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
import idp.federation.application.FederationService
import idp.federation.application.SamlAttribute
import idp.federation.application.SignatureAlgorithm
import idp.federation.application.SpSettings
import idp.identity.application.IdentityService
import idp.identity.application.ProfileChange
import idp.sharedkernel.audit.Actor
import idp.sharedkernel.testing.TestCa
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.Transferable
import org.testcontainers.utility.MountableFile
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException

private const val SP_HOST = "sp.idp.test"
private const val SSP = "/var/simplesamlphp"
private const val MAIL_OID = "urn:oid:0.9.2342.19200300.100.1.3"
private const val DISPLAY_NAME_OID = "urn:oid:2.16.840.1.113730.3.1.241"
private const val OUTSIDER = "grace.hopper@acme.test"
private val TIMEOUT: Duration = Duration.ofSeconds(30)
private val BOUNCE = Regex("""url=(/saml/sso/[0-9a-f-]{36})""")

/** SimpleSAMLphp with the configuration of the local environment, and the IdP metadata as `app` publishes it. */
private class SimpleSamlPhp(
    idpMetadata: String,
) : AutoCloseable {
    private val ca = TestCa("Test SimpleSAMLphp CA")
    private val certificate = ca.issue("localhost")
    private val config = Path.of(System.getProperty("idp.simplesamlphp.config"))
    private val container: GenericContainer<*> =
        GenericContainer(System.getProperty("idp.simplesamlphp.image"))
            .withExposedPorts(443)
            .withEnv("APACHE_CERT_NAME", CERT_NAME)
            .withCopyToContainer(Transferable.of(certificate.first, READABLE), "/etc/ssl/certs/$CERT_NAME.pem")
            .withCopyToContainer(Transferable.of(certificate.second, OWNER_ONLY), "/etc/ssl/private/$CERT_NAME.key")
            .withEnv("SSP_ADMIN_PASSWORD", "admin")
            .withEnv("SSP_SECRET_SALT", "interop-test")
            .withCopyToContainer(file("authsources.php"), "$SSP/config/authsources.php")
            .withCopyToContainer(file("config-override.php"), "$SSP/config/config-override.php")
            .withCopyToContainer(file("whoami.php"), "$SSP/public/whoami.php")
            .withCopyToContainer(Transferable.of(idpMetadata, READABLE), "$SSP/metadata/idp/acme.xml")
            .waitingFor(Wait.forHttps("/simplesaml/module.php/core/welcome").allowInsecure().forPort(443))
            .also { it.start() }

    private val http = HttpClient.newBuilder().sslContext(ca.trust()).build()
    private val base = "https://localhost:${container.getMappedPort(443)}"
    private val cookies = ConcurrentHashMap<String, String>()

    fun get(url: String): HttpResponse<String> = send(request(url).GET())

    fun trust(): SSLContext = ca.trust()

    /** The welcome page through a client of its own, which trusts only what [context] trusts. */
    fun welcome(context: SSLContext): Int =
        HttpClient
            .newBuilder()
            .sslContext(context)
            .build()
            .send(request("https://$SP_HOST/simplesaml/module.php/core/welcome").GET().build(), HttpResponse.BodyHandlers.discarding())
            .statusCode()

    /** A new visitor of the SP, so the SP has no session and sends the browser to the IdP again. */
    fun forget() = cookies.clear()

    fun post(
        url: String,
        fields: Map<String, String>,
    ): HttpResponse<String> {
        val body = fields.entries.joinToString("&") { (name, value) -> "$name=${URLEncoder.encode(value, Charsets.UTF_8)}" }
        return send(
            request(url).header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body)),
        )
    }

    /** A URL of the SP host goes to the container, with the host it expects in the header. */
    private fun request(url: String): HttpRequest.Builder {
        val uri = URI(url)
        check(uri.host == SP_HOST) { "Not a URL of the SP: $url" }
        val builder =
            HttpRequest
                .newBuilder(
                    URI("$base${uri.rawPath}${uri.rawQuery?.let { "?$it" }.orEmpty()}"),
                ).header("Host", SP_HOST)
                .timeout(TIMEOUT)
        if (cookies.isNotEmpty()) builder.header("Cookie", cookies.entries.joinToString("; ") { (name, value) -> "$name=$value" })
        return builder
    }

    private fun send(request: HttpRequest.Builder): HttpResponse<String> =
        http.send(request.build(), HttpResponse.BodyHandlers.ofString()).also { response ->
            response.headers().allValues("Set-Cookie").forEach { cookie ->
                val (name, value) = cookie.substringBefore(';').split('=', limit = 2)
                cookies[name] = value
            }
        }

    /** SimpleSAMLphp logs why it refused a Response, and its error page does not say. */
    fun log(): String =
        container.logs
            .lines()
            .takeLast(LOG_LINES)
            .joinToString("\n")

    override fun close() = container.close()

    private fun file(name: String) = MountableFile.forHostPath(config.resolve(name), READABLE)

    companion object {
        const val READABLE = 0b110_100_100
        const val OWNER_ONLY = 0b110_000_000
        const val CERT_NAME = "interop"
        const val LOG_LINES = 40
    }
}

private fun HttpResponse<String>.location(): String = headers().firstValue("Location").orElseThrow()

private fun Reply.formField(name: String): String =
    Regex("""name="$name" value="([^"]*)"""")
        .find(body)
        .shouldNotBeNull()
        .groupValues[1]
        .replace("&amp;", "&")
        .replace("&#39;", "'")

private fun Reply.formAction(): String = Regex("""action="([^"]+)"""").find(body).shouldNotBeNull().groupValues[1]

/**
 * The whole way through a real SP: SimpleSAMLphp sends the AuthnRequest, the login runs over the HTTP API as the login page runs it,
 * and SimpleSAMLphp checks the Response with the certificate from the published metadata.
 */
class SamlInteropTest :
    FunSpec({
        val environment = LoginEnvironment()
        val application = environment.start()
        val federation = application.getBean(FederationService::class.java)
        val member = with(environment) { application.user() }
        with(environment) { application.user(OUTSIDER) }
        environment.acme.acting {
            federation.createIdentity(ACME)
            application.getBean(IdentityService::class.java).updateProfile(member, ProfileChange("Ada Lovelace", null), Actor.System)
        }
        val metadata = Browser(application).get("/saml/metadata").body
        val sp = SimpleSamlPhp(metadata)
        val spId =
            environment.acme.acting {
                val spMetadata = sp.get("https://$SP_HOST/simplesaml/module.php/saml/sp/metadata/default-sp").body()
                federation.registerServiceProvider("SimpleSAMLphp", spMetadata.toByteArray(), Actor.System).also { id ->
                    federation.assign(id, listOf(member), Actor.System)
                }
            }

        afterSpec {
            sp.close()
            application.close()
        }

        /** Each step names the reply it got, so a step that fails under load says which and why instead of a bare NPE. */
        fun Reply.step(
            name: String,
            status: Int,
        ): Reply = also { withClue("$name answered $status with: $body") { it.status shouldBe status } }

        /** From the page of the SP to the form the IdP hands the browser, or the page that stops it. */
        fun login(email: String): Reply {
            sp.forget()
            val toIdp = sp.get("https://$SP_HOST/simplesaml/whoami.php").location()
            toIdp shouldStartWith "https://$ACME/saml/sso?"
            val browser = Browser(application)
            val sso = browser.get(toIdp.removePrefix("https://$ACME")).step("The AuthnRequest", 200)
            val bounce = withClue(sso.body) { BOUNCE.find(sso.body).shouldNotBeNull() }.groupValues[1]
            val toLogin = browser.get(bounce).step("The bounce", 303)
            val flow = withClue(toLogin.headers()) { toLogin.header("Location").shouldNotBeNull() }.substringAfter("flow=")
            browser.post("/api/flows/$flow/login", loginBody(email)).step("The login", 200)
            browser.post("/api/flows/$flow/password", passwordBody(PASSWORD)).step("The password", 200)
            val completed = browser.complete(flow).step("The completion", 200)
            val returnUrl = completed.json().get("returnUrl")
            return browser.get(withClue("The completion named no returnUrl") { returnUrl.shouldNotBeNull() }.asString())
        }

        fun whoAmI(form: Reply): JsonNode {
            val fields = mapOf("SAMLResponse" to form.formField("SAMLResponse"), "RelayState" to form.formField("RelayState"))
            val accepted = sp.post(form.formAction(), fields)
            withClue({ sp.log() }) { accepted.statusCode() shouldBe 303 }
            val page = sp.get(accepted.location())
            withClue({ sp.log() }) { page.statusCode() shouldBe 200 }
            return JsonMapper().readTree(page.body())
        }

        fun settings(algorithm: SignatureAlgorithm) =
            environment.acme.acting {
                val released = setOf(SamlAttribute.EMAIL, SamlAttribute.DISPLAY_NAME)
                federation.changeSettings(spId, SpSettings(signatureAlgorithm = algorithm, attributes = released), Actor.System)
            }

        test("an assigned user reaches the SP with a persistent NameID and the released attributes") {
            settings(SignatureAlgorithm.RSA_SHA256)
            val form = login(EMAIL)
            form.status shouldBe 200
            form.formAction() shouldBe "https://$SP_HOST/simplesaml/module.php/saml/sp/saml2-acs.php/default-sp"

            val seen = whoAmI(form)

            seen.text("nameIdFormat") shouldBe "urn:oasis:names:tc:SAML:2.0:nameid-format:persistent"
            seen.text("nameId") shouldNotContain EMAIL
            seen.text("authnContext") shouldBe PPT
            seen["attributes"][MAIL_OID].single().asString() shouldBe EMAIL
            seen["attributes"][DISPLAY_NAME_OID].single().asString() shouldBe "Ada Lovelace"
        }

        /**
         * SimpleSAMLphp 2.5.3 verifies through `xmlseclibs`, which knows no RSA-PSS, so this SP is set to `rsa-sha256`. This test
         * fails once a newer image verifies PSS, and then the SP should move to the default.
         */
        test("SimpleSAMLphp 2.5.3 cannot verify RSA-PSS yet, which is why its SP signs with rsa-sha256") {
            settings(SignatureAlgorithm.RSA_PSS_SHA256)
            val form = login(EMAIL)
            val fields = mapOf("SAMLResponse" to form.formField("SAMLResponse"), "RelayState" to form.formField("RelayState"))

            try {
                sp.post(form.formAction(), fields).statusCode() shouldBe 500
                sp.log() shouldContain "NOTVALIDCERTSIGNATURE"
            } finally {
                settings(SignatureAlgorithm.RSA_SHA256)
            }
        }

        test("the NameID stays the same at the next login") {
            settings(SignatureAlgorithm.RSA_SHA256)
            val first = whoAmI(login(EMAIL)).text("nameId")

            whoAmI(login(EMAIL)).text("nameId") shouldBe first
        }

        test("a user who is not assigned stops at the denial page and the SP gets nothing") {
            val reply = login(OUTSIDER)

            reply.status shouldBe 403
            reply.body shouldContain "do not have access"
            reply.body shouldNotContain "SAMLResponse"
        }

        test("the SP is reached over TLS that is verified, so a client trusting another CA is refused") {
            sp.welcome(sp.trust()) shouldBe 200
            shouldThrow<SSLHandshakeException> { sp.welcome(TestCa("Test other CA").trust()) }
        }
    })
