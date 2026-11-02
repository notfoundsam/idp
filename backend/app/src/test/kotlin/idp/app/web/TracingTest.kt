package idp.app.web

import idp.app.login.ACME
import idp.app.login.Browser
import idp.app.login.EMAIL
import idp.app.login.GLOBEX
import idp.app.login.LoginEnvironment
import idp.app.login.PASSWORD
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeSingleton
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.SpanData
import org.springframework.context.annotation.Bean
import java.util.concurrent.TimeUnit

private val TENANT_ID: AttributeKey<String> = AttributeKey.stringKey(TenantFilter.TENANT_ID)

// Base64 of "<samlp:AuthnRequest", the way a SAML message travels in a query or a form.
private const val SAML_MESSAGE = "PHNhbWxwOkF1dGhuUmVxdWVzdA"

// Without @Configuration, so that the component scan of other tests does not pick it up.
private class SpanCapture {
    @Bean
    fun spans(): InMemorySpanExporter = InMemorySpanExporter.create()
}

private fun SpanData.texts(): List<String> {
    val events = events.flatMap { it.attributes.asMap().values }
    return listOf(name) + (attributes.asMap().values + events).map(Any::toString)
}

class TracingTest :
    FunSpec({
        val environment = LoginEnvironment()
        val application =
            environment.start("--management.tracing.sampling.probability=1.0", sources = arrayOf(SpanCapture::class.java))
        with(environment) { application.user() }
        val spans = application.getBean(InMemorySpanExporter::class.java)

        afterSpec { application.close() }

        beforeEach { spans.reset() }

        fun requests(): List<SpanData> {
            application.getBean(SdkTracerProvider::class.java).forceFlush().join(10, TimeUnit.SECONDS)
            return spans.finishedSpanItems.filter { it.kind == SpanKind.SERVER }
        }

        test("the span of a request carries the tenant it was served for") {
            Browser(application, ACME).post("/api/flows").status shouldBe 201
            Browser(application, GLOBEX).post("/api/flows").status shouldBe 201

            requests().map { it.attributes.get(TENANT_ID) } shouldContainExactlyInAnyOrder
                listOf(environment.acme, environment.globex).map { it.context.tenantId.toString() }
        }

        test("a host without an active tenant leaves the span without one") {
            Browser(application, "nobody.idp.test").post("/api/flows").status shouldBe 404

            val request = requests().shouldBeSingleton().single()
            request.attributes.get(TENANT_ID).shouldBeNull()
        }

        test("no email, password or SAML message reaches a span") {
            val browser = Browser(application, ACME).loggedIn()
            browser.get("/saml/sso?SAMLRequest=$SAML_MESSAGE&RelayState=$SAML_MESSAGE")
            browser.post("/saml/acs?SAMLRequest=$SAML_MESSAGE", "SAMLResponse=$SAML_MESSAGE")

            val texts = requests().flatMap { it.texts() }
            texts.filter { it.contains("/saml/") }.shouldNotBeEmpty()
            texts.forEach { text ->
                text shouldNotContain EMAIL
                text shouldNotContain PASSWORD
                text shouldNotContain SAML_MESSAGE
            }
        }
    })
