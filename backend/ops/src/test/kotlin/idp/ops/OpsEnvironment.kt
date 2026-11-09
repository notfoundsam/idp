package idp.ops

import idp.sharedkernel.crypto.adapter.OpenBaoKeyManagementService
import idp.sharedkernel.testing.TestCa
import idp.sharedkernel.testing.TestDatabase
import idp.sharedkernel.testing.TestOpenBao
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64
import java.util.UUID
import java.util.zip.Inflater

const val CONTACT = "admin@acme.example"
const val MASK = "a***@acme.example"
const val OPS_HOST = "ops.idp.test"
const val SESSION_COOKIE = "__Host-ops-session"
const val CSRF_COOKIE = "XSRF-TOKEN"
const val CSRF_HEADER = "X-XSRF-TOKEN"

/**
 * A migrated database of its own, a platform KEK that the token of `ops` may only encrypt with, as in production, and a fake IdP
 * whose metadata `ops` is given.
 */
class OpsEnvironment(
    val idp: FakeIdp = FakeIdp(),
) {
    val database = TestDatabase.migrated()
    val owner = database.owner()
    val sql = JdbcTemplate(owner)
    val root = OpenBaoKeyManagementService(TestOpenBao.address, TestOpenBao.TOKEN)
    private val keyName = "platform-${UUID.randomUUID()}"
    val platformKekUri = root.createKey(keyName)

    private val encryptOnly =
        TestOpenBao.tokenWithPolicy(
            "encrypt-$keyName",
            """
            path "transit/keys/$keyName" { capabilities = ["read"] }
            path "transit/encrypt/$keyName" { capabilities = ["update"] }
            """,
        )

    init {
        database.role("ops_runtime")
    }

    val settings =
        arrayOf(
            "--DB_URL=${database.pooledUrl()}",
            "--DB_USERNAME=ops_runtime",
            "--DB_PASSWORD=ops_runtime",
            "--TENANTS_BASE_DOMAIN=idp.test",
            "--KMS_NEW_KEYS=hcvault",
            "--PLATFORM_KEK_URI=$platformKekUri",
            "--OPENBAO_ADDRESS=${TestOpenBao.address}",
            "--OPENBAO_TOKEN=$encryptOnly",
        )

    private val server = settings + arrayOf("--server.port=0", "--management.server.port=0", "--IDP_METADATA_FILE=${idp.metadataFile}")

    val arguments = server + "--SERVER_TLS_ENABLED=false"

    /** The CA of [tlsArguments], whose server certificate names `localhost`. */
    val serverCa = TestCa("Test internal CA")

    /** [arguments] with TLS on both ports, which the deployed profiles demand. */
    val tlsArguments: Array<String> by lazy {
        val (certificate, key) = serverCa.issue("localhost")
        server + arrayOf("--SERVER_TLS_CERT=$certificate", "--SERVER_TLS_KEY=$key")
    }

    fun audit(): List<Map<String, Any?>> = sql.queryForList("SELECT * FROM platform.operator_audit ORDER BY occurred_at, id")

    fun events(tenant: UUID): List<String?> =
        sql.queryForList("SELECT event_type FROM platform.outbox WHERE aggregate_id = ?", String::class.java, tenant)
}

class OpsResponse(
    val status: Int,
    val body: String,
    private val headers: HttpHeaders,
) {
    fun json(): JsonNode = JsonMapper().readTree(body)

    fun header(name: String): String? = headers.firstValue(name).orElse(null)

    fun headers(name: String): List<String> = headers.allValues(name)
}

/** The AuthnRequest `ops` sent the browser to the IdP with. */
class SentRequest(
    val id: String,
    val xml: String,
    val relayState: String?,
    val location: URI,
)

/**
 * A browser of `ops`: it names the host of `ops` as Traefik would, keeps the cookies it is given and echoes the CSRF cookie on a
 * call that changes something, as the operator UI will.
 */
class OpsClient(
    private val port: String?,
    private val host: String = OPS_HOST,
) {
    private val http = HttpClient.newHttpClient()
    val cookies = mutableMapOf<String, String>()

    fun get(path: String) = send(request(path).GET())

    fun trace(path: String) = send(request(path).method("TRACE", HttpRequest.BodyPublishers.noBody()))

    fun delete(path: String) = send(unsafe(request(path)).DELETE())

    fun post(
        path: String,
        body: String? = null,
    ) = send(
        unsafe(request(path))
            .header("Content-Type", "application/json")
            .POST(body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody()),
    )

    fun postForm(
        path: String,
        fields: Map<String, String?>,
    ) = send(
        request(path)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    fields.entries
                        .filter { it.value != null }
                        .joinToString("&") { (name, value) -> "$name=${URLEncoder.encode(value, Charsets.UTF_8)}" },
                ),
            ),
    )

    /** Starts a login as a browser would, and reads the AuthnRequest out of the redirect to the IdP. */
    fun startLogin(): SentRequest {
        val response = get("/saml2/authenticate/ops-sso")
        check(response.status == 302) { "Starting a login answered ${response.status}" }
        val location = URI(checkNotNull(response.header("Location")))
        val query =
            location.rawQuery.split("&").associate {
                val (name, value) = it.split("=", limit = 2)
                name to URLDecoder.decode(value, Charsets.UTF_8)
            }
        val xml = inflate(Base64.getDecoder().decode(query.getValue("SAMLRequest")))
        val id = checkNotNull(Regex("""ID="([^"]+)"""").find(xml)).groupValues[1]
        return SentRequest(id, xml, query["RelayState"], location)
    }

    fun finishLogin(
        samlResponse: String,
        relayState: String?,
    ): OpsResponse = postForm("/login/saml2/sso/ops-sso", mapOf("SAMLResponse" to samlResponse, "RelayState" to relayState))

    /** A full login of [login] through [idp], which answers the AuthnRequest of this browser. */
    fun login(
        idp: FakeIdp,
        login: (SentRequest) -> Login = { Login(inResponseTo = it.id) },
    ): OpsResponse {
        val request = startLogin()
        return finishLogin(idp.response(login(request)), request.relayState)
    }

    fun submit(
        subdomain: String = "acme",
        contact: String = CONTACT,
    ): UUID {
        val response = post("/api/applications", """{"companyName":"Acme","subdomain":"$subdomain","contact":"$contact"}""")
        check(response.status == 201) { "Submitting answered ${response.status}" }
        return UUID.fromString(response.json().get("id").asString())
    }

    fun tenantOf(application: UUID): UUID = UUID.fromString(get("/api/applications/$application").json().get("tenantId").asString())

    private fun request(path: String): HttpRequest.Builder {
        val builder = HttpRequest.newBuilder(URI("http://localhost:$port$path")).header("Host", host)
        if (cookies.isNotEmpty()) builder.header("Cookie", cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
        return builder
    }

    private fun unsafe(builder: HttpRequest.Builder): HttpRequest.Builder =
        cookies[CSRF_COOKIE]?.let { builder.header(CSRF_HEADER, it) } ?: builder

    private fun send(request: HttpRequest.Builder): OpsResponse {
        val response = http.send(request.build(), HttpResponse.BodyHandlers.ofString())
        response.headers().allValues("Set-Cookie").forEach(::remember)
        return OpsResponse(response.statusCode(), response.body(), response.headers())
    }

    private fun remember(header: String) {
        val (name, value) = header.substringBefore(";").split("=", limit = 2)
        val expired = header.split(";").any { it.trim().equals("Max-Age=0", ignoreCase = true) }
        if (expired || value.isEmpty()) cookies.remove(name) else cookies[name] = value
    }
}

private fun inflate(bytes: ByteArray): String {
    val inflater = Inflater(true).apply { setInput(bytes) }
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(4096)
    while (!inflater.finished()) {
        val count = inflater.inflate(buffer)
        if (count == 0 && inflater.needsInput()) break
        out.write(buffer, 0, count)
    }
    return out.toString(Charsets.UTF_8)
}
