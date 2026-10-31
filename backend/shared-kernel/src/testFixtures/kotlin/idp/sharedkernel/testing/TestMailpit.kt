package idp.sharedkernel.testing

import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

private const val SMTP_PORT = 1025
private const val API_PORT = 8025
private const val REJECTED = 451

private val json = JsonMapper.builder().build()

data class ReceivedMail(
    val from: String,
    val to: List<String>,
    val subject: String,
    val text: String,
    val html: String,
)

class MailpitServer internal constructor() : AutoCloseable {
    private val container: GenericContainer<*> =
        GenericContainer(System.getProperty("idp.mailpit.image"))
            .withExposedPorts(SMTP_PORT, API_PORT)
            .withEnv("MP_ENABLE_CHAOS", "true")
            .waitingFor(Wait.forHttp("/readyz").forPort(API_PORT))
            .also { it.start() }

    private val http: HttpClient = HttpClient.newHttpClient()
    private val api = "http://${container.host}:${container.getMappedPort(API_PORT)}/api/v1"

    val host: String = container.host
    val smtpPort: Int = container.getMappedPort(SMTP_PORT)

    fun messages(): List<ReceivedMail> =
        json.readTree(get("/messages"))["messages"].toList().map { summary ->
            val message = json.readTree(get("/message/${summary["ID"].asString()}"))
            ReceivedMail(
                from = message["From"]["Address"].asString(),
                to = (message["To"].toList() + message["Bcc"].toList()).map(::address),
                subject = message["Subject"].asString(),
                text = message["Text"].asString(),
                html = message["HTML"].asString(),
            )
        }

    fun clear() {
        send(HttpRequest.newBuilder(URI("$api/messages")).DELETE().build())
    }

    /** Mailpit answers every RCPT with an SMTP error until [acceptRecipients]. */
    fun rejectRecipients() = chaos(probability = 100)

    fun acceptRecipients() = chaos(probability = 0)

    override fun close() = container.stop()

    private fun address(node: JsonNode): String = node["Address"].asString()

    private fun chaos(probability: Int) {
        val body = """{"Recipient":{"ErrorCode":$REJECTED,"Probability":$probability}}"""
        send(
            HttpRequest
                .newBuilder(URI("$api/chaos"))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body))
                .build(),
        )
    }

    private fun get(path: String): String = send(HttpRequest.newBuilder(URI("$api$path")).build())

    private fun send(request: HttpRequest): String {
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == 200) { "Mailpit answered ${response.statusCode()}: ${response.body()}" }
        return response.body()
    }
}

object TestMailpit {
    fun start() = MailpitServer()
}
