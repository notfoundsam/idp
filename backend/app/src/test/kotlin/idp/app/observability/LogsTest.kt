package idp.app.observability

import idp.app.login.Browser
import idp.app.login.GLOBEX
import idp.app.login.LoginEnvironment
import idp.sharedkernel.observability.LogFields
import idp.sharedkernel.testing.ConsoleCapture
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldMatch
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.logs.Severity
import io.opentelemetry.sdk.logs.SdkLoggerProvider
import io.opentelemetry.sdk.logs.data.LogRecordData
import io.opentelemetry.sdk.testing.exporter.InMemoryLogRecordExporter
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.concurrent.TimeUnit

private val UUID_TEXT = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
private val TENANT_ID: AttributeKey<String> = AttributeKey.stringKey(LogFields.TENANT_ID)
private val CORRELATION_ID: AttributeKey<String> = AttributeKey.stringKey(LogFields.CORRELATION_ID)
private val json = JsonMapper()

// Without @Configuration, so that the component scan of other tests does not pick it up.
private class LogRecordCapture {
    @Bean
    fun logRecords(): InMemoryLogRecordExporter = InMemoryLogRecordExporter.create()
}

/** ECS nests the dotted names, so `service.name` is a path. */
private fun JsonNode.text(path: String): String? =
    path
        .split('.')
        .fold(this as JsonNode?) { node, name -> node?.get(name) }
        ?.asString()

class LogsTest :
    FunSpec({
        val console = ConsoleCapture()
        val environment = LoginEnvironment()
        val application =
            environment.start("--management.tracing.sampling.probability=1.0", sources = arrayOf(LogRecordCapture::class.java))
        val records = application.getBean(InMemoryLogRecordExporter::class.java)
        val globex =
            environment.globex.context.tenantId
                .toString()

        afterSpec {
            application.close()
            console.close()
        }

        fun exported(): List<LogRecordData> {
            application.getBean(SdkLoggerProvider::class.java).forceFlush().join(10, TimeUnit.SECONDS)
            return records.finishedLogRecordItems
        }

        // A batch of the test before would otherwise arrive after the reset.
        beforeEach {
            exported()
            records.reset()
            console.clear()
        }

        fun lines(): List<JsonNode> = console.lines().map { line -> json.readTree(line) }

        fun busLines(): List<JsonNode> = lines().filter { it.text("message").orEmpty().contains("outcome=OK") }

        test("every line is one JSON object") {
            Browser(application, GLOBEX).startFlow()

            val lines = console.lines()
            lines.shouldNotBeEmpty()
            lines.forEach { line -> json.readTree(line).isObject shouldBe true }
        }

        test("a line of a request holds the time in UTC, the service, the tenant and the correlation of that request") {
            Browser(application, GLOBEX).startFlow()

            val line = busLines().single()
            val time = line.text("@timestamp").shouldNotBeNull()
            time shouldEndWith "Z"
            Instant.parse(time).shouldNotBeNull()
            line.text("service.name") shouldBe "app"
            line.text(LogFields.TENANT_ID) shouldBe globex
            line.text(LogFields.CORRELATION_ID).shouldNotBeNull() shouldMatch UUID_TEXT
            line.text("log.level") shouldBe "INFO"
        }

        test("two requests have two correlations") {
            Browser(application, GLOBEX).startFlow()
            Browser(application, GLOBEX).startFlow()

            val correlations = busLines().map { it.text(LogFields.CORRELATION_ID) }
            correlations.size shouldBe 2
            correlations[0] shouldNotBe correlations[1]
        }

        test("a line break in a message is escaped and stays inside its line") {
            LoggerFactory.getLogger("idp.test").warn("first\nsecond\r\nthird\u0000")

            val raw = console.lines().single { it.contains("first") }
            raw shouldContain """first\nsecond\r\nthird\u0000"""
            json.readTree(raw).text("message") shouldBe "first\nsecond\r\nthird\u0000"
        }

        test("the lines of a request leave over OTLP with the tenant, the correlation and the span") {
            Browser(application, GLOBEX).startFlow()

            val record =
                exported().single {
                    it.bodyValue
                        ?.asString()
                        .orEmpty()
                        .contains("outcome=OK")
                }
            record.severity shouldBe Severity.INFO
            record.attributes.get(TENANT_ID) shouldBe globex
            record.attributes.get(CORRELATION_ID).shouldNotBeNull() shouldMatch UUID_TEXT
            record.spanContext.isValid shouldBe true
            record.resource.attributes.get(AttributeKey.stringKey("service.name")) shouldBe "app"
        }

        test("the lines of the exporter itself stay on the console") {
            LoggerFactory.getLogger("io.opentelemetry.exporter.test").warn("export failed")

            console.text() shouldContain "export failed"
            exported().none { it.bodyValue?.asString() == "export failed" } shouldBe true
        }
    })
