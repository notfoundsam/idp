package idp.federation.adapters.saml

import idp.federation.IDP_SSO
import idp.federation.PASSKEY_CLASS
import idp.federation.PPT
import idp.federation.REFEDS_MFA
import idp.federation.RSA_SHA1
import idp.federation.SP_ACS
import idp.federation.SP_ENTITY_ID
import idp.federation.TestSp
import idp.federation.application.AcsEndpointView
import idp.federation.application.ServiceProviderView
import idp.federation.application.SpSettings
import idp.federation.authnRequest
import idp.federation.base64
import idp.federation.deflated
import idp.federation.postForm
import idp.federation.requestedContext
import idp.federation.withoutDeclaration
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.opensaml.saml.saml2.core.AuthnRequest
import org.opensaml.xmlsec.signature.support.SignatureConstants
import java.net.URLEncoder
import java.nio.file.Files
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

private const val OTHER_ACS = "https://sp.example/saml/acs2"
private const val POST_BINDING = "urn:oasis:names:tc:SAML:2.0:bindings:HTTP-POST"
private const val ARTIFACT_BINDING = "urn:oasis:names:tc:SAML:2.0:bindings:HTTP-Artifact"

private val now: Instant = Instant.parse("2026-11-03T10:00:00Z")
private val sp = TestSp()

private fun view(
    authnRequestsSigned: Boolean = false,
    certificates: TestSp = sp,
) = ServiceProviderView(
    UUID.randomUUID(),
    SP_ENTITY_ID,
    "Wiki",
    listOf(AcsEndpointView(0, SP_ACS, true), AcsEndpointView(1, OTHER_ACS, false)),
    listOf(certificates.certificate),
    authnRequestsSigned,
    SpSettings(),
    0,
)

private fun validator(registered: ServiceProviderView = view()) =
    AuthnRequestValidator({ entityId -> registered.takeIf { it.entityId == entityId } }, Clock.fixed(now, ZoneOffset.UTC))

private fun request(
    issueInstant: Instant = now,
    destination: String? = IDP_SSO,
    acsUrl: String? = null,
    acsIndex: Int? = null,
    protocolBinding: String? = null,
    forceAuthn: Boolean? = null,
    isPassive: Boolean? = null,
    requestedContext: String? = null,
    issuer: String? = SP_ENTITY_ID,
    id: String = "_${UUID.randomUUID()}",
) = authnRequest(issuer, id, destination, issueInstant, acsUrl, acsIndex, protocolBinding, forceAuthn, isPassive, requestedContext)

private fun AuthnRequestValidator.redirect(query: String) = validate(SsoMessage.Redirect(query), IDP_SSO)

private fun AuthnRequestValidator.post(
    fields: Map<String, List<String>>,
    query: String? = null,
) = validate(SsoMessage.Post(fields, query), IDP_SSO)

private fun Verdict.accepted(): Verdict.Accepted = shouldBeInstanceOf<Verdict.Accepted>()

/** A refusal before the ACS is known has nowhere to go but the error page. */
private fun Verdict.refusedOnPage(rejection: Rejection) {
    val refused = shouldBeInstanceOf<Verdict.Refused>()
    refused.rejection shouldBe rejection
    refused.replyTo.shouldBeNull()
}

private fun Verdict.refusedToAcs(rejection: Rejection): ReplyTo {
    val refused = shouldBeInstanceOf<Verdict.Refused>()
    refused.rejection shouldBe rejection
    return refused.replyTo.shouldNotBeNull()
}

private fun demandsOf(verdict: Verdict): Demands? {
    val xml = verdict.accepted().request.xml
    return Demands.of(OpenSaml.unmarshall(OpenSaml.parse(xml).documentElement) as AuthnRequest)
}

class AuthnRequestValidatorTest :
    FunSpec({
        context("a valid request") {
            test("by Redirect goes to the default ACS and keeps its RelayState") {
                val id = "_${UUID.randomUUID()}"

                val accepted = validator().redirect(sp.redirectQuery(request(id = id), relayState = "/wiki/page")).accepted()

                accepted.request.acsUrl shouldBe SP_ACS
                accepted.request.requestId shouldBe id
                accepted.request.relayState shouldBe "/wiki/page"
                accepted.request.idValidUntil shouldBe now + Duration.ofMinutes(6)
            }

            test("by Redirect with a signature over the query") {
                validator(view(authnRequestsSigned = true))
                    .redirect(sp.redirectQuery(request(), relayState = "state", signed = true))
                    .accepted()
            }

            test("by POST with an enveloped signature") {
                validator(view(authnRequestsSigned = true)).post(postForm(sp.signed(request()), "state")).accepted()
            }

            test("by POST with line breaks in its base64, as some SPs send it") {
                val wrapped = base64(request().toByteArray()).chunked(76).joinToString("\r\n")

                validator().post(mapOf("SAMLRequest" to listOf(wrapped))).accepted()
            }

            test("names a registered ACS by its URL or its index") {
                validator()
                    .redirect(sp.redirectQuery(request(acsUrl = OTHER_ACS, protocolBinding = POST_BINDING)))
                    .accepted()
                    .request.acsUrl shouldBe OTHER_ACS
                validator()
                    .redirect(sp.redirectQuery(request(acsIndex = 1)))
                    .accepted()
                    .request.acsUrl shouldBe OTHER_ACS
            }
        }

        context("the sender") {
            test("unknown to the tenant is refused") {
                validator().redirect(sp.redirectQuery(request(issuer = "https://evil.example/saml"))).refusedOnPage(Rejection.UNKNOWN_SP)
            }

            test("missing is refused") {
                validator().redirect(sp.redirectQuery(request(issuer = null))).refusedOnPage(Rejection.UNKNOWN_SP)
            }
        }

        context("the ACS") {
            test("at an address the SP did not register is refused, and the error does not go there") {
                validator()
                    .redirect(sp.redirectQuery(request(acsUrl = "https://evil.example/acs")))
                    .refusedOnPage(Rejection.ACS_NOT_REGISTERED)
            }

            test("that differs from a registered one in any character is refused") {
                validator().redirect(sp.redirectQuery(request(acsUrl = "$SP_ACS/"))).refusedOnPage(Rejection.ACS_NOT_REGISTERED)
            }

            test("by an unknown index is refused") {
                validator().redirect(sp.redirectQuery(request(acsIndex = 7))).refusedOnPage(Rejection.ACS_NOT_REGISTERED)
            }

            test("named by both an index and a URL is refused") {
                validator().redirect(sp.redirectQuery(request(acsUrl = SP_ACS, acsIndex = 0))).refusedOnPage(Rejection.ACS_NOT_REGISTERED)
            }

            test("for a binding other than HTTP-POST is refused") {
                validator()
                    .redirect(sp.redirectQuery(request(protocolBinding = ARTIFACT_BINDING)))
                    .refusedOnPage(Rejection.ACS_NOT_REGISTERED)
            }
        }

        context("the Destination") {
            test("of another endpoint is refused, and the error goes to the checked ACS") {
                val replyTo =
                    validator()
                        .redirect(sp.redirectQuery(request(destination = "https://globex.idp.test/saml/sso"), relayState = "state"))
                        .refusedToAcs(Rejection.DESTINATION_MISMATCH)

                replyTo.acsUrl shouldBe SP_ACS
                replyTo.relayState shouldBe "state"
            }

            test("is required once the request is signed") {
                validator(view(authnRequestsSigned = true))
                    .redirect(sp.redirectQuery(request(destination = null), signed = true))
                    .refusedToAcs(Rejection.DESTINATION_MISMATCH)
            }

            test("may be left out of an unsigned request") {
                validator().redirect(sp.redirectQuery(request(destination = null))).accepted()
            }
        }

        context("the signature") {
            test("is required when the SP says it signs") {
                validator(view(authnRequestsSigned = true)).redirect(sp.redirectQuery(request())).refusedOnPage(Rejection.SIGNATURE_MISSING)
                validator(view(authnRequestsSigned = true)).post(postForm(request())).refusedOnPage(Rejection.SIGNATURE_MISSING)
            }

            test("by another key is refused, also when the SP does not require one") {
                val stranger = TestSp()

                validator().redirect(stranger.redirectQuery(request(), signed = true)).refusedOnPage(Rejection.SIGNATURE_INVALID)
                validator().post(postForm(stranger.signed(request()))).refusedOnPage(Rejection.SIGNATURE_INVALID)
            }

            test("over a query changed after signing is refused") {
                val query = sp.redirectQuery(request(), relayState = "state", signed = true)

                validator(view(authnRequestsSigned = true))
                    .redirect(query.replace("RelayState=state", "RelayState=other"))
                    .refusedOnPage(Rejection.SIGNATURE_INVALID)
            }

            test("over XML changed after signing is refused") {
                val signed = sp.signed(request(forceAuthn = false))

                validator(view(authnRequestsSigned = true))
                    .post(postForm(signed.replace("ForceAuthn=\"false\"", "ForceAuthn=\"true\"")))
                    .refusedOnPage(Rejection.SIGNATURE_INVALID)
            }

            test("with SHA-1 is refused") {
                validator(view(authnRequestsSigned = true))
                    .redirect(sp.redirectQuery(request(), signed = true, algorithm = RSA_SHA1))
                    .refusedOnPage(Rejection.SIGNATURE_INVALID)
                validator(view(authnRequestsSigned = true))
                    .post(postForm(sp.signed(request(), algorithm = RSA_SHA1)))
                    .refusedOnPage(Rejection.SIGNATURE_INVALID)
                validator(view(authnRequestsSigned = true))
                    .post(postForm(sp.signed(request(), digest = SignatureConstants.ALGO_ID_DIGEST_SHA1)))
                    .refusedOnPage(Rejection.SIGNATURE_INVALID)
            }

            test("inside the XML of a Redirect is refused: that binding signs the query") {
                val signed = sp.signed(request())

                validator(view(authnRequestsSigned = true))
                    .redirect(sp.redirectQuery(signed, signed = true))
                    .refusedOnPage(Rejection.SIGNATURE_INVALID)
            }
        }

        context("signature wrapping") {
            test("a signature over a request carried inside another is refused") {
                val original = sp.signed(request(id = "_original"))
                val signature = Regex("<ds:Signature.*</ds:Signature>", RegexOption.DOT_MATCHES_ALL).find(original)!!.value
                val forged =
                    request(id = "_forged", acsUrl = OTHER_ACS, forceAuthn = false)
                        .replace("</saml:Issuer>", "</saml:Issuer>$signature")
                        .replace(
                            "</samlp:AuthnRequest>",
                            """<samlp:Extensions><w:Wrap xmlns:w="urn:test">$original</w:Wrap></samlp:Extensions></samlp:AuthnRequest>""",
                        )

                validator(view(authnRequestsSigned = true)).post(postForm(forged)).refusedOnPage(Rejection.SIGNATURE_INVALID)
            }

            test("a changed request that repeats the ID of the signed one fails the schema, where an ID is unique") {
                val original = sp.signed(request(id = "_original"))
                val signature = Regex("<ds:Signature.*</ds:Signature>", RegexOption.DOT_MATCHES_ALL).find(original)!!.value
                val forged =
                    request(id = "_original", acsUrl = OTHER_ACS)
                        .replace("</saml:Issuer>", "</saml:Issuer>$signature")
                        .replace(
                            "</samlp:AuthnRequest>",
                            """<samlp:Extensions><w:Wrap xmlns:w="urn:test">$original</w:Wrap></samlp:Extensions></samlp:AuthnRequest>""",
                        )

                validator(view(authnRequestsSigned = true)).post(postForm(forged)).refusedOnPage(Rejection.MALFORMED)
            }
        }

        context("the time") {
            test("of a request older than its lifetime and the skew is refused, and the error goes to the ACS") {
                validator().redirect(sp.redirectQuery(request(issueInstant = now - Duration.ofMinutes(7)))).refusedToAcs(Rejection.EXPIRED)
            }

            test("of a request issued beyond the skew in the future is refused") {
                validator().redirect(sp.redirectQuery(request(issueInstant = now + Duration.ofMinutes(4)))).refusedToAcs(Rejection.EXPIRED)
            }

            test("within the skew is accepted") {
                validator().redirect(sp.redirectQuery(request(issueInstant = now - Duration.ofMinutes(5)))).accepted()
                validator().redirect(sp.redirectQuery(request(issueInstant = now + Duration.ofMinutes(2)))).accepted()
            }
        }

        context("hostile XML") {
            test("with an external entity is refused before the entity is read") {
                val secret = Files.createTempFile("secret", ".txt").also { Files.writeString(it, "TOP-SECRET") }
                val xml =
                    """<?xml version="1.0"?><!DOCTYPE samlp:AuthnRequest [<!ENTITY xxe SYSTEM "${secret.toUri()}">]>""" +
                        request(issuer = "&xxe;")

                val verdict = validator().redirect(sp.redirectQuery(xml))

                verdict.refusedOnPage(Rejection.MALFORMED)
                verdict.toString() shouldNotContain "TOP-SECRET"
            }

            test("that expands entities is refused") {
                val xml =
                    """<?xml version="1.0"?><!DOCTYPE r [<!ENTITY a "aaaaaaaaaa"><!ENTITY b "&a;&a;&a;&a;&a;&a;&a;&a;&a;&a;">]>""" +
                        request(issuer = "&b;")

                validator().post(postForm(xml)).refusedOnPage(Rejection.MALFORMED)
            }

            test("outside the SAML schema is refused") {
                val xml = request().replace("<saml:Issuer>", "<saml:Unknown/><saml:Issuer>")

                validator().redirect(sp.redirectQuery(xml)).refusedOnPage(Rejection.MALFORMED)
            }

            test("of another message type is refused") {
                val xml =
                    """<samlp:LogoutRequest xmlns:samlp="urn:oasis:names:tc:SAML:2.0:protocol" """ +
                        """xmlns:saml="urn:oasis:names:tc:SAML:2.0:assertion" ID="_x" Version="2.0" IssueInstant="$now">""" +
                        """<saml:Issuer>$SP_ENTITY_ID</saml:Issuer><saml:NameID>x</saml:NameID></samlp:LogoutRequest>"""

                validator().redirect(sp.redirectQuery(xml)).refusedOnPage(Rejection.MALFORMED)
            }
        }

        context("the size") {
            test("of an inflated request is limited before parsing, so a small bomb gets nowhere") {
                val bomb = request().replace("</samlp:AuthnRequest>", " ".repeat(10_000_000) + "</samlp:AuthnRequest>")
                val query = "SAMLRequest=${URLEncoder.encode(base64(deflated(bomb.toByteArray())), Charsets.UTF_8)}"

                validator().redirect(query).refusedOnPage(Rejection.TOO_LARGE)
            }

            test("of a request by POST is limited too") {
                val large = request().replace("</samlp:AuthnRequest>", " ".repeat(MAX_REQUEST_BYTES) + "</samlp:AuthnRequest>")

                validator().post(postForm(large)).refusedOnPage(Rejection.TOO_LARGE)
            }

            test("of the RelayState is limited") {
                validator().redirect(sp.redirectQuery(request(), relayState = "r".repeat(MAX_RELAY_STATE_BYTES))).accepted()
                validator()
                    .redirect(sp.redirectQuery(request(), relayState = "r".repeat(MAX_RELAY_STATE_BYTES + 1)))
                    .refusedOnPage(Rejection.RELAY_STATE_TOO_LONG)
            }

            test("of the RelayState counts bytes, not characters") {
                validator()
                    .redirect(sp.redirectQuery(request(), relayState = "я".repeat(MAX_RELAY_STATE_BYTES / 2 + 1)))
                    .refusedOnPage(Rejection.RELAY_STATE_TOO_LONG)
            }
        }

        context("the encoding") {
            test("that is not base64 is refused") {
                validator().redirect("SAMLRequest=%%%").refusedOnPage(Rejection.MALFORMED)
                validator().redirect("SAMLRequest=not*base64").refusedOnPage(Rejection.MALFORMED)
            }

            test("that does not inflate is refused") {
                validator().redirect("SAMLRequest=${base64("plain".toByteArray())}").refusedOnPage(Rejection.MALFORMED)
            }

            test("is decoded once: base64 that encodes base64 is no request") {
                val twice = base64(base64(deflated(request().toByteArray())).toByteArray())

                validator().redirect("SAMLRequest=${URLEncoder.encode(twice, Charsets.UTF_8)}").refusedOnPage(Rejection.MALFORMED)
            }

            test("without a request is refused") {
                validator().redirect("RelayState=state").refusedOnPage(Rejection.MALFORMED)
                validator().post(emptyMap()).refusedOnPage(Rejection.MALFORMED)
            }
        }

        context("a repeated parameter") {
            listOf("SAMLRequest", "RelayState", "Signature", "SigAlg").forEach { name ->
                test("$name in a Redirect is refused") {
                    val query = sp.redirectQuery(request(), relayState = "state", signed = true)
                    val copy = query.split('&').first { it.startsWith("$name=") }

                    validator(view(authnRequestsSigned = true)).redirect("$query&$copy").refusedOnPage(Rejection.REPEATED_PARAMETER)
                }
            }

            test("hidden behind an encoded name is refused") {
                val query = sp.redirectQuery(request())

                validator().redirect("$query&SAML%52equest=${query.substringAfter('=')}").refusedOnPage(Rejection.REPEATED_PARAMETER)
            }

            test("in a POST body is refused") {
                val form = postForm(request(), "state")

                validator().post(form + ("RelayState" to listOf("state", "other"))).refusedOnPage(Rejection.REPEATED_PARAMETER)
                validator().post(form + ("SAMLRequest" to form.getValue("SAMLRequest") * 2)).refusedOnPage(Rejection.REPEATED_PARAMETER)
            }

            test("in the URL of a POST is refused: a parameter has one source") {
                validator().post(postForm(request()), "RelayState=state").refusedOnPage(Rejection.REPEATED_PARAMETER)
            }

            test("that the request does not read is ignored") {
                validator().redirect(sp.redirectQuery(request()) + "&utm=1&utm=2").accepted()
            }
        }

        context("what the SP demands") {
            test("is nothing beyond a login by default") {
                val demands = demandsOf(validator().redirect(sp.redirectQuery(request()))).shouldNotBeNull()

                demands.freshLogin shouldBe false
                demands.passive shouldBe false
                demands.assurance shouldBe Assurance.SINGLE_FACTOR
            }

            test("ForceAuthn asks for a fresh login and IsPassive for none at all") {
                val demands =
                    demandsOf(
                        validator().redirect(sp.redirectQuery(request(forceAuthn = true, isPassive = true))),
                    ).shouldNotBeNull()

                demands.freshLogin shouldBe true
                demands.passive shouldBe true
                demands.continuation("/saml/resume/x").requiresFreshLogin shouldBe true
            }

            listOf(
                Triple(requestedContext(PPT), Assurance.SINGLE_FACTOR, "the password class"),
                Triple(requestedContext(REFEDS_MFA), Assurance.MULTI_FACTOR, "REFEDS MFA"),
                Triple(requestedContext(PASSKEY_CLASS), Assurance.PASSKEY, "the passkey class"),
                Triple(requestedContext(PPT, REFEDS_MFA, comparison = "exact"), Assurance.SINGLE_FACTOR, "the weakest of an exact list"),
                Triple(requestedContext(REFEDS_MFA, comparison = "minimum"), Assurance.MULTI_FACTOR, "a minimum"),
                Triple(requestedContext(PPT, comparison = "better"), Assurance.MULTI_FACTOR, "better than a password"),
                Triple(requestedContext(PASSKEY_CLASS, comparison = "maximum"), Assurance.SINGLE_FACTOR, "a maximum"),
            ).forEach { (context, assurance, name) ->
                test("RequestedAuthnContext with $name needs $assurance") {
                    demandsOf(
                        validator().redirect(sp.redirectQuery(request(requestedContext = context))),
                    ).shouldNotBeNull().assurance shouldBe
                        assurance
                }
            }

            test("maps onto the flow") {
                val demands = demandsOf(validator().redirect(sp.redirectQuery(request(requestedContext = requestedContext(PASSKEY_CLASS)))))
                val continuation = demands.shouldNotBeNull().continuation("/saml/resume/x")

                continuation.requiresPasskey shouldBe true
                continuation.returnUrl shouldBe "/saml/resume/x"
            }

            test("that no login here meets is refused, and the error goes to the ACS") {
                validator()
                    .redirect(sp.redirectQuery(request(requestedContext = requestedContext("urn:example:smartcard"))))
                    .refusedToAcs(Rejection.NO_AUTHN_CONTEXT)
                validator()
                    .redirect(sp.redirectQuery(request(requestedContext = requestedContext(PASSKEY_CLASS, comparison = "better"))))
                    .refusedToAcs(Rejection.NO_AUTHN_CONTEXT)
            }
        }

        context("the request kept for later") {
            test("is the XML as received, so nothing of it is rebuilt") {
                val xml = request()

                String(
                    validator()
                        .redirect(sp.redirectQuery(xml))
                        .accepted()
                        .request.xml,
                ) shouldBe xml.withoutDeclaration()
            }
        }
    })

private operator fun List<String>.times(count: Int): List<String> = List(count) { this }.flatten()
