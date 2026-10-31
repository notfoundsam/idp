package idp.ops

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.springframework.boot.runApplication
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class OpsApplicationTest :
    StringSpec({
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
            runApplication<OpsApplication>("--server.port=0", "--management.server.port=0").use { context ->
                val main = context.environment.getProperty("local.server.port")
                val management = context.environment.getProperty("local.management.port")

                val health = get(management, "/actuator/health")
                health.statusCode() shouldBe 200
                health.body() shouldContain "\"status\":\"UP\""

                get(main, "/actuator/health").statusCode() shouldBe 404
            }
        }

        "the management port exposes nothing but health" {
            runApplication<OpsApplication>("--server.port=0", "--management.server.port=0").use { context ->
                val management = context.environment.getProperty("local.management.port")

                get(management, "/actuator/env").statusCode() shouldBe 404
            }
        }
    })
