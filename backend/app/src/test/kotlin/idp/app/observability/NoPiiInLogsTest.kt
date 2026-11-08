package idp.app.observability

import idp.app.login.ACME
import idp.app.login.Browser
import idp.app.login.EMAIL
import idp.app.login.LoginEnvironment
import idp.app.login.PASSWORD
import idp.app.login.WRONG_PASSWORD
import idp.app.login.codeBody
import idp.app.login.loginBody
import idp.app.login.passwordBody
import idp.app.login.text
import idp.federation.TestSp
import idp.federation.application.FederationService
import idp.federation.authnRequest
import idp.sharedkernel.audit.Actor
import idp.sharedkernel.testing.ConsoleCapture
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URLDecoder

private const val NOBODY = "nobody.here@acme.test"
private const val UNKNOWN_FIELD = "nickname"

// Base64 of "<samlp:AuthnRequest", the way a SAML message travels in a query or a form.
private const val FORGED_MESSAGE = "PHNhbWxwOkF1dGhuUmVxdWVzdA"
private val BOUNCE = Regex("""url=(/saml/sso/[0-9a-f-]{36})""")
private val FLOW = Regex("""^/login/\?flow=([0-9a-f-]{36})$""")
private val SAML_RESPONSE = Regex("""name="SAMLResponse" value="([^"]+)"""")

/** An exception text with an address, as a driver or a library may write one. */
@RestController
private class PersonalFailureEndpoint {
    @GetMapping("/api/probe/personal-failure")
    fun fail(): String = error("no account for $EMAIL with $PASSWORD")
}

class NoPiiInLogsTest :
    FunSpec({
        val console = ConsoleCapture()
        val environment = LoginEnvironment()
        val application = environment.start("--logging.level.idp=DEBUG")
        val sp = TestSp()
        val federation = application.getBean(FederationService::class.java)
        val member = with(environment) { application.user() }
        environment.acme.acting {
            federation.createIdentity(ACME)
            val spId = federation.registerServiceProvider("Wiki", sp.metadata().toByteArray(), Actor.System)
            federation.assign(spId, listOf(member), Actor.System)
        }

        afterSpec {
            application.close()
            console.close()
        }

        beforeEach { console.clear() }

        fun shouldHoldNothingPersonal(vararg secrets: String) {
            val text = console.text()
            listOf(EMAIL, EMAIL.substringBefore('@'), NOBODY, PASSWORD, WRONG_PASSWORD, *secrets).forEach { text shouldNotContain it }
        }

        test("a login, its refusals and its failures leave no address, password or code in the lines") {
            val browser = Browser(application)
            browser.post("/api/flows/${browser.startFlow()}/login", loginBody(NOBODY)).status shouldBe 200
            val flow = browser.identified()
            browser.post("/api/flows/$flow/password", passwordBody(WRONG_PASSWORD)).status shouldBe 422
            browser.post("/api/flows/$flow/totp", codeBody("123456"))
            browser.post("/api/flows/$flow/login", """{"login":"$EMAIL","$UNKNOWN_FIELD":"$PASSWORD"}""").status shouldBe 400
            browser.password(flow).status shouldBe 200
            browser.complete(flow).status shouldBe 200
            browser.get("/api/probe/personal-failure").status shouldBe 500

            console.text() shouldContain "LoginRejected"
            console.text() shouldContain "event=failure"
            shouldHoldNothingPersonal()
        }

        test("a SAML login and a forged message leave no SAML message in the lines") {
            val browser = Browser(application)
            val query = sp.redirectQuery(authnRequest(), "state")
            val request = URLDecoder.decode(query.substringAfter("SAMLRequest=").substringBefore('&'), Charsets.UTF_8)
            val bounce = BOUNCE.find(browser.get("/saml/sso?$query").body).shouldNotBeNull().groupValues[1]
            val flow = FLOW.find(browser.get(bounce).header("Location").shouldNotBeNull()).shouldNotBeNull().groupValues[1]
            browser.post("/api/flows/$flow/login", loginBody(EMAIL))
            browser.password(flow).status shouldBe 200
            val reply = browser.get(browser.complete(flow).json().text("returnUrl"))
            val response = SAML_RESPONSE.find(reply.body).shouldNotBeNull().groupValues[1]
            browser.get("/saml/sso?SAMLRequest=$FORGED_MESSAGE&RelayState=$FORGED_MESSAGE").status shouldBe 400
            browser.form("/saml/sso", "SAMLRequest=$FORGED_MESSAGE&RelayState=$FORGED_MESSAGE").status shouldBe 400

            console.text() shouldContain "event=saml.request_rejected"
            shouldHoldNothingPersonal(request, response, response.take(40), FORGED_MESSAGE, "<samlp:", "<saml2p:", "<saml2:")
        }
    })
