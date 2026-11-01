package idp.ops

import idp.sharedkernel.crypto.adapter.OpenBaoKeyManagementService
import idp.sharedkernel.migration.TenantMigrationRunner
import idp.sharedkernel.testing.TestDatabase
import idp.sharedkernel.testing.TestOpenBao
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID

const val CONTACT = "admin@acme.example"
const val MASK = "a***@acme.example"

/** A migrated database of its own and a platform KEK that the token of `ops` may only encrypt with, as in production. */
class OpsEnvironment {
    val database = TestDatabase.create()
    val owner = database.owner().also { TenantMigrationRunner(it).migrateAll() }
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

    val arguments = settings + arrayOf("--server.port=0", "--management.server.port=0")

    val localArguments = arguments + "--spring.profiles.active=local"

    fun audit(): List<Map<String, Any?>> = sql.queryForList("SELECT * FROM platform.operator_audit ORDER BY occurred_at, id")

    fun events(tenant: UUID): List<String?> =
        sql.queryForList("SELECT event_type FROM platform.outbox WHERE aggregate_id = ?", String::class.java, tenant)
}

class OpsResponse(
    val status: Int,
    val body: String,
) {
    fun json(): JsonNode = JsonMapper().readTree(body)
}

class OpsClient(
    private val port: String?,
) {
    private val http = HttpClient.newHttpClient()

    fun get(path: String) = send(HttpRequest.newBuilder(uri(path)).GET())

    fun post(
        path: String,
        body: String? = null,
    ) = send(
        HttpRequest
            .newBuilder(uri(path))
            .header("Content-Type", "application/json")
            .POST(body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody()),
    )

    fun submit(
        subdomain: String = "acme",
        contact: String = CONTACT,
    ): UUID {
        val response = post("/api/applications", """{"companyName":"Acme","subdomain":"$subdomain","contact":"$contact"}""")
        check(response.status == 201) { "Submitting answered ${response.status}" }
        return UUID.fromString(response.json().get("id").asString())
    }

    fun tenantOf(application: UUID): UUID = UUID.fromString(get("/api/applications/$application").json().get("tenantId").asString())

    private fun uri(path: String) = URI("http://localhost:$port$path")

    private fun send(request: HttpRequest.Builder): OpsResponse {
        val response = http.send(request.build(), HttpResponse.BodyHandlers.ofString())
        return OpsResponse(response.statusCode(), response.body())
    }
}
