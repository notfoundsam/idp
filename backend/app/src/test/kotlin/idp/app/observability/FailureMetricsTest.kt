package idp.app.observability

import idp.app.login.Browser
import idp.app.login.LoginEnvironment
import idp.app.login.WRONG_PASSWORD
import idp.sharedkernel.observability.LogFields
import idp.sharedkernel.testing.ConsoleCapture
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.core.env.Environment
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

private const val DETAIL = "the token of kms.internal is s3cr3t-detail"

/** HTTPS to a port that speaks plain HTTP, as a backend behind a wrong endpoint or a proxy that breaks TLS would answer. */
@RestController
private class BackendProbes(
    private val environment: Environment,
) {
    @GetMapping("/api/probe/unexpected-failure")
    fun unexpected(): String = error(DETAIL)

    @GetMapping("/api/probe/backend-tls")
    fun tls(): String {
        val plain = environment.getProperty("local.management.port")
        HttpClient
            .newHttpClient()
            .send(HttpRequest.newBuilder(URI("https://localhost:$plain/actuator/health")).build(), HttpResponse.BodyHandlers.discarding())
        return "reached"
    }
}

class FailureMetricsTest :
    FunSpec({
        val console = ConsoleCapture()
        val environment = LoginEnvironment()
        val application = environment.start()
        with(environment) { application.user() }
        val registry = application.getBean(MeterRegistry::class.java)
        val acme =
            environment.acme.context.tenantId
                .toString()

        afterSpec {
            application.close()
            console.close()
        }

        beforeEach { console.clear() }

        fun count(
            name: String,
            vararg tags: String,
        ): Double =
            registry
                .find(name)
                .tags(*tags)
                .counter()
                ?.count() ?: 0.0

        fun failureLine(): String = console.lines().single { it.contains("event=failure") }

        test("an unexpected failure is logged without its text, under its tenant, and counted") {
            val before = count("idp.failures", "source", "http", "tls", "false")

            Browser(application).get("/api/probe/unexpected-failure").status shouldBe 500

            count("idp.failures", "source", "http", "tls", "false") shouldBe before + 1
            val line = failureLine()
            line shouldContain "java.lang.IllegalStateException"
            line shouldNotContain DETAIL
            val fields = JsonMapper().readTree(line)
            fields.get("log").get("level").asString() shouldBe "ERROR"
            fields.get(LogFields.TENANT_ID).asString() shouldBe acme
            console.text() shouldNotContain DETAIL
        }

        test("a TLS failure to a backend is logged and counted as one") {
            val before = count("idp.failures", "source", "http", "tls", "true")
            val others = count("idp.failures", "source", "http", "tls", "false")

            Browser(application).get("/api/probe/backend-tls").status shouldBe 500

            count("idp.failures", "source", "http", "tls", "true") shouldBe before + 1
            count("idp.failures", "source", "http", "tls", "false") shouldBe others
            val line = failureLine()
            line shouldContain "tls=true"
            line shouldContain "javax.net.ssl.SSL"
        }

        test("a refused password counts as a failed login by its factor, and a login that passes counts nothing") {
            val before = count("idp.login.failures", "factor", "PASSWORD")
            val browser = Browser(application)
            val flow = browser.identified()

            browser.password(flow, WRONG_PASSWORD).status shouldBe 422
            count("idp.login.failures", "factor", "PASSWORD") shouldBe before + 1

            browser.password(flow).status shouldBe 200
            count("idp.login.failures", "factor", "PASSWORD") shouldBe before + 1
        }
    })
