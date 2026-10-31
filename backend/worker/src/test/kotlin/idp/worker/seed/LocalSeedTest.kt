package idp.worker.seed

import idp.sharedkernel.migration.TenantMigrationRunner
import idp.sharedkernel.migration.TenantSchema
import idp.sharedkernel.testing.TestDatabase
import idp.worker.WorkerApplication
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.springframework.boot.runApplication
import org.springframework.jdbc.core.JdbcTemplate
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID

class LocalSeedTest :
    FunSpec({
        test("the seed does not run outside the local profile") {
            runApplication<WorkerApplication>("--server.port=0").use { context ->
                context.getBeansOfType(LocalSeed::class.java).keys.shouldBeEmpty()
            }
        }

        context("in the local profile") {
            val database = TestDatabase.create()
            val owner = database.owner()
            TenantMigrationRunner(owner).migrateAll()
            database.role("provisioner")
            val jdbc = JdbcTemplate(owner)
            val arguments =
                arrayOf(
                    "--server.port=0",
                    "--spring.profiles.active=local",
                    "--idp.seed.url=${database.url}?options=-c%20role%3Didp_owner",
                    "--idp.seed.username=provisioner",
                    "--idp.seed.password=provisioner",
                )

            fun tenants(): List<String> =
                jdbc
                    .queryForList(
                        """
                    SELECT d.domain FROM platform.tenant_domains d
                    JOIN platform.tenants t ON t.id = d.tenant_id WHERE t.status = 'ACTIVE'
                    """,
                        String::class.java,
                    ).filterNotNull()

            test("two active tenants appear with migrated schemas") {
                runApplication<WorkerApplication>(*arguments).use { context ->
                    tenants() shouldContainExactlyInAnyOrder listOf("acme.idp.test", "globex.idp.test")

                    jdbc.queryForList("SELECT id FROM platform.tenants", UUID::class.java).filterNotNull().forEach { id ->
                        val schema = TenantSchema(id).name
                        val history = "SELECT count(*) FROM \"$schema\".flyway_schema_history WHERE success"
                        (jdbc.queryForObject(history, Int::class.java)!! > 0) shouldBe true
                    }

                    val port = context.environment.getProperty("local.server.port")
                    val readiness =
                        HttpClient.newHttpClient().send(
                            HttpRequest.newBuilder(URI("http://localhost:$port/actuator/health/readiness")).build(),
                            HttpResponse.BodyHandlers.ofString(),
                        )
                    readiness.statusCode() shouldBe 200
                }
            }

            test("a restart changes nothing") {
                runApplication<WorkerApplication>(*arguments).close()

                tenants() shouldContainExactlyInAnyOrder listOf("acme.idp.test", "globex.idp.test")
                jdbc.queryForObject("SELECT count(*) FROM platform.tenants", Int::class.java) shouldBe 2
            }
        }
    })
