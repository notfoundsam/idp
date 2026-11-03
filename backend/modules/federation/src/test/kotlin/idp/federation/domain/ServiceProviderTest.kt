package idp.federation.domain

import idp.federation.application.NameIdFormat
import idp.federation.application.SignatureAlgorithm
import idp.federation.application.SignedPart
import idp.federation.application.SpSettings
import idp.federation.application.WeakSignatureAlgorithm
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import java.util.UUID

private fun description() =
    SpDescription(
        entityId = "https://sp.example/saml",
        acs = listOf(AcsEndpoint(0, AcsUrl.of("https://sp.example/acs"), isDefault = true)),
        signingCertificates = emptyList(),
        authnRequestsSigned = false,
    )

class ServiceProviderTest :
    FunSpec({
        context("an ACS URL") {
            listOf(
                "https://sp.example/saml/acs",
                "HTTPS://sp.example/acs",
                "https://sp.example:8443/acs?tenant=acme",
            ).forEach { url ->
                test("accepts $url") {
                    AcsUrl.of(url).value shouldBe url
                }
            }

            listOf(
                "http://sp.example/acs",
                "javascript:alert(document.cookie)",
                "JavaScript:alert(1)",
                "data:text/html;base64,PHNjcmlwdD5hbGVydCgxKTwvc2NyaXB0Pg==",
                "vbscript:msgbox(1)",
                "//sp.example/acs",
                "/saml/acs",
                "https:///acs",
                "https://user:secret@sp.example/acs",
                "https://sp.example/acs#fragment",
                " https://sp.example/acs",
                "https://sp.example/${"a".repeat(2048)}",
            ).forEach { url ->
                test("refuses ${url.take(60)}") {
                    shouldThrow<AcsUrlNotAllowed> { AcsUrl.of(url) }
                }
            }
        }

        context("the signature algorithm") {
            test("defaults to RSA-PSS with SHA-256") {
                SpSettings().signatureAlgorithm shouldBe SignatureAlgorithm.RSA_PSS_SHA256
            }

            listOf(
                "http://www.w3.org/2000/09/xmldsig#rsa-sha1",
                "http://www.w3.org/2007/05/xmldsig-more#sha1-rsa-MGF1",
                "http://www.w3.org/2000/09/xmldsig#dsa-sha1",
                "http://www.w3.org/2001/04/xmldsig-more#ecdsa-sha1",
                "http://www.w3.org/2000/09/xmldsig#hmac-sha1",
                "http://www.w3.org/2001/04/xmldsig-more#rsa-md5",
                "",
            ).forEach { uri ->
                test("refuses '$uri'") {
                    shouldThrow<WeakSignatureAlgorithm> { SignatureAlgorithm.ofUri(uri) }
                }
            }

            test("has no member with SHA-1, and the digest is SHA-256") {
                SignatureAlgorithm.entries.filter { it.uri.contains("sha1", ignoreCase = true) }.shouldBeEmpty()
                SignatureAlgorithm.DIGEST_URI shouldNotContain "sha1"
                SignatureAlgorithm.DIGEST_URI shouldBe "http://www.w3.org/2001/04/xmlenc#sha256"
            }

            test("maps the URIs it allows") {
                SignatureAlgorithm.entries.forEach { SignatureAlgorithm.ofUri(it.uri) shouldBe it }
            }
        }

        test("a new SP signs the assertion with RSA-PSS and gets a persistent NameID, the email only by a setting") {
            ServiceProvider.registered(UUID.randomUUID(), "Wiki", description()).settings shouldBe
                SpSettings(NameIdFormat.PERSISTENT, SignedPart.ASSERTION, SignatureAlgorithm.RSA_PSS_SHA256)
        }

        context("the name") {
            test("is trimmed") {
                ServiceProvider.registered(UUID.randomUUID(), "  Wiki  ", description()).name shouldBe "Wiki"
            }

            listOf("", "   ", "x".repeat(201)).forEach { name ->
                test("refuses '${name.take(20)}' of ${name.length} characters") {
                    shouldThrow<InvalidServiceProviderName> { ServiceProvider.registered(UUID.randomUUID(), name, description()) }
                }
            }
        }

        test("a settings change keeps the version, which the store raises") {
            val sp = ServiceProvider.registered(UUID.randomUUID(), "Wiki", description())
            val settings = SpSettings(NameIdFormat.EMAIL, SignedPart.BOTH, SignatureAlgorithm.RSA_SHA256)

            sp.changeSettings(settings)

            sp.settings shouldBe settings
            sp.version shouldBe 0
        }
    })
