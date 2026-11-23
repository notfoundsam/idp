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
import idp.sharedkernel.testing.ConsoleCapture
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
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

private const val ATTACKER_EMAIL = "attacker@platform.example"
private const val SIGNED_ID = "_signed"
private const val FORGED_ID = "_forged"
private val SIGNATURE = Regex("<ds:Signature .*</ds:Signature>", RegexOption.DOT_MATCHES_ALL)

/** A Response the fake IdP builds for the request of the browser, then bent in one way. [reason] is what the refusal must name. */
private class Refusal(
    val name: String,
    val reason: String? = null,
    val response: (FakeIdp, String) -> String,
) {
    override fun toString() = name
}

private fun bent(change: (Login) -> Login) = { idp: FakeIdp, id: String -> idp.response(change(Login(inResponseTo = id))) }

/**
 * Signature wrapping: the signed assertion stays in the Response, and a forged one naming another operator carries a copy of its
 * signature where a careless verifier would read it.
 */
private fun wrapped(arrange: FakeIdp.(login: Login, signed: String, forged: (id: String, advice: String) -> String) -> String) =
    { idp: FakeIdp, id: String ->
        val login = Login(inResponseTo = id, assertionId = SIGNED_ID)
        val signed = idp.signedAssertion(login)
        val signature = SIGNATURE.find(signed)!!.value
        val forged = { forgedId: String, advice: String ->
            idp
                .assertion(login.copy(nameId = ATTACKER_EMAIL, assertionId = forgedId), advice)
                .replaceFirst("</saml:Issuer>", "</saml:Issuer>$signature")
        }
        idp.arrange(login, signed, forged)
    }

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

            test("reads the whole NameID, so a comment inserted after signing cannot cut it to another operator") {
                val operator = browser()
                val request = operator.startLogin()
                val longer = "$OPERATOR_EMAIL.attacker.test"
                val split =
                    idp
                        .response(
                            Login(inResponseTo = request.id, nameId = longer),
                        ).tampered(longer, "$OPERATOR_EMAIL<!---->.attacker.test")

                val reply = operator.finishLogin(split, request.relayState)

                if (reply.status == 303) {
                    operator.submit("split").shouldNotBeNull()
                    environment.audit().last()["actor"] shouldBe longer
                } else {
                    reply.status shouldBe 401
                    operator.signedIn() shouldBe false
                }
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
                Refusal("without a destination") { fake, id -> fake.response(Login(inResponseTo = id, destination = null)) },
                Refusal("with an empty destination") { fake, id -> fake.response(Login(inResponseTo = id, destination = "")) },
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
                Refusal(
                    "wrapped: a forged assertion with the signature, the signed one in its Advice",
                    "Invalid signature for object [$FORGED_ID]",
                    wrapped { login, signed, forged -> response(login, forged(FORGED_ID, signed)) },
                ),
                Refusal(
                    "wrapped: a forged assertion with the ID and the signature of the signed one, which is in its Advice",
                    "Invalid signature for object [$SIGNED_ID]",
                    wrapped { login, signed, forged -> response(login, forged(SIGNED_ID, signed)) },
                ),
                Refusal(
                    "wrapped: the signed assertion in the Extensions, a forged one with its signature in its place",
                    "Invalid signature for object [$FORGED_ID]",
                    wrapped { login, signed, forged -> response(login, forged(FORGED_ID, ""), extensions = signed) },
                ),
                Refusal(
                    "with two assertions, both signed by the IdP",
                    "The Response must carry exactly one plain assertion",
                ) { fake, id ->
                    val login = Login(inResponseTo = id)
                    val forged = fake.signedAssertion(login.copy(nameId = ATTACKER_EMAIL, assertionId = "_second"))
                    fake.response(login, forged + fake.signedAssertion(login))
                },
                Refusal("nested deeper than the parser allows", "Failed to deserialize") { fake, id ->
                    val login = Login(inResponseTo = id)
                    val nested = "<a xmlns=\"urn:x\">" + "<a>".repeat(99) + "</a>".repeat(100)
                    fake.response(login, fake.signedAssertion(login), extensions = nested)
                },
                Refusal("with more attributes than the parser allows", "Failed to deserialize") { fake, id ->
                    val login = Login(inResponseTo = id)
                    val attributed = "<a xmlns=\"urn:x\" " + (1..201).joinToString(" ") { "a$it=\"\"" } + "/>"
                    fake.response(login, fake.signedAssertion(login), extensions = attributed)
                },
                Refusal(
                    "signed by a key not in the metadata, with its certificate in KeyInfo",
                    "Invalid signature for object",
                ) { fake, id ->
                    fake.response(Login(inResponseTo = id, keyInfo = true), key = rsaKeyPair(2048))
                },
                Refusal(
                    "with an empty NameID",
                    "The assertion names no operator by email",
                ) { fake, id -> fake.response(Login(inResponseTo = id, nameId = "")) },
                Refusal(
                    "with an empty Issuer",
                    "required elements must have a value",
                ) { fake, id -> fake.response(Login(inResponseTo = id, issuer = "")) },
                Refusal("valid only from ten minutes on", "is not yet valid") { fake, id ->
                    fake.response(Login(inResponseTo = id, notBefore = Instant.now().plus(Duration.ofMinutes(10))))
                },
                Refusal(
                    "without an AudienceRestriction",
                    "The assertion names no audience",
                ) { fake, id -> fake.response(Login(inResponseTo = id, audience = null)) },
            ).forEach { refusal ->
                test(refusal.name) {
                    val browser = browser()
                    val request = browser.startLogin()

                    val lines =
                        ConsoleCapture().use { console ->
                            browser.finishLogin(refusal.response(idp, request.id), request.relayState).status shouldBe 401
                            console.lines()
                        }
                    refusal.reason?.let { reason -> lines.single { "event=ops.login_refused" in it } shouldContain reason }

                    browser.signedIn() shouldBe false
                    environment.audit().map { it["action"] to it["actor"] }.shouldContainExactly(
                        "operator.login_refused" to "anonymous",
                        "access.denied" to "anonymous",
                    )
                }
            }

            test("over the size of a form answers 413 as the client's error, and opens no session") {
                val browser = browser()
                val request = browser.startLogin()
                val login = Login(inResponseTo = request.id)
                val large = idp.response(login, idp.signedAssertion(login), extensions = "<a xmlns=\"urn:x\">${" ".repeat(70_000)}</a>")

                val lines =
                    ConsoleCapture().use { console ->
                        browser.finishLogin(large, request.relayState).status shouldBe 413
                        console.lines()
                    }

                browser.signedIn() shouldBe false
                lines.filter { "event=failure" in it }.shouldBeEmpty()
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
