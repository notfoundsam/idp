package idp.app.saml

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import idp.app.login.ACME
import idp.app.login.Browser
import idp.app.login.GLOBEX
import idp.app.login.LoginEnvironment
import idp.app.login.Reply
import idp.app.login.text
import idp.authentication.application.FlowCookie
import idp.federation.PASSKEY_CLASS
import idp.federation.REFEDS_MFA
import idp.federation.SP_ACS
import idp.federation.TestSp
import idp.federation.application.FederationService
import idp.federation.authnRequest
import idp.federation.formBody
import idp.federation.postForm
import idp.federation.requestedContext
import idp.sharedkernel.audit.Actor
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.Base64

private const val NO_PASSIVE = "urn:oasis:names:tc:SAML:2.0:status:NoPassive"
private const val REQUEST_DENIED = "urn:oasis:names:tc:SAML:2.0:status:RequestDenied"
private val BOUNCE = Regex("""url=(/saml/sso/[0-9a-f-]{36})""")
private val FLOW = Regex("""^/login/\?flow=([0-9a-f-]{36})$""")

private fun Reply.bounce(): String = BOUNCE.find(body).shouldNotBeNull().groupValues[1]

private fun Reply.flow(): String = FLOW.find(header("Location").shouldNotBeNull()).shouldNotBeNull().groupValues[1]

/** The decoded Response of an auto-posted form. */
private fun Reply.samlResponse(): String {
    val value = Regex("""name="SAMLResponse" value="([^"]+)"""").find(body).shouldNotBeNull().groupValues[1]
    return String(Base64.getDecoder().decode(value))
}

class SsoApiTest :
    FunSpec({
        val environment = LoginEnvironment()
        val application = environment.start()
        val sp = TestSp()
        val federation = application.getBean(FederationService::class.java)
        environment.acme.acting {
            federation.createIdentity(ACME)
            federation.registerServiceProvider("Wiki", sp.metadata().toByteArray(), Actor.System)
        }
        with(environment) { application.user() }

        afterSpec { application.close() }

        fun Browser.redirect(
            xml: String = authnRequest(),
            relayState: String? = null,
        ) = get("/saml/sso?${sp.redirectQuery(xml, relayState)}")

        fun Browser.proceed(xml: String = authnRequest()): Reply = get(redirect(xml).bounce())

        fun requests(): Int =
            environment.sql.queryForObject("SELECT count(*) FROM ${environment.acme.table("saml_requests")}", Int::class.java)!!

        context("by POST") {
            test("the request is kept, and the browser goes on to this origin before any cookie is read") {
                val browser = Browser(application).loggedIn()
                val flows = environment.openFlows()
                val kept = requests()

                val reply = browser.form("/saml/sso", formBody(postForm(authnRequest(), "state")))

                reply.status shouldBe 200
                reply.header("Content-Type") shouldBe "text/html;charset=UTF-8"
                reply.header("Cache-Control") shouldBe "no-store"
                reply.cookies().shouldBeEmpty()
                reply.body shouldNotContain "<script"
                environment.openFlows() shouldBe flows
                requests() shouldBe kept + 1

                val proceeded = browser.get(reply.bounce())

                proceeded.status shouldBe 303
                proceeded.flow()
                proceeded.cookies().single { it.startsWith("${FlowCookie.NAME}=") }
                environment.openFlows() shouldBe flows + 1
            }

            test("with SAML parameters in the URL too is refused") {
                val reply = Browser(application).form("/saml/sso?RelayState=other", formBody(postForm(authnRequest(), "state")))

                reply.status shouldBe 400
            }

            test("over the size limit is refused before anyone reads it") {
                val padding = "x".repeat(64 * 1024)

                Browser(application).form("/saml/sso", formBody(postForm(authnRequest(), padding))).status shouldBe 413
            }
        }

        context("by Redirect") {
            test("the flow returns to an opaque URL of this request") {
                val browser = Browser(application)
                val bounce = browser.redirect().bounce()

                val flow = browser.get(bounce).flow()

                val returnUrl =
                    environment.sql.queryForObject(
                        "SELECT return_url FROM ${environment.acme.table("flows")} WHERE id = ?::uuid",
                        String::class.java,
                        flow,
                    )
                returnUrl shouldBe "/saml/resume/${bounce.substringAfterLast('/')}"
            }

            test("the flow keeps the secret the browser holds, so its other tabs go on") {
                val browser = Browser(application)
                browser.startFlow()
                val secret = browser.cookies.getValue(FlowCookie.NAME)

                browser.proceed()

                browser.cookies.getValue(FlowCookie.NAME) shouldBe secret
            }

            test("the way back works once") {
                val browser = Browser(application)
                val bounce = browser.redirect().bounce()
                browser.get(bounce).status shouldBe 303

                val again = browser.get(bounce)

                again.status shouldBe 400
                again.body shouldContain "could not be accepted"
            }

            test("with a repeated parameter is refused") {
                val query = sp.redirectQuery(authnRequest(), "state")

                Browser(application).get("/saml/sso?$query&${query.substringBefore('&')}").status shouldBe 400
            }
        }

        context("a live session") {
            test("completes the login with nothing more to prove") {
                val browser = Browser(application).loggedIn()

                val flow = browser.proceed().flow()

                browser.get("/api/flows/$flow").json().text("step") shouldBe "COMPLETE"
            }

            test("does not get past ForceAuthn") {
                val browser = Browser(application).loggedIn()

                val flow = browser.proceed(authnRequest(forceAuthn = true)).flow()

                browser.get("/api/flows/$flow").json().text("step") shouldBe "IDENTIFY"
            }

            test("of one factor does not get past a request for more") {
                listOf(REFEDS_MFA, PASSKEY_CLASS).forEach { context ->
                    val browser = Browser(application).loggedIn()

                    val flow = browser.proceed(authnRequest(requestedContext = requestedContext(context))).flow()

                    browser.get("/api/flows/$flow").json().text("step") shouldNotBe "COMPLETE"
                }
            }

            test("lets a passive request through") {
                val browser = Browser(application).loggedIn()

                browser.proceed(authnRequest(isPassive = true)).status shouldBe 303
            }
        }

        context("IsPassive") {
            test("without a session answers NoPassive at the checked ACS and drops the request") {
                val browser = Browser(application)
                val kept = requests()
                val id = "_passive-${System.nanoTime()}"

                val reply = browser.proceed(authnRequest(id = id, isPassive = true))

                reply.status shouldBe 200
                reply.body shouldContain """action="$SP_ACS""""
                reply.header("Content-Security-Policy").shouldNotBeNull() shouldContain "form-action $SP_ACS;"
                reply.headers()["Content-Security-Policy"].shouldNotBeNull() shouldHaveSize 1
                reply.samlResponse() shouldContain NO_PASSIVE
                reply.samlResponse() shouldContain """InResponseTo="$id""""
                requests() shouldBe kept
            }

            test("does not get past ForceAuthn with a session") {
                val browser = Browser(application).loggedIn()

                browser.proceed(authnRequest(isPassive = true, forceAuthn = true)).samlResponse() shouldContain NO_PASSIVE
            }
        }

        context("a refused request") {
            test("answers at the checked ACS once the SP and its ACS are known") {
                val reply = Browser(application).redirect(authnRequest(issueInstant = Instant.now() - Duration.ofHours(1)), "state")

                reply.status shouldBe 200
                reply.samlResponse() shouldContain REQUEST_DENIED
                reply.body shouldContain """name="RelayState" value="state""""
            }

            test("shows the error page when the ACS is not one of the SP") {
                val reply = Browser(application).redirect(authnRequest(acsUrl = "https://evil.example/acs"))

                reply.status shouldBe 400
                reply.body shouldNotContain "SAMLResponse"
                reply.body shouldNotContain "evil.example"
            }

            test("keeps a hostile RelayState a value of the form") {
                val reply =
                    Browser(application).redirect(
                        authnRequest(issueInstant = Instant.now() - Duration.ofHours(1)),
                        "\"><script>alert(1)</script>",
                    )

                reply.body shouldNotContain "<script>alert"
                reply.body shouldContain "&quot;&gt;&lt;script&gt;"
            }

            test("by replay of its ID is denied") {
                val xml = authnRequest()
                Browser(application).redirect(xml).bounce()

                Browser(application).redirect(xml).samlResponse() shouldContain REQUEST_DENIED
            }

            test("reaches the log of security events without its content") {
                val logger = LoggerFactory.getLogger("idp.security") as Logger
                val events = ListAppender<ILoggingEvent>().also { it.start() }
                logger.addAppender(events)
                try {
                    Browser(application).redirect(authnRequest(issuer = "https://unknown-sp.example/saml"), "relay-marker")
                } finally {
                    logger.detachAppender(events)
                }

                val event = events.list.single().formattedMessage
                event shouldContain "event=saml.request_rejected"
                event shouldContain "reason=UNKNOWN_SP"
                event shouldContain "tenant=${environment.acme.context.tenantId}"
                event shouldNotContain "unknown-sp"
                event shouldNotContain "relay-marker"
            }

            test("of an SP of another tenant is not known here") {
                val reply = Browser(application, GLOBEX).redirect()

                reply.status shouldBe 400
                reply.body shouldNotContain "SAMLResponse"
            }
        }

        test("the way back to an unknown request shows the error page and starts no flow") {
            val flows = environment.openFlows()

            Browser(application).get("/saml/sso/00000000-0000-4000-8000-000000000000").status shouldBe 400

            environment.openFlows() shouldBe flows
        }
    })
