package idp.app.saml

import idp.app.login.ACME
import idp.app.login.Browser
import idp.app.login.EMAIL
import idp.app.login.LoginEnvironment
import idp.app.login.PASSWORD
import idp.app.login.Reply
import idp.app.login.loginBody
import idp.app.login.passwordBody
import idp.app.login.text
import idp.federation.PPT
import idp.federation.REFEDS_MFA
import idp.federation.SP_ACS
import idp.federation.SP_ENTITY_ID
import idp.federation.TestSp
import idp.federation.application.FederationService
import idp.federation.authnRequest
import idp.federation.requestedContext
import idp.sharedkernel.audit.Actor
import idp.sharedkernel.audit.AuditTarget
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.sql.Timestamp
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.UUID

private const val OUTSIDER = "grace.hopper@acme.test"
private const val SUCCESS = "urn:oasis:names:tc:SAML:2.0:status:Success"
private const val REQUEST_DENIED = "urn:oasis:names:tc:SAML:2.0:status:RequestDenied"
private val BOUNCE = Regex("""url=(/saml/sso/[0-9a-f-]{36})""")
private val FLOW = Regex("""^/login/\?flow=([0-9a-f-]{36})$""")

private fun Reply.samlResponse(): String {
    val value = Regex("""name="SAMLResponse" value="([^"]+)"""").find(body).shouldNotBeNull().groupValues[1]
    return String(Base64.getDecoder().decode(value))
}

private fun String.attribute(name: String): String = Regex("""$name="([^"]+)"""").find(this).shouldNotBeNull().groupValues[1]

private fun String.element(name: String): String =
    Regex("""<saml2?:$name[^>]*>([^<]+)</saml2?:$name>""").find(this).shouldNotBeNull().groupValues[1]

/** Where a browser stands after the SSO endpoint sent it on: the request ID and the flow. */
private class Started(
    val request: String,
    val flow: String,
) {
    val resume = "/saml/resume/$request"
}

class ResumeApiTest :
    FunSpec({
        val environment = LoginEnvironment()
        val application = environment.start()
        val sp = TestSp()
        val federation = application.getBean(FederationService::class.java)
        val member = with(environment) { application.user() }
        val outsider = with(environment) { application.user(OUTSIDER) }
        val spId =
            environment.acme.acting {
                federation.createIdentity(ACME)
                federation.registerServiceProvider("Wiki", sp.metadata().toByteArray(), Actor.System).also { id ->
                    federation.assign(id, listOf(member), Actor.System)
                }
            }

        afterSpec { application.close() }

        fun Browser.started(xml: String = authnRequest()): Started {
            val bounce = BOUNCE.find(get("/saml/sso?${sp.redirectQuery(xml, "state")}").body).shouldNotBeNull().groupValues[1]
            val proceeded = get(bounce)
            val flow = FLOW.find(proceeded.header("Location").shouldNotBeNull()).shouldNotBeNull().groupValues[1]
            return Started(bounce.substringAfterLast('/'), flow)
        }

        /** Walks the flow as the login page would and follows the URL it returns to. */
        fun Browser.signedIn(
            xml: String = authnRequest(),
            email: String = EMAIL,
        ): Reply {
            val started = started(xml)
            when (get("/api/flows/${started.flow}").json().text("step")) {
                "IDENTIFY" -> {
                    post("/api/flows/${started.flow}/login", loginBody(email))
                    post("/api/flows/${started.flow}/password", passwordBody(PASSWORD))
                }
                "PASSWORD" -> post("/api/flows/${started.flow}/password", passwordBody(PASSWORD))
            }
            val returnUrl = complete(started.flow).json().text("returnUrl")
            returnUrl shouldBe started.resume
            return get(returnUrl)
        }

        fun audit(action: String) = with(environment) { application.audit() }.filter { it.action == action }

        context("an assigned user") {
            test("gets a signed Response at the checked ACS, under a policy that lets the form go there only") {
                val id = "_resume-${UUID.randomUUID()}"

                val reply = Browser(application).signedIn(authnRequest(id = id))

                reply.status shouldBe 200
                reply.header("Content-Type") shouldBe "text/html;charset=UTF-8"
                reply.header("Cache-Control") shouldBe "no-store"
                reply.header("Content-Security-Policy").shouldNotBeNull() shouldContain "form-action $SP_ACS;"
                reply.body shouldContain """action="$SP_ACS""""
                reply.body shouldContain """name="RelayState" value="state""""
                val response = reply.samlResponse()
                response shouldContain SUCCESS
                response.attribute("InResponseTo") shouldBe id
                response.attribute("Recipient") shouldBe SP_ACS
                response.element("Audience") shouldBe SP_ENTITY_ID
                response shouldContain "urn:oasis:names:tc:SAML:2.0:nameid-format:persistent"
                response shouldNotContain EMAIL
            }

            test("leaves a login in the journal") {
                val before = audit("sp.login").size

                Browser(application).signedIn()

                val entry = audit("sp.login").first()
                audit("sp.login").size shouldBe before + 1
                entry.actor shouldBe Actor.User(member)
                entry.target shouldBe AuditTarget.user(member)
                entry.details shouldBe mapOf("sp_id" to spId.toString())
            }

            test("gets the class and the time of the login it made") {
                val browser = Browser(application)

                val response = browser.signedIn().samlResponse()

                response.element("AuthnContextClassRef") shouldBe PPT
                val loggedInAt =
                    environment.sql
                        .queryForObject(
                            "SELECT max(authenticated_at) FROM ${environment.acme.table("sso_sessions")} WHERE user_id = ?",
                            Timestamp::class.java,
                            member,
                        )!!
                        .toInstant()
                Instant.parse(response.attribute("AuthnInstant")).truncatedTo(ChronoUnit.SECONDS) shouldBe
                    loggedInAt.truncatedTo(ChronoUnit.SECONDS)
                response.attribute("SessionNotOnOrAfter").let(Instant::parse).isAfter(loggedInAt) shouldBe true
            }
        }

        context("the way back") {
            test("spends the request once") {
                val browser = Browser(application)
                val started = browser.started()
                browser.post("/api/flows/${started.flow}/login", loginBody(EMAIL))
                browser.post("/api/flows/${started.flow}/password", passwordBody(PASSWORD))
                browser.complete(started.flow)
                browser.get(started.resume).status shouldBe 200

                val again = browser.get(started.resume)

                again.status shouldBe 400
                again.body shouldNotContain "SAMLResponse"
            }

            test("in another browser gets nothing and leaves the request to its own browser") {
                val browser = Browser(application)
                val started = browser.started()
                browser.post("/api/flows/${started.flow}/login", loginBody(EMAIL))
                browser.post("/api/flows/${started.flow}/password", passwordBody(PASSWORD))
                browser.complete(started.flow)

                Browser(application).loggedIn().get(started.resume).status shouldBe 400

                browser.get(started.resume).status shouldBe 200
            }

            test("without a session tells an anonymous visitor nothing about assignments, and keeps the request") {
                val assigned = Browser(application)
                val forMember = assigned.started()

                val anonymous = assigned.get(forMember.resume)

                anonymous.status shouldBe 400
                anonymous.body shouldNotContain "access"
                assigned.post("/api/flows/${forMember.flow}/login", loginBody(EMAIL))
                assigned.post("/api/flows/${forMember.flow}/password", passwordBody(PASSWORD))
                assigned.complete(forMember.flow)
                assigned.get(forMember.resume).status shouldBe 200
            }

            test("does not let an old session past ForceAuthn") {
                val browser = Browser(application).loggedIn()
                val started = browser.started(authnRequest(forceAuthn = true))

                browser.get(started.resume).status shouldBe 400
            }

            test("does not let a session of one factor past a demand for more") {
                val browser = Browser(application).loggedIn()
                val started = browser.started(authnRequest(requestedContext = requestedContext(REFEDS_MFA)))

                browser.get(started.resume).status shouldBe 400
            }
        }

        context("a user who is not assigned") {
            test("gets the denial page after the login, and the journal records the refusal") {
                val reply = Browser(application).signedIn(email = OUTSIDER)

                reply.status shouldBe 403
                reply.body shouldContain "do not have access"
                reply.body shouldNotContain "SAMLResponse"
                val denial = audit("sp.login_denied").first()
                denial.actor shouldBe Actor.User(outsider)
                denial.details shouldBe mapOf("sp_id" to spId.toString())
            }

            test("with IsPassive gets RequestDenied at the checked ACS") {
                val browser = Browser(application).loggedIn(OUTSIDER)

                val reply = browser.signedIn(authnRequest(isPassive = true), OUTSIDER)

                reply.status shouldBe 200
                reply.samlResponse() shouldContain REQUEST_DENIED
                reply.samlResponse() shouldNotContain "Assertion"
            }
        }

        context("a live session") {
            test("does not get past the assignment, and a withdrawn one counts at the next login") {
                val browser = Browser(application).loggedIn()
                browser.signedIn().status shouldBe 200
                environment.acme.acting { federation.unassign(spId, listOf(member), Actor.System) }

                try {
                    browser.signedIn().status shouldBe 403
                } finally {
                    environment.acme.acting { federation.assign(spId, listOf(member), Actor.System) }
                }
            }
        }
    })
