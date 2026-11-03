package idp.ops.security

import idp.federation.rsaKeyPair
import idp.ops.BEARER
import idp.ops.CSRF_COOKIE
import idp.ops.FakeIdp
import idp.ops.HOLDER_OF_KEY
import idp.ops.IDP_ENTITY_ID
import idp.ops.Login
import idp.ops.MFA
import idp.ops.OPERATOR_EMAIL
import idp.ops.OPS_ACS
import idp.ops.OPS_ENTITY_ID
import idp.ops.OpsApplication
import idp.ops.OpsClient
import idp.ops.OpsEnvironment
import idp.ops.SESSION_COOKIE
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.opensaml.xmlsec.signature.support.SignatureConstants
import org.springframework.boot.runApplication
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

private const val RSA_SHA256 = "http://www.w3.org/2001/04/xmldsig-more#rsa-sha256"
private const val RSA_SHA1 = "http://www.w3.org/2000/09/xmldsig#rsa-sha1"

/** A Response the fake IdP builds for the request of the browser, then bent in one way. */
private class Refusal(
    val name: String,
    val response: (FakeIdp, String) -> String,
) {
    override fun toString() = name
}

private fun bent(change: (Login) -> Login) = { idp: FakeIdp, id: String -> idp.response(change(Login(inResponseTo = id))) }

private fun String.tampered(
    from: String,
    to: String,
): String = Base64.getEncoder().encodeToString(String(Base64.getDecoder().decode(this)).replace(from, to).toByteArray())

class OpsSamlLoginTest :
    FunSpec({
        val environment = OpsEnvironment()
        val idp = environment.idp
        val application = runApplication<OpsApplication>(*environment.arguments)
        val port = application.environment.getProperty("local.server.port")

        afterSpec { application.close() }

        beforeTest { environment.sql.update("DELETE FROM platform.operator_audit") }

        fun browser() = OpsClient(port)

        fun OpsClient.signedIn(): Boolean = get("/api/tenants/${UUID.randomUUID()}").status == 404

        context("without a session") {
            test("the API is closed and every refusal is recorded") {
                val anonymous = browser()

                anonymous.get("/api/tenants/${UUID.randomUUID()}").status shouldBe 401
                anonymous.get("/api/applications/${UUID.randomUUID()}").status shouldBe 401

                environment.audit().map { it["actor"] } shouldBe listOf("anonymous", "anonymous")
            }
        }

        context("a login") {
            test("asks the service tenant for a passkey on behalf of ops") {
                val request = browser().startLogin()

                request.location.host shouldBe "ops-sso.idp.test"
                request.location.path shouldBe "/saml/sso"
                request.xml shouldContain """AssertionConsumerServiceURL="$OPS_ACS""""
                request.xml shouldContain "urn:oasis:names:tc:SAML:2.0:bindings:HTTP-POST"
                request.xml shouldContain ">$OPS_ENTITY_ID</saml2:Issuer>"
                request.xml shouldContain """Comparison="exact""""
                request.xml shouldContain ">urn:idp:ac:classes:passkey</saml2:AuthnContextClassRef>"
            }

            test("with a passkey opens a session for the operator under a new session id, and is recorded") {
                val operator = browser()
                val request = operator.startLogin()
                val before = operator.cookies[SESSION_COOKIE].shouldNotBeNull()

                val answer = operator.finishLogin(idp.response(Login(inResponseTo = request.id)), request.relayState)

                answer.status shouldBe 303
                answer.header("Location") shouldBe "/"
                operator.cookies[SESSION_COOKIE] shouldNotBe before
                operator.signedIn() shouldBe true
                environment.audit().map { it["action"] to it["actor"] }.shouldContainExactly("operator.logged_in" to OPERATOR_EMAIL)
            }

            test("acts as the operator the IdP named") {
                val operator = browser()
                operator.login(idp)

                operator.submit("named").shouldNotBeNull()

                environment.audit().last()["actor"] shouldBe OPERATOR_EMAIL
            }

            test("ends when the session the IdP stated ends, even while the operator is active") {
                val operator = browser()
                operator.login(idp) { Login(inResponseTo = it.id, sessionNotOnOrAfter = Instant.now().plusSeconds(2)) }
                operator.signedIn() shouldBe true

                eventually(10.seconds) { operator.signedIn() shouldBe false }
            }

            test("a change without the CSRF token is refused") {
                val operator = browser()
                operator.login(idp)
                operator.get("/api/tenants/${UUID.randomUUID()}")
                val token = operator.cookies.remove(CSRF_COOKIE)

                operator
                    .post("/api/applications", """{"companyName":"Acme","subdomain":"forged","contact":"a@acme.example"}""")
                    .status shouldBe 403
                token.shouldNotBeNull()
            }
        }

        context("the ACS") {
            test("answers only on the host of ops") {
                val operator = OpsClient(port, host = "ops-sso.idp.test")

                operator
                    .postForm("/login/saml2/sso/ops-sso", mapOf("SAMLResponse" to idp.response(Login(inResponseTo = "_x"))))
                    .status shouldBe 404
                OpsClient(port, host = "localhost").get("/saml2/authenticate/ops-sso").status shouldBe 404
            }
        }

        context("a Response is refused, and opens no session") {
            listOf(
                Refusal("signed with a key that is not in the metadata") { fake, id ->
                    fake.response(Login(inResponseTo = id), key = rsaKeyPair(2048))
                },
                Refusal("from another IdP with keys of its own") { _, id -> FakeIdp().response(Login(inResponseTo = id)) },
                Refusal("unsigned") { fake, id -> fake.response(Login(inResponseTo = id, signAssertion = false)) },
                Refusal("with only the Response signed") { fake, id ->
                    fake.response(Login(inResponseTo = id, signAssertion = false, signResponse = true))
                },
                Refusal(
                    "changed after signing",
                ) { fake, id -> fake.response(Login(inResponseTo = id)).tampered(OPERATOR_EMAIL, "other@x.example") },
                Refusal("naming another issuer") { fake, id ->
                    fake.response(Login(inResponseTo = id, issuer = "https://acme.idp.test/saml/metadata"))
                },
                Refusal(
                    "for another audience",
                ) { fake, id -> fake.response(Login(inResponseTo = id, audience = "https://sp.example/saml")) },
                Refusal("for another destination") { fake, id ->
                    fake.response(Login(inResponseTo = id, destination = "https://sp.example/acs"))
                },
                Refusal(
                    "for another recipient",
                ) { fake, id -> fake.response(Login(inResponseTo = id, recipient = "https://sp.example/acs")) },
                Refusal("expired") { fake, id ->
                    fake.response(Login(inResponseTo = id, issuedAt = Instant.now().minus(Duration.ofMinutes(20))))
                },
                Refusal("with a holder-of-key confirmation") { fake, id ->
                    fake.response(Login(inResponseTo = id, confirmationMethod = HOLDER_OF_KEY))
                },
                Refusal("unsolicited") { fake, _ -> fake.response(Login(inResponseTo = null)) },
                Refusal("answering another request") { fake, _ -> fake.response(Login(inResponseTo = "_another")) },
                Refusal("after a login without a passkey") { fake, id -> fake.response(Login(inResponseTo = id, contextClass = MFA)) },
                Refusal("without the end of the IdP session") { fake, id ->
                    fake.response(Login(inResponseTo = id, sessionNotOnOrAfter = null))
                },
                Refusal("after the IdP session ended") { fake, id ->
                    fake.response(Login(inResponseTo = id, sessionNotOnOrAfter = Instant.now().minusSeconds(1)))
                },
                Refusal("naming the operator by a persistent NameID") { fake, id ->
                    fake.response(Login(inResponseTo = id, nameIdFormat = "urn:oasis:names:tc:SAML:2.0:nameid-format:persistent"))
                },
                Refusal("signed with RSA-SHA1") { fake, id -> fake.response(Login(inResponseTo = id, algorithm = RSA_SHA1)) },
                Refusal("signed with PKCS#1 v1.5") { fake, id -> fake.response(Login(inResponseTo = id, algorithm = RSA_SHA256)) },
                Refusal("digested with SHA-1") { fake, id ->
                    fake.response(Login(inResponseTo = id, digest = SignatureConstants.ALGO_ID_DIGEST_SHA1))
                },
                Refusal("with a DOCTYPE") { fake, id -> fake.response(Login(inResponseTo = id, doctype = true)) },
            ).forEach { refusal ->
                test(refusal.name) {
                    val browser = browser()
                    val request = browser.startLogin()

                    browser.finishLogin(refusal.response(idp, request.id), request.relayState).status shouldBe 401

                    browser.signedIn() shouldBe false
                    environment.audit().map { it["action"] to it["actor"] }.shouldContainExactly(
                        "operator.login_refused" to "anonymous",
                        "access.denied" to "anonymous",
                    )
                }
            }

            test("the same Response a second time") {
                val operator = browser()
                val request = operator.startLogin()
                val response = idp.response(Login(inResponseTo = request.id))
                operator.finishLogin(response, request.relayState).status shouldBe 303
                val other = browser()
                other.startLogin()

                operator.finishLogin(response, request.relayState).status shouldBe 401
                other.finishLogin(response, request.relayState).status shouldBe 401
                other.signedIn() shouldBe false
            }

            test("the default algorithm, RSA-PSS, is accepted, so the refusals above are about what they name") {
                browser().login(idp) { Login(inResponseTo = it.id, confirmationMethod = BEARER) }.status shouldBe 303
            }
        }

        context("the metadata of the IdP") {
            test("of another tenant stops ops from starting") {
                val other = OpsEnvironment(FakeIdp(entityId = "https://acme.idp.test/saml/metadata"))

                val failure = shouldThrow<Exception> { runApplication<OpsApplication>(*other.arguments) }

                generateSequence(failure as Throwable) { it.cause }.last().message.orEmpty() shouldContain IDP_ENTITY_ID
            }

            test("missing stops ops from starting") {
                val other = OpsEnvironment()

                val arguments = other.arguments.filterNot { it.startsWith("--IDP_METADATA_FILE=") }.toTypedArray()

                val failure = shouldThrow<Exception> { runApplication<OpsApplication>(*arguments) }

                generateSequence(failure as Throwable) { it.cause }.last().message.orEmpty() shouldContain "IDP_METADATA_FILE"
            }
        }
    })
