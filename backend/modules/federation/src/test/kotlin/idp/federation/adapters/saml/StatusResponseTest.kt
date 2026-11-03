package idp.federation.adapters.saml

import idp.federation.SP_ACS
import idp.federation.adapters.crypto.SigningKeyGenerator
import idp.federation.application.AcsEndpointView
import idp.federation.application.ServiceProviderView
import idp.federation.application.SignatureAlgorithm
import idp.federation.application.SigningCredential
import idp.federation.application.SpSettings
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.opensaml.saml.saml2.core.Response
import org.opensaml.saml.saml2.core.StatusCode
import org.opensaml.saml.security.impl.SAMLSignatureProfileValidator
import org.opensaml.security.x509.BasicX509Credential
import org.opensaml.xmlsec.signature.support.SignatureException
import org.opensaml.xmlsec.signature.support.SignatureValidator
import java.time.Instant
import java.util.Base64
import java.util.UUID

private val material by lazy { SigningKeyGenerator().generate("acme.idp.test", Instant.now()) }
private val credential by lazy { SigningCredential(material.certificate, material.keyPair.private) }

private fun replyTo(relayState: String? = null) =
    ReplyTo(
        ServiceProviderView(
            UUID.randomUUID(),
            "https://sp.example/saml",
            "Wiki",
            listOf(AcsEndpointView(0, SP_ACS, true)),
            emptyList(),
            false,
            SpSettings(),
            0,
        ),
        SP_ACS,
        "_request",
        relayState,
    )

private fun read(base64: String): Response {
    val document = OpenSaml.parse(Base64.getDecoder().decode(base64))
    OpenSaml.validate(document)
    return OpenSaml.unmarshall(document.documentElement) as Response
}

class StatusResponseTest :
    FunSpec({
        SignatureAlgorithm.entries.forEach { algorithm ->
            test("an error answers the request, signed with $algorithm by the published key") {
                val now = Instant.parse("2026-11-03T10:00:00Z")

                val response =
                    read(
                        StatusResponse.write(
                            "https://acme.idp.test/saml/metadata",
                            replyTo(),
                            SamlError.NO_PASSIVE,
                            credential,
                            algorithm,
                            now,
                        ),
                    )

                response.inResponseTo shouldBe "_request"
                response.destination shouldBe SP_ACS
                response.issuer!!.value shouldBe "https://acme.idp.test/saml/metadata"
                response.issueInstant shouldBe now
                response.status!!.statusCode!!.value shouldBe StatusCode.RESPONDER
                response.status!!
                    .statusCode!!
                    .statusCode!!
                    .value shouldBe StatusCode.NO_PASSIVE
                response.assertions.shouldBeEmpty()
                response.signature!!.signatureAlgorithm shouldBe algorithm.uri
                SAMLSignatureProfileValidator().validate(response.signature!!)
                SignatureValidator.validate(response.signature!!, BasicX509Credential(material.certificate))
            }
        }

        test("a refused request is denied by the requester's fault") {
            val response =
                read(
                    StatusResponse.write(
                        "issuer",
                        replyTo(),
                        SamlError.REQUEST_DENIED,
                        credential,
                        SignatureAlgorithm.RSA_PSS_SHA256,
                        Instant.now(),
                    ),
                )

            response.status!!.statusCode!!.value shouldBe StatusCode.REQUESTER
            response.status!!
                .statusCode!!
                .statusCode!!
                .value shouldBe StatusCode.REQUEST_DENIED
        }

        test("does not verify with another key") {
            val other = SigningKeyGenerator().generate("globex.idp.test", Instant.now())
            val response =
                read(
                    StatusResponse.write(
                        "issuer",
                        replyTo(),
                        SamlError.NO_PASSIVE,
                        credential,
                        SignatureAlgorithm.RSA_PSS_SHA256,
                        Instant.now(),
                    ),
                )

            shouldThrow<SignatureException> { SignatureValidator.validate(response.signature!!, BasicX509Credential(other.certificate)) }
        }

        test("each answer has an ID of its own") {
            val first =
                read(StatusResponse.write("i", replyTo(), SamlError.NO_PASSIVE, credential, SignatureAlgorithm.RSA_SHA256, Instant.now()))
            val second =
                read(StatusResponse.write("i", replyTo(), SamlError.NO_PASSIVE, credential, SignatureAlgorithm.RSA_SHA256, Instant.now()))

            (first.id == second.id) shouldBe false
        }
    })
