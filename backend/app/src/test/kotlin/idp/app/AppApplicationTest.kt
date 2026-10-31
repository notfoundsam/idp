package idp.app

import idp.sharedkernel.migration.TenantMigrationRunner
import idp.sharedkernel.testing.TestDatabase
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.springframework.boot.runApplication
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class AppApplicationTest :
    StringSpec({
        val database = TestDatabase.create()
        TenantMigrationRunner(database.owner()).migrateAll()
        database.role("app_runtime")
        val arguments =
            arrayOf(
                "--server.port=0",
                "--management.server.port=0",
                "--spring.datasource.url=${database.url}",
                "--spring.datasource.username=app_runtime",
                "--spring.datasource.password=app_runtime",
                "--VALKEY_URL=redis://127.0.0.1:1",
                "--THROTTLE_IP_HMAC_KEY=test-key",
            )

        fun get(
            port: String?,
            path: String,
        ): HttpResponse<String> =
            HttpClient
                .newHttpClient()
                .send(
                    HttpRequest.newBuilder(URI("http://localhost:$port$path")).build(),
                    HttpResponse.BodyHandlers.ofString(),
                )

        "health answers on the management port only" {
            runApplication<AppApplication>(*arguments).use { context ->
                val main = context.environment.getProperty("local.server.port")
                val management = context.environment.getProperty("local.management.port")

                val health = get(management, "/actuator/health")
                health.statusCode() shouldBe 200
                health.body() shouldContain "\"status\":\"UP\""

                get(main, "/actuator/health").statusCode() shouldBe 404
            }
        }

        "the management port exposes nothing but health" {
            runApplication<AppApplication>(*arguments).use { context ->
                val management = context.environment.getProperty("local.management.port")

                get(management, "/actuator/env").statusCode() shouldBe 404
            }
        }
    })
