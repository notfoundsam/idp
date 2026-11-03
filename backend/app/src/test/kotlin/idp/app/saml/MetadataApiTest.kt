package idp.app.saml

import idp.app.login.ACME
import idp.app.login.Browser
import idp.app.login.GLOBEX
import idp.app.login.LoginEnvironment
import idp.federation.application.FederationService
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class MetadataApiTest :
    FunSpec({
        val environment = LoginEnvironment()
        val application = environment.start()
        environment.acme.acting { application.getBean(FederationService::class.java).createIdentity(ACME) }

        afterSpec { application.close() }

        test("serves the metadata of the tenant on its host without a session") {
            val reply = Browser(application, ACME).get("/saml/metadata")

            reply.status shouldBe 200
            reply.body shouldContain """entityID="https://$ACME/saml/metadata""""
            reply.body shouldContain "https://$ACME/saml/sso"
        }

        test("names the type with its charset and a fixed file name") {
            val reply = Browser(application, ACME).get("/saml/metadata?filename=../../evil.html")

            reply.header("Content-Type") shouldBe "application/samlmetadata+xml;charset=UTF-8"
            reply.header("Content-Disposition") shouldBe "attachment; filename=\"idp-metadata.xml\""
            reply.header("X-Content-Type-Options") shouldBe "nosniff"
        }

        test("of one tenant never shows on the host of another") {
            val reply = Browser(application, GLOBEX).get("/saml/metadata")

            reply.status shouldBe 404
            reply.body shouldNotContain ACME
        }

        test("of an unknown host is not found") {
            Browser(application, "nobody.idp.test").get("/saml/metadata").status shouldBe 404
        }
    })
