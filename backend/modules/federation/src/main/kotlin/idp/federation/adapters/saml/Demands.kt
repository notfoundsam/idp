package idp.federation.adapters.saml

import idp.authentication.application.FlowContinuation
import org.opensaml.saml.saml2.core.AuthnContext
import org.opensaml.saml.saml2.core.AuthnContextComparisonTypeEnumeration
import org.opensaml.saml.saml2.core.AuthnRequest
import org.opensaml.saml.saml2.core.RequestedAuthnContext

/** Ordered from weakest to strongest, so a comparison is a comparison of positions. */
internal enum class Assurance {
    SINGLE_FACTOR,
    MULTI_FACTOR,
    PASSKEY,
}

/**
 * The classes an SP may ask for. REFEDS names multi-factor, no standard names a phishing-resistant login, so the passkey class
 * belongs to this product. A Response names the class it proves from the same table.
 */
internal object AuthnContextClasses {
    const val PASSKEY = "urn:idp:ac:classes:passkey"
    const val REFEDS_MFA = "https://refeds.org/profile/mfa"
    const val REFEDS_SFA = "https://refeds.org/profile/sfa"

    val levels: Map<String, Assurance> =
        mapOf(
            AuthnContext.UNSPECIFIED_AUTHN_CTX to Assurance.SINGLE_FACTOR,
            AuthnContext.PASSWORD_AUTHN_CTX to Assurance.SINGLE_FACTOR,
            AuthnContext.PPT_AUTHN_CTX to Assurance.SINGLE_FACTOR,
            REFEDS_SFA to Assurance.SINGLE_FACTOR,
            REFEDS_MFA to Assurance.MULTI_FACTOR,
            PASSKEY to Assurance.PASSKEY,
        )
}

/** What an SP demands of the login. A live session counts only where it meets all of it. */
internal class Demands(
    val freshLogin: Boolean,
    val passive: Boolean,
    val assurance: Assurance,
) {
    fun continuation(returnUrl: String) =
        FlowContinuation(
            returnUrl = returnUrl,
            requiresMultiFactor = assurance == Assurance.MULTI_FACTOR,
            requiresPasskey = assurance == Assurance.PASSKEY,
            requiresFreshLogin = freshLogin,
        )

    companion object {
        /** Null when no login here can meet the requested context. */
        fun of(request: AuthnRequest): Demands? {
            val requested = request.requestedAuthnContext
            val assurance = if (requested == null) Assurance.SINGLE_FACTOR else assurance(requested) ?: return null
            return Demands(request.isForceAuthn == true, request.isPassive == true, assurance)
        }

        /**
         * Classes of this table only, declarations never. `exact` and `minimum` need the weakest class named, `better` a class above
         * it, and `maximum` is met by any login, since one never proves less than a single factor.
         */
        private fun assurance(requested: RequestedAuthnContext): Assurance? {
            if (requested.authnContextDeclRefs.isNotEmpty()) return null
            val weakest =
                requested.authnContextClassRefs
                    .mapNotNull { AuthnContextClasses.levels[it.uri] }
                    .minOrNull() ?: return null
            return when (requested.comparison) {
                AuthnContextComparisonTypeEnumeration.BETTER -> Assurance.entries.getOrNull(weakest.ordinal + 1)
                AuthnContextComparisonTypeEnumeration.MAXIMUM -> Assurance.SINGLE_FACTOR
                else -> weakest
            }
        }
    }
}
