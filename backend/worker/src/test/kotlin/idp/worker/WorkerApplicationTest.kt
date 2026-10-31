package idp.worker

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.springframework.boot.runApplication
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class WorkerApplicationTest :
    StringSpec({
        "health answers on the management port" {
            runApplication<WorkerApplication>("--server.port=0").use { context ->
                val port = context.environment.getProperty("local.server.port")

                val health =
                    HttpClient
                        .newHttpClient()
                        .send(
                            HttpRequest.newBuilder(URI("http://localhost:$port/actuator/health")).build(),
                            HttpResponse.BodyHandlers.ofString(),
                        )

                health.statusCode() shouldBe 200
                health.body() shouldContain "\"status\":\"UP\""
            }
        }
    })
