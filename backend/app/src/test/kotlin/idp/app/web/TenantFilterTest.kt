package idp.app.web

import idp.app.AppApplication
import idp.sharedkernel.migration.TenantMigrationRunner
import idp.sharedkernel.migration.TenantSchema
import idp.sharedkernel.migration.TenantSchemaFactory
import idp.sharedkernel.tenant.TenantContext
import idp.sharedkernel.tenant.TenantTransaction
import idp.sharedkernel.testing.TestDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldBeEmpty
import io.kotest.matchers.string.shouldContain
import org.springframework.boot.SpringApplication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID
import javax.sql.DataSource

@RestController
private class ProbeController(
    private val transaction: TenantTransaction,
) {
    @GetMapping("/probe")
    fun probe(): String {
        val tenant = TenantContext.current()
        val role = transaction.execute { it.fetchOne("SELECT current_user")!!.get(0).toString() }
        return "${tenant.tenantId} ${tenant.kekUri} $role"
    }
}

private fun DataSource.update(sql: String) {
    connection.use { connection -> connection.createStatement().use { it.execute(sql) } }
}

class TenantFilterTest :
    FunSpec({
        val database = TestDatabase.create()
        val owner = database.owner()
        val active: UUID = UUID.randomUUID()

        fun tenant(
            id: UUID,
            host: String,
            status: String,
        ) {
            owner.update("INSERT INTO platform.tenants (id, name, status, kek_uri) VALUES ('$id', 'tenant', '$status', 'hcvault://$host')")
            owner.update("INSERT INTO platform.tenant_domains (domain, tenant_id) VALUES ('$host', '$id')")
        }

        TenantMigrationRunner(owner).migrateAll()
        tenant(active, "acme.idp.test", "ACTIVE")
        TenantSchemaFactory(owner).create(active)
        listOf("REQUESTED", "APPROVED", "PROVISIONING", "SUSPENDED", "OFFBOARDING", "DELETED").forEach {
            tenant(UUID.randomUUID(), "${it.lowercase()}.idp.test", it)
        }
        database.pooled("app_runtime")

        val application =
            SpringApplication(AppApplication::class.java, ProbeController::class.java).run(
                "--server.port=0",
                "--management.server.port=0",
                "--spring.datasource.url=${database.pooledUrl()}",
                "--spring.datasource.username=app_runtime",
                "--spring.datasource.password=app_runtime",
                "--THROTTLE_ENABLED=false",
            )
        val management = application.environment.getProperty("local.management.port")

        afterSpec { application.close() }

        fun get(
            host: String,
            path: String,
            port: String? = application.environment.getProperty("local.server.port"),
        ): HttpResponse<String> =
            HttpClient.newHttpClient().send(
                HttpRequest
                    .newBuilder(URI("http://localhost:$port$path"))
                    .header("Host", host)
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )

        test("an active tenant reaches the application with its context and database role") {
            val response = get("acme.idp.test", "/probe")

            response.statusCode() shouldBe 200
            response.body() shouldBe "$active hcvault://acme.idp.test ${TenantSchema(active).name}"
        }

        test("the port in the Host header does not matter") {
            get("acme.idp.test:8443", "/probe").statusCode() shouldBe 200
        }

        test("an unknown host gets an empty 404") {
            val response = get("nobody.idp.test", "/probe")

            response.statusCode() shouldBe 404
            response.body().shouldBeEmpty()
        }

        test("a suspended or offboarding tenant is refused with no details") {
            listOf("suspended", "offboarding").forEach {
                val response = get("$it.idp.test", "/probe")

                response.statusCode() shouldBe 403
                response.body().shouldBeEmpty()
            }
        }

        test("a tenant that is not live looks like an unknown host") {
            listOf("requested", "approved", "provisioning", "deleted").forEach {
                val response = get("$it.idp.test", "/probe")

                response.statusCode() shouldBe 404
                response.body().shouldBeEmpty()
            }
        }

        test("the context ends with the request") {
            get("acme.idp.test", "/probe").statusCode() shouldBe 200
            get("nobody.idp.test", "/probe").statusCode() shouldBe 404
            get("acme.idp.test", "/nothing").body() shouldContain "\"status\":404"
        }

        test("health needs no tenant host") {
            val health = get("nobody.idp.test", "/actuator/health", management)

            health.statusCode() shouldBe 200
            health.body() shouldContain "\"status\":\"UP\""
        }
    })
