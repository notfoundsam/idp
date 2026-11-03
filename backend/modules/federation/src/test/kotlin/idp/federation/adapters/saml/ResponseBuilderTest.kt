package idp.federation.adapters.saml

import idp.authentication.application.LoginFactor
import idp.authentication.application.SessionView
import idp.federation.PASSKEY_CLASS
import idp.federation.PPT
import idp.federation.REFEDS_MFA
import idp.federation.SP_ACS
import idp.federation.adapters.crypto.SigningKeyGenerator
import idp.federation.application.AcsEndpointView
import idp.federation.application.NameIdFormat
import idp.federation.application.SamlAttribute
import idp.federation.application.SamlSubject
import idp.federation.application.ServiceProviderView
import idp.federation.application.SignatureAlgorithm
import idp.federation.application.SignedPart
import idp.federation.application.SigningCredential
import idp.federation.application.SpSettings
import idp.federation.authnRequest
import idp.federation.requestedContext
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.opensaml.core.xml.schema.XSString
import org.opensaml.saml.saml2.core.AuthnRequest
import org.opensaml.saml.saml2.core.Response
import org.opensaml.saml.saml2.core.StatusCode
import org.opensaml.saml.saml2.core.SubjectConfirmation
import org.opensaml.saml.security.impl.SAMLSignatureProfileValidator
import org.opensaml.security.x509.BasicX509Credential
import org.opensaml.xmlsec.signature.impl.SignatureImpl
import org.opensaml.xmlsec.signature.support.SignatureException
import org.opensaml.xmlsec.signature.support.SignatureValidator
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID

private const val ISSUER = "https://acme.idp.test/saml/metadata"
private const val SP_ENTITY = "https://sp.example/saml"

private val now: Instant = Instant.parse("2026-11-03T10:00:00Z")
private val loggedInAt: Instant = now - Duration.ofMinutes(20)
private val sessionEnd: Instant = loggedInAt + Duration.ofHours(12)
private val material by lazy { SigningKeyGenerator().generate("acme.idp.test", now) }

/** The certificate as an SP gets it: out of the published metadata, not from the signing objects. */
private val published by lazy {
    signingCertificatesOf(parseEntity(IdpMetadata.write(ISSUER, "acme.idp.test", listOf(material.certificate)))).single()
}

private fun sp(settings: SpSettings = SpSettings()) =
    ServiceProviderView(UUID.randomUUID(), SP_ENTITY, "Wiki", listOf(AcsEndpointView(0, SP_ACS, true)), emptyList(), false, settings, 0)

private fun login(
    settings: SpSettings = SpSettings(),
    subject: SamlSubject = SamlSubject("opaque-id", NameIdFormat.PERSISTENT, emptyMap()),
    contextClass: String = PPT,
) = Login(ISSUER, ReplyTo(sp(settings), SP_ACS, "_request", "state"), subject, loggedInAt, sessionEnd, contextClass)

/** Parsed and checked against the SAML schema, as an SP would read it. */
private fun read(base64: String): Response {
    val document = OpenSaml.parse(Base64.getDecoder().decode(base64))
    OpenSaml.validate(document)
    return OpenSaml.unmarshall(document.documentElement) as Response
}

private fun write(login: Login = login()): Response =
    read(ResponseBuilder.write(login, SigningCredential(material.certificate, material.keyPair.private), now))

/** Checks the profile, the SHA-256 digest and the signature itself. */
private fun verify(signature: org.opensaml.xmlsec.signature.Signature?) {
    SAMLSignatureProfileValidator().validate(signature.shouldNotBeNull())
    val digest =
        (signature as SignatureImpl)
            .xmlSignature!!
            .signedInfo
            .item(0)
            .messageDigestAlgorithm.algorithmURI
    digest shouldBe SignatureAlgorithm.DIGEST_URI
    SignatureValidator.validate(signature, BasicX509Credential(published))
}

private fun session(vararg factors: LoginFactor) =
    SessionView(UUID.randomUUID(), UUID.randomUUID(), factors.toSet(), loggedInAt, sessionEnd)

private fun requested(context: String) =
    (OpenSaml.unmarshall(OpenSaml.parse(authnRequest(requestedContext = context).toByteArray()).documentElement) as AuthnRequest)
        .requestedAuthnContext

class ResponseBuilderTest :
    FunSpec({
        context("the Response") {
            test("answers the request at the checked ACS with success") {
                val response = write()

                response.inResponseTo shouldBe "_request"
                response.destination shouldBe SP_ACS
                response.issuer!!.value shouldBe ISSUER
                response.issueInstant shouldBe now
                response.status!!.statusCode!!.value shouldBe StatusCode.SUCCESS
                response.assertions.single()
            }
        }

        context("the conditions of the assertion") {
            test("limit it to the SP, the ACS, the request and five minutes") {
                val assertion = write().assertions.single()
                val confirmation = assertion.subject!!.subjectConfirmations.single()

                assertion.issuer!!.value shouldBe ISSUER
                assertion.conditions!!
                    .audienceRestrictions
                    .flatMap { it.audiences }
                    .map { it.uri }
                    .shouldContainExactly(SP_ENTITY)
                assertion.conditions!!.notBefore shouldBe now
                assertion.conditions!!.notOnOrAfter shouldBe now + Duration.ofMinutes(5)
                confirmation.method shouldBe SubjectConfirmation.METHOD_BEARER
                confirmation.subjectConfirmationData!!.recipient shouldBe SP_ACS
                confirmation.subjectConfirmationData!!.inResponseTo shouldBe "_request"
                confirmation.subjectConfirmationData!!.notOnOrAfter shouldBe now + Duration.ofMinutes(5)
            }
        }

        context("the signature") {
            SignatureAlgorithm.entries.forEach { algorithm ->
                test("over the assertion by default verifies with the certificate from the metadata, $algorithm") {
                    val response = write(login(SpSettings(signatureAlgorithm = algorithm)))

                    response.signature.shouldBeNull()
                    val assertion = response.assertions.single()
                    assertion.signature!!.signatureAlgorithm shouldBe algorithm.uri
                    verify(assertion.signature)
                }
            }

            test("over the Response only, where the SP asks for that") {
                val response = write(login(SpSettings(signedPart = SignedPart.RESPONSE)))

                response.assertions
                    .single()
                    .signature
                    .shouldBeNull()
                verify(response.signature)
            }

            test("over both, and the outer one covers the signed assertion") {
                val response = write(login(SpSettings(signedPart = SignedPart.BOTH)))

                verify(response.assertions.single().signature)
                verify(response.signature)
            }

            test("does not verify with the key of another tenant") {
                val other = SigningKeyGenerator().generate("globex.idp.test", now)
                val assertion = write().assertions.single()

                shouldThrow<SignatureException> {
                    SignatureValidator.validate(
                        assertion.signature!!,
                        BasicX509Credential(other.certificate),
                    )
                }
            }
        }

        context("the NameID") {
            test("persistent names both parties") {
                val nameId =
                    write()
                        .assertions
                        .single()
                        .subject!!
                        .nameID!!

                nameId.value shouldBe "opaque-id"
                nameId.format shouldBe NameIdFormat.PERSISTENT.uri
                nameId.nameQualifier shouldBe ISSUER
                nameId.spNameQualifier shouldBe SP_ENTITY
            }

            test("as email stands alone") {
                val nameId =
                    write(
                        login(subject = SamlSubject("ada@acme.test", NameIdFormat.EMAIL, emptyMap())),
                    ).assertions.single().subject!!.nameID!!

                nameId.value shouldBe "ada@acme.test"
                nameId.format shouldBe NameIdFormat.EMAIL.uri
                nameId.nameQualifier.shouldBeNull()
                nameId.spNameQualifier.shouldBeNull()
            }
        }

        context("the attributes") {
            test("carry their OID, the URI name format and a friendly name") {
                val subject =
                    SamlSubject(
                        "opaque-id",
                        NameIdFormat.PERSISTENT,
                        mapOf(SamlAttribute.DISPLAY_NAME to "Ada Lovelace", SamlAttribute.EMAIL to "ada@acme.test"),
                    )

                val attributes =
                    write(login(subject = subject))
                        .assertions
                        .single()
                        .attributeStatements
                        .single()
                        .attributes

                attributes.map { it.name }.shouldContainExactly(SamlAttribute.EMAIL.oid, SamlAttribute.DISPLAY_NAME.oid)
                attributes.map { it.friendlyName }.shouldContainExactly("mail", "displayName")
                attributes.forEach { it.nameFormat shouldBe SamlAttribute.NAME_FORMAT }
                attributes.map { (it.attributeValues.single() as XSString).value }.shouldContainExactly("ada@acme.test", "Ada Lovelace")
            }

            test("leave no statement when there are none") {
                write()
                    .assertions
                    .single()
                    .attributeStatements
                    .shouldBeEmpty()
            }

            test("are escaped as XML text") {
                val subject =
                    SamlSubject(
                        "opaque-id",
                        NameIdFormat.PERSISTENT,
                        mapOf(
                            SamlAttribute.DISPLAY_NAME to "</saml:AttributeValue><x>",
                        ),
                    )

                val value =
                    write(login(subject = subject))
                        .assertions
                        .single()
                        .attributeStatements
                        .single()
                        .attributes
                        .single()

                (value.attributeValues.single() as XSString).value shouldBe "</saml:AttributeValue><x>"
            }
        }

        context("the authentication statement") {
            test("states the time of the login, the end of the session and the class") {
                val statement =
                    write(login(contextClass = REFEDS_MFA))
                        .assertions
                        .single()
                        .authnStatements
                        .single()

                statement.authnInstant shouldBe loggedInAt
                statement.sessionNotOnOrAfter shouldBe sessionEnd
                statement.authnContext!!.authnContextClassRef!!.uri shouldBe REFEDS_MFA
                statement.sessionIndex.shouldBeNull()
            }
        }

        context("the class a Response states") {
            test("follows what the session proved when the SP asked for none") {
                AuthnContextClasses.of(Assurance.of(session(LoginFactor.PASSWORD).factors), null) shouldBe PPT
                AuthnContextClasses.of(Assurance.of(setOf(LoginFactor.PASSWORD, LoginFactor.TOTP)), null) shouldBe REFEDS_MFA
                AuthnContextClasses.of(Assurance.of(setOf(LoginFactor.PASSWORD, LoginFactor.RECOVERY_CODE)), null) shouldBe REFEDS_MFA
                AuthnContextClasses.of(Assurance.of(setOf(LoginFactor.PASSKEY)), null) shouldBe PASSKEY_CLASS
            }

            test("is one the SP listed under exact, the strongest the login meets") {
                val context = requested(requestedContext(PPT, REFEDS_MFA, comparison = "exact"))

                AuthnContextClasses.of(Assurance.MULTI_FACTOR, context) shouldBe REFEDS_MFA
                AuthnContextClasses.of(Assurance.PASSKEY, context) shouldBe REFEDS_MFA
                AuthnContextClasses.of(Assurance.SINGLE_FACTOR, context) shouldBe PPT
            }

            test("stays within a maximum") {
                AuthnContextClasses.of(Assurance.PASSKEY, requested(requestedContext(PPT, comparison = "maximum"))) shouldBe PPT
            }
        }

        context("a session against the demands of a request") {
            val accepted = loggedInAt + Duration.ofMinutes(1)

            test("of a single factor does not meet a demand for more") {
                Demands(false, false, Assurance.MULTI_FACTOR).metBy(session(LoginFactor.PASSWORD), accepted) shouldBe false
                Demands(false, false, Assurance.PASSKEY).metBy(session(LoginFactor.PASSWORD, LoginFactor.TOTP), accepted) shouldBe false
                Demands(false, false, Assurance.MULTI_FACTOR).metBy(session(LoginFactor.PASSKEY), accepted) shouldBe true
            }

            test("from before the request does not meet ForceAuthn") {
                Demands(true, false, Assurance.SINGLE_FACTOR).metBy(session(LoginFactor.PASSWORD), accepted) shouldBe false
                Demands(true, false, Assurance.SINGLE_FACTOR).metBy(session(LoginFactor.PASSWORD), loggedInAt) shouldBe true
                Demands(false, false, Assurance.SINGLE_FACTOR).metBy(session(LoginFactor.PASSWORD), accepted) shouldBe true
            }
        }
    })
