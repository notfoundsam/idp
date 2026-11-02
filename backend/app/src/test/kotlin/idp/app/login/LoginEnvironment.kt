package idp.app.login

import idp.app.AppApplication
import idp.audit.application.AuditEntryView
import idp.audit.application.AuditService
import idp.authentication.application.CsrfCookie
import idp.identity.application.IdentityService
import idp.identity.application.PasswordService
import idp.sharedkernel.audit.Actor
import idp.sharedkernel.audit.AuditLog
import idp.sharedkernel.crypto.KeyHierarchy
import idp.sharedkernel.crypto.adapter.OpenBaoKeyManagementService
import idp.sharedkernel.migration.TenantSchema
import idp.sharedkernel.security.UserRole
import idp.sharedkernel.tenant.TenantContext
import idp.sharedkernel.tenant.TenantTransaction
import idp.sharedkernel.testing.TestDatabase
import idp.sharedkernel.testing.TestOpenBao
import org.springframework.boot.SpringApplication
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

const val ACME = "acme.idp.test"
const val GLOBEX = "globex.idp.test"
const val EMAIL = "ada.lovelace@acme.test"
const val PASSWORD = "glass-otter-musters-quietly"
const val WRONG_PASSWORD = "brass-heron-wanders-loudly"
const val USER_AGENT = "Mozilla/5.0 (Macintosh)"

private const val AUDIT_PAGE = 200
private const val TOTP_STEP_SECONDS = 30L
private const val TOTP_MODULUS = 1_000_000
private const val BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
private const val BASE32_BITS = 5
private const val BYTE_BITS = 8
private val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(30)

class TestTenant(
    val context: TenantContext,
    val host: String,
) {
    val schema: String = TenantSchema(context.tenantId).name

    fun table(name: String) = "\"$schema\".$name"

    fun <T> acting(block: () -> T): T = TenantContext.with(context, block)
}

/** A migrated database with two tenants. `acme` relaxes MFA, so a password completes a login there. `globex` keeps the default. */
class LoginEnvironment {
    val database = TestDatabase.migrated()
    val owner = database.owner()
    val sql = JdbcTemplate(owner)
    private val kms = OpenBaoKeyManagementService(TestOpenBao.address, TestOpenBao.TOKEN)
    val acme = tenant(ACME)
    val globex = tenant(GLOBEX)

    init {
        val transaction = TenantTransaction(database.pooled("app_runtime"))
        val keys = KeyHierarchy(kms, transaction, Duration.ofMinutes(1))
        listOf(acme, globex).forEach { tenant -> tenant.acting { keys.createTenantKey() } }
        sql.update("UPDATE ${acme.table("tenant_policies")} SET mfa_for_users = false")
    }

    private fun tenant(host: String): TestTenant {
        val id = database.tenantSchema()
        val kekUri = kms.createKey("tenant-${UUID.randomUUID()}")
        sql.update("INSERT INTO platform.tenants (id, name, status, kek_uri) VALUES (?, 'tenant', 'ACTIVE', ?)", id, kekUri)
        sql.update("INSERT INTO platform.tenant_domains (domain, tenant_id) VALUES (?, ?)", host, id)
        return TestTenant(TenantContext(id, kekUri), host)
    }

    /** [throttled] is off by default, so that only the tests about limits need Valkey. */
    fun start(
        vararg overrides: String,
        throttled: Boolean = false,
        sources: Array<Class<*>> = emptyArray(),
    ): ConfigurableApplicationContext =
        SpringApplication(AppApplication::class.java, *sources).run(
            "--server.port=0",
            "--management.server.port=0",
            "--spring.datasource.url=${database.pooledUrl()}",
            "--spring.datasource.username=app_runtime",
            "--spring.datasource.password=app_runtime",
            "--THROTTLE_ENABLED=$throttled",
            "--KMS_NEW_KEYS=hcvault",
            "--OPENBAO_ADDRESS=${TestOpenBao.address}",
            "--OPENBAO_TOKEN=${TestOpenBao.TOKEN}",
            *overrides,
        )

    fun ConfigurableApplicationContext.user(
        email: String = EMAIL,
        tenant: TestTenant = acme,
        password: String? = PASSWORD,
        status: String = "ACTIVE",
        role: UserRole = UserRole.USER,
    ): UUID =
        tenant.acting {
            val id = getBean(IdentityService::class.java).invite(email, role, Actor.System)
            password?.let { getBean(PasswordService::class.java).setPassword(id, it, emptySet(), Actor.System) }
            sql.update("UPDATE ${tenant.table("users")} SET status = ? WHERE id = ?", status, id)
            id
        }

    fun ConfigurableApplicationContext.audit(tenant: TestTenant = acme): List<AuditEntryView> =
        tenant.acting { (getBean(AuditLog::class.java) as AuditService).read(AUDIT_PAGE) }

    fun failedAttempts(
        user: UUID,
        tenant: TestTenant = acme,
    ): Int =
        sql.queryForObject(
            "SELECT failed_attempts FROM ${tenant.table("password_credentials")} WHERE user_id = ?",
            Int::class.java,
            user,
        )!!

    fun openFlows(tenant: TestTenant = acme): Int = sql.queryForObject("SELECT count(*) FROM ${tenant.table("flows")}", Int::class.java)!!
}

class Reply(
    private val response: HttpResponse<String>,
) {
    val status: Int get() = response.statusCode()
    val body: String get() = response.body()

    fun json(): JsonNode = JsonMapper().readTree(body)

    fun header(name: String): String? = response.headers().firstValue(name).orElse(null)

    fun headers(): Map<String, List<String>> = response.headers().map()

    fun cookies(): List<String> = response.headers().allValues("Set-Cookie")
}

/** Keeps cookies and echoes the CSRF cookie the way the page does. Each instance is one browser. */
class Browser(
    private val application: ConfigurableApplicationContext,
    val host: String = ACME,
) {
    val cookies: MutableMap<String, String> = ConcurrentHashMap()
    private val http = HttpClient.newHttpClient()

    fun get(
        path: String,
        vararg headers: String,
    ) = send(request(path, *headers).GET())

    fun post(
        path: String,
        body: String? = null,
        vararg headers: String,
        echoCsrf: Boolean = true,
    ): Reply {
        val request = request(path, *headers).header("Content-Type", "application/json")
        if (echoCsrf) cookies[CsrfCookie.NAME]?.let { request.header(CsrfCookie.HEADER, it) }
        return send(request.POST(body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody()))
    }

    fun send(
        method: String,
        path: String,
        publisher: HttpRequest.BodyPublisher = HttpRequest.BodyPublishers.noBody(),
        vararg headers: String,
    ) = send(request(path, *headers).header("Content-Type", "application/json").method(method, publisher))

    private fun request(
        path: String,
        vararg headers: String,
    ): HttpRequest.Builder {
        val port = application.environment.getProperty("local.server.port")
        val request =
            HttpRequest
                .newBuilder(URI("http://localhost:$port$path"))
                .header("Host", host)
                .header("User-Agent", USER_AGENT)
                .timeout(REQUEST_TIMEOUT)
        headers.toList().chunked(2).forEach { (name, value) -> request.header(name, value) }
        if (cookies.isNotEmpty()) request.header("Cookie", cookies.entries.joinToString("; ") { (name, value) -> "$name=$value" })
        return request
    }

    private fun send(request: HttpRequest.Builder): Reply {
        val reply = Reply(http.send(request.build(), HttpResponse.BodyHandlers.ofString()))
        reply.cookies().forEach { cookie ->
            val (name, value) = cookie.substringBefore(';').split('=', limit = 2)
            cookies[name] = value
        }
        return reply
    }

    fun startFlow(body: String? = null): String {
        val reply = post("/api/flows", body)
        check(reply.status == 201) { "Starting a flow answered ${reply.status}" }
        return reply.json().get("id").asString()
    }

    fun identified(login: String = EMAIL): String = startFlow().also { post("/api/flows/$it/login", loginBody(login)) }

    fun password(
        flow: String,
        password: String = PASSWORD,
    ) = post("/api/flows/$flow/password", passwordBody(password))

    fun complete(flow: String) = post("/api/flows/$flow/complete")

    /** A full login under a relaxed policy. */
    fun loggedIn(
        email: String = EMAIL,
        password: String = PASSWORD,
    ): Browser {
        val flow = identified(email)
        check(password(flow, password).status == 200) { "The password was refused" }
        check(complete(flow).status == 200) { "The login did not complete" }
        return this
    }
}

fun loginBody(login: String) = """{"login":"$login"}"""

fun passwordBody(password: String) = """{"password":"$password"}"""

fun codeBody(code: String) = """{"code":"$code"}"""

fun tokenBody(token: String) = """{"token":"$token"}"""

fun emailBody(email: String) = """{"email":"$email"}"""

fun invitationBody(
    token: String,
    password: String,
) = """{"token":"$token","password":"$password"}"""

fun JsonNode.text(name: String): String = get(name).asString()

fun JsonNode.strings(name: String): List<String> = get(name).values().map { it.asString() }

private fun decodeBase32(value: String): ByteArray {
    var buffer = 0
    var bits = 0
    val bytes = mutableListOf<Byte>()
    value.trimEnd('=').forEach { char ->
        buffer = (buffer shl BASE32_BITS) or BASE32.indexOf(char)
        bits += BASE32_BITS
        if (bits >= BYTE_BITS) {
            bits -= BYTE_BITS
            bytes += (buffer shr bits).toByte()
            buffer = buffer and ((1 shl bits) - 1)
        }
    }
    return bytes.toByteArray()
}

/** RFC 6238 written out here, so the test does not trust the code it checks. [shift] counts steps from now. */
fun totpCode(
    secret: String,
    shift: Long = 0,
): String {
    val step = Instant.now().epochSecond / TOTP_STEP_SECONDS + shift
    val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(decodeBase32(secret), "HmacSHA1")) }
    val hash = mac.doFinal(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(step).array())
    val offset = hash.last().toInt() and 0x0f
    val value = ByteBuffer.wrap(hash, offset, Int.SIZE_BYTES).int and Int.MAX_VALUE
    return (value % TOTP_MODULUS).toString().padStart(6, '0')
}
