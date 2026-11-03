package idp.ops.security

import idp.audience.operator.OperatorSecurityConfig
import idp.sharedkernel.audit.Actor
import idp.sharedkernel.security.Principal
import org.opensaml.saml.saml2.core.Assertion
import org.opensaml.saml.saml2.core.NameIDType
import org.opensaml.xmlsec.signature.Signature
import org.opensaml.xmlsec.signature.impl.SignatureImpl
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.saml2.core.Saml2Error
import org.springframework.security.saml2.core.Saml2ErrorCodes
import org.springframework.security.saml2.core.Saml2ResponseValidatorResult
import org.springframework.security.saml2.provider.service.authentication.OpenSaml5AuthenticationProvider.AssertionToken
import org.springframework.security.saml2.provider.service.authentication.OpenSaml5AuthenticationProvider.ResponseToken
import org.springframework.security.saml2.provider.service.authentication.Saml2AuthenticationException
import java.time.Clock
import java.time.Duration
import java.time.Instant

const val PASSKEY_CONTEXT = "urn:idp:ac:classes:passkey"

/** RSA-PSS is what the IdP signs `ops` with by default. Nothing else is accepted, so a setting changed at the IdP fails loudly. */
private const val RSA_PSS_SHA256 = "http://www.w3.org/2007/05/xmldsig-more#sha256-rsa-MGF1"
private const val SHA256 = "http://www.w3.org/2001/04/xmlenc#sha256"

/** A session of `ops` lives in the HTTP session and ends at [expiresAt], the end the IdP stated for its own session. */
class OperatorAuthentication(
    val principal: Principal,
    val expiresAt: Instant,
) : AbstractAuthenticationToken(emptyList()) {
    init {
        isAuthenticated = true
    }

    override fun getCredentials(): Any? = null

    override fun getPrincipal(): Any = principal

    override fun getName(): String = (principal.actor as Actor.Operator).name
}

/**
 * The checks of `ops` on top of what Spring Security verifies: signature, Issuer, Audience, Destination, the bearer confirmation and
 * the validity window. Spring runs the validators even after a failed signature, so the replay cache is written only in [authenticate],
 * which runs once everything passed.
 */
class OperatorAssertions(
    private val replays: AssertionReplayCache,
    private val clock: Clock = Clock.systemUTC(),
    private val clockSkew: Duration = Duration.ofMinutes(5),
) {
    /** `ops` starts every login, so a Response that answers no request of this browser is refused. */
    fun validateResponse(token: ResponseToken): Saml2ResponseValidatorResult {
        val response = token.response
        var result = Saml2ResponseValidatorResult.success()
        if (response.inResponseTo.isNullOrBlank()) {
            result = result.concat(Saml2Error(Saml2ErrorCodes.INVALID_IN_RESPONSE_TO, "The Response answers no request"))
        }
        if (response.assertions.size != 1 || response.encryptedAssertions.isNotEmpty()) {
            result = result.concat(Saml2Error.invalidResponse("The Response must carry exactly one plain assertion"))
        }
        response.signature?.let { result = result.concat(algorithms(it)) }
        return result
    }

    fun validateAssertion(token: AssertionToken): Saml2ResponseValidatorResult {
        val assertion = token.assertion
        val signature = assertion.signature ?: return failure("The assertion is not signed")
        var result = algorithms(signature)
        val statements = assertion.authnStatements
        if (statements.none { it.authnContext?.authnContextClassRef?.uri == PASSKEY_CONTEXT }) {
            result = result.concat(error("The login was not made with a passkey"))
        }
        val end = sessionEnd(assertion)
        if (end == null || !end.isAfter(clock.instant())) result = result.concat(error("The assertion states no live session"))
        if (operatorName(assertion) == null) result = result.concat(error("The assertion names no operator by email"))
        return result
    }

    fun authenticate(token: ResponseToken): OperatorAuthentication {
        val assertion = token.response.assertions.single()
        val firstUse = replays.firstUse(assertion.id, keepUntil(assertion))
        if (!firstUse) throw Saml2AuthenticationException(error("The assertion was used before"))
        val operator = Actor.Operator(checkNotNull(operatorName(assertion)))
        val principal = Principal(operator, OperatorSecurityConfig.OPERATOR, authnInstant(assertion))
        return OperatorAuthentication(principal, checkNotNull(sessionEnd(assertion)))
    }

    private fun algorithms(signature: Signature): Saml2ResponseValidatorResult {
        val digests =
            (signature as? SignatureImpl)
                ?.xmlSignature
                ?.signedInfo
                ?.let { info -> (0 until info.length).map { info.item(it).messageDigestAlgorithm.algorithmURI } }
                .orEmpty()
        val allowed = signature.signatureAlgorithm == RSA_PSS_SHA256 && digests.isNotEmpty() && digests.all { it == SHA256 }
        return if (allowed) Saml2ResponseValidatorResult.success() else failure("The signature uses an algorithm that is not allowed")
    }

    /** The earliest end the IdP stated, if it stated one in every statement. */
    private fun sessionEnd(assertion: Assertion): Instant? {
        val ends = assertion.authnStatements.map { it.sessionNotOnOrAfter }
        return if (ends.isEmpty() || ends.any { it == null }) null else ends.filterNotNull().min()
    }

    private fun authnInstant(assertion: Assertion): Instant? = assertion.authnStatements.mapNotNull { it.authnInstant }.maxOrNull()

    private fun operatorName(assertion: Assertion): String? =
        assertion.subject
            ?.nameID
            ?.takeIf { it.format == NameIDType.EMAIL }
            ?.value
            ?.takeIf { it.isNotBlank() }

    /** As long as any part of the assertion could still be accepted. */
    private fun keepUntil(assertion: Assertion): Instant {
        val ends =
            listOfNotNull(assertion.conditions?.notOnOrAfter) +
                assertion.subject
                    ?.subjectConfirmations
                    .orEmpty()
                    .mapNotNull { it.subjectConfirmationData?.notOnOrAfter }
        return (ends.maxOrNull() ?: clock.instant()).plus(clockSkew)
    }

    private fun error(description: String) = Saml2Error(Saml2ErrorCodes.INVALID_ASSERTION, description)

    private fun failure(description: String) =
        Saml2ResponseValidatorResult.failure(Saml2Error(Saml2ErrorCodes.INVALID_SIGNATURE, description))
}
