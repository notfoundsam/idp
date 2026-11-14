package idp.app.observability

import com.sun.net.httpserver.HttpServer
import idp.app.AppApplication
import idp.sharedkernel.testing.TestCa
import idp.sharedkernel.testing.TestDatabase
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.micrometer.core.instrument.MeterRegistry
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.sdk.logs.SdkLoggerProvider
import io.opentelemetry.sdk.logs.export.LogRecordExporter
import io.opentelemetry.sdk.logs.export.SimpleLogRecordProcessor
import io.opentelemetry.sdk.testing.exporter.InMemoryLogRecordExporter
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import io.opentelemetry.sdk.trace.export.SpanExporter
import org.slf4j.LoggerFactory
import org.springframework.boot.runApplication
import org.springframework.context.ConfigurableApplicationContext
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.ImageFromDockerfile
import tools.jackson.databind.json.JsonMapper
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

private const val RECEIVER = 4318
private const val LOG_GROUP = "/idp-test/otlp"
private const val SECURITY_LOG_GROUP = "/idp-test/otlp/security"
private const val METRICS_LOG_GROUP = "/idp-test/otlp/metrics"
private const val PUT_LOG_EVENTS = "Logs_20140328.PutLogEvents"
private val JSON = JsonMapper.builder().build()

/** CloudWatch Logs and the OTLP endpoint of X-Ray as the collector calls them, on the host. */
private class FakeAws : AutoCloseable {
    class Call(
        val target: String?,
        val path: String,
        val authorization: String?,
        val body: ByteArray,
    )

    val calls = CopyOnWriteArrayList<Call>()

    private val server =
        HttpServer.create(InetSocketAddress(0), 0).apply {
            createContext("/") { exchange ->
                val call =
                    Call(
                        exchange.requestHeaders.getFirst("X-Amz-Target"),
                        exchange.requestURI.path,
                        exchange.requestHeaders.getFirst("Authorization"),
                        exchange.requestBody.readAllBytes(),
                    )
                calls += call
                val answer = if (call.path == "/v1/traces") ByteArray(0) else "{}".toByteArray()
                val type = if (call.path == "/v1/traces") "application/x-protobuf" else "application/x-amz-json-1.1"
                exchange.responseHeaders.add("Content-Type", type)
                exchange.sendResponseHeaders(200, if (answer.isEmpty()) -1 else answer.size.toLong())
                exchange.responseBody.use { it.write(answer) }
            }
            start()
        }

    val port: Int get() = server.address.port

    /** Stream and message of every event that PutLogEvents wrote to the group. */
    fun events(group: String): List<Pair<String, String>> =
        calls
            .filter { it.target == PUT_LOG_EVENTS }
            .map { JSON.readTree(it.body) }
            .filter { it["logGroupName"].asString() == group }
            .flatMap { put ->
                val events = put["logEvents"]
                (0 until events.size()).map { put["logStreamName"].asString() to events[it]["message"].asString() }
            }

    override fun close() = server.stop(0)
}

/**
 * The collector of `infra/otel-collector` with a read-only root as in ECS, fed by the exporters of `app` over TLS, in front of a
 * fake of CloudWatch Logs and X-Ray. It proves the trust of the exporters, the routes of the collector and what reaches AWS.
 */
class ProductionTelemetryTest :
    StringSpec({
        val database = TestDatabase.migrated()
        database.role("app_runtime")
        val internalCa = TestCa("Test internal CA")
        val otherCa = TestCa("Test other CA")
        val aws = FakeAws()
        val (certificate, key) = internalCa.issue("localhost")
        val collector: GenericContainer<*> =
            GenericContainer(ImageFromDockerfile().withFileFromPath(".", Path.of(System.getProperty("idp.collector.dir"))))
                .withExposedPorts(RECEIVER)
                .withExtraHost("host.docker.internal", "host-gateway")
                .withEnv(
                    mapOf(
                        "TLS_CERT" to certificate,
                        "TLS_KEY" to key,
                        "AWS_REGION" to "eu-central-1",
                        "AWS_ACCESS_KEY_ID" to "test",
                        "AWS_SECRET_ACCESS_KEY" to "test",
                        "LOGS_ENDPOINT" to "http://host.docker.internal:${aws.port}",
                        "TRACES_ENDPOINT" to "http://host.docker.internal:${aws.port}/v1/traces",
                        "LOG_GROUP" to LOG_GROUP,
                        "SECURITY_LOG_GROUP" to SECURITY_LOG_GROUP,
                        "METRICS_LOG_GROUP" to METRICS_LOG_GROUP,
                        "METRICS_NAMESPACE" to "idp-test",
                    ),
                ).withCreateContainerCmdModifier { it.hostConfig?.withReadonlyRootfs(true) }
                .waitingFor(Wait.forLogMessage(".*Everything is ready.*", 1))
        collector.start()

        afterSpec {
            collector.stop()
            aws.close()
        }

        fun app(
            ca: String,
            host: String = "localhost",
        ): ConfigurableApplicationContext {
            val endpoint = "https://$host:${collector.getMappedPort(RECEIVER)}"
            return runApplication<AppApplication>(
                "--server.port=0",
                "--management.server.port=0",
                "--SERVER_TLS_ENABLED=false",
                "--spring.datasource.url=${database.url}",
                "--spring.datasource.username=app_runtime",
                "--spring.datasource.password=app_runtime",
                "--VALKEY_URL=redis://127.0.0.1:1",
                "--THROTTLE_IP_HMAC_KEY=test-key",
                "--KMS_NEW_KEYS=hcvault",
                "--OPENBAO_ADDRESS=http://127.0.0.1:1",
                "--OTLP_TLS_CA=$ca",
                "--management.opentelemetry.tracing.export.otlp.endpoint=$endpoint/v1/traces",
                "--management.opentelemetry.logging.export.otlp.endpoint=$endpoint/v1/logs",
                "--management.otlp.metrics.export.url=$endpoint/v1/metrics",
                "--management.otlp.metrics.export.enabled=true",
                "--management.otlp.metrics.export.aggregation-temporality=delta",
                "--management.otlp.metrics.export.step=1s",
                "--management.opentelemetry.tracing.export.schedule-delay=200ms",
                "--management.opentelemetry.logging.export.schedule-delay=200ms",
                "--management.tracing.sampling.probability=1.0",
            )
        }

        fun ConfigurableApplicationContext.exportsSucceed(): List<Boolean> {
            val spans = InMemorySpanExporter.create()
            SdkTracerProvider
                .builder()
                .addSpanProcessor(SimpleSpanProcessor.create(spans))
                .build()
                .use {
                    it
                        .get("probe")
                        .spanBuilder("probe")
                        .startSpan()
                        .end()
                }
            val logs = InMemoryLogRecordExporter.create()
            SdkLoggerProvider
                .builder()
                .addLogRecordProcessor(SimpleLogRecordProcessor.create(logs))
                .build()
                .use {
                    it
                        .get("probe")
                        .logRecordBuilder()
                        .setBody("probe")
                        .emit()
                }
            val traces = getBean(SpanExporter::class.java).export(spans.finishedSpanItems)
            val lines = getBean(LogRecordExporter::class.java).export(logs.finishedLogRecordItems)
            return listOf(traces, lines).map { it.join(10, TimeUnit.SECONDS).isSuccess }
        }

        "lines, metrics and spans reach their stores, the lines of idp.security a log group of their own" {
            app(internalCa.pem).use { context ->
                context.exportsSucceed() shouldBe listOf(true, true)
                LoggerFactory.getLogger("idp.security").info("event=throttle.rejected scope=probe")
                LoggerFactory.getLogger("idp.probe").info("probe line")
                val registry = context.getBean(MeterRegistry::class.java)
                registry.counter("idp.login.failures", "factor", "password").increment()
                registry.counter("probe.other").increment()
                context
                    .getBean(OpenTelemetry::class.java)
                    .getTracer("probe")
                    .spanBuilder("probe")
                    .startSpan()
                    .end()

                eventually(30.seconds) {
                    val security = aws.events(SECURITY_LOG_GROUP)
                    security.map { it.second }.filter { "event=throttle.rejected" in it }.shouldNotBeEmpty()
                    security.map { it.first } shouldContain "app"
                    aws.events(LOG_GROUP).map { it.second }.filter { "probe line" in it } shouldNotBe emptyList<String>()

                    val metrics = aws.events(METRICS_LOG_GROUP).map { it.second }
                    metrics.filter { "idp.login.failures" in it && "\"Namespace\":\"idp-test\"" in it }.shouldNotBeEmpty()

                    val traces = aws.calls.filter { it.path == "/v1/traces" }
                    traces.shouldNotBeEmpty()
                    traces.first().authorization.orEmpty() shouldStartWith "AWS4-HMAC-SHA256"
                    traces.first().authorization.orEmpty() shouldContain "/eu-central-1/xray/aws4_request"
                }

                aws.events(LOG_GROUP).map { it.second }.filter { "event=throttle.rejected" in it } shouldBe emptyList()
                aws.events(METRICS_LOG_GROUP).map { it.second }.none { "probe.other" in it } shouldBe true
                aws.calls.mapNotNull { it.target }.toSet() shouldNotContain "Logs_20140328.CreateLogGroup"
            }
        }

        "the exporters refuse a collector that another CA certified" {
            app(otherCa.pem).use { context ->
                context.exportsSucceed() shouldBe listOf(false, false)
            }
        }

        "the exporters refuse a collector whose certificate names another host" {
            app(internalCa.pem, host = "127.0.0.1").use { context ->
                context.exportsSucceed() shouldBe listOf(false, false)
            }
        }

        "the collector takes no plain HTTP" {
            val response =
                HttpClient.newHttpClient().send(
                    HttpRequest
                        .newBuilder(URI("http://localhost:${collector.getMappedPort(RECEIVER)}/v1/logs"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{}"))
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )

            response.statusCode() shouldBe 400
        }
    })
