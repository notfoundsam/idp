package idp.federation.adapters.saml

import idp.authentication.application.FlowContinuation
import idp.authentication.application.LoginFactor
import idp.authentication.application.SessionView
import org.opensaml.saml.saml2.core.AuthnContext
import org.opensaml.saml.saml2.core.AuthnContextComparisonTypeEnumeration
import org.opensaml.saml.saml2.core.AuthnRequest
import org.opensaml.saml.saml2.core.RequestedAuthnContext
import java.time.Instant

/** Ordered from weakest to strongest, so a comparison is a comparison of positions. */
internal enum class Assurance {
    SINGLE_FACTOR,
    MULTI_FACTOR,
    PASSKEY,
    ;

    companion object {
        /** What a session proved. A passkey verifies the user on its own, and a password counts double only with a second factor. */
        fun of(factors: Set<LoginFactor>): Assurance =
            when {
                LoginFactor.PASSKEY in factors -> PASSKEY
                LoginFactor.PASSWORD in factors && (LoginFactor.TOTP in factors || LoginFactor.RECOVERY_CODE in factors) -> MULTI_FACTOR
                else -> SINGLE_FACTOR
            }
    }
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

    private val own: Map<Assurance, String> =
        mapOf(
            Assurance.SINGLE_FACTOR to AuthnContext.PPT_AUTHN_CTX,
            Assurance.MULTI_FACTOR to REFEDS_MFA,
            Assurance.PASSKEY to PASSKEY,
        )

    /**
     * The class a Response states. Without a request it names what the login reached. With one it names the strongest requested class
     * the login meets, so `exact` gets a class from its list, and a stronger login meets a weaker class.
     */
    fun of(
        reached: Assurance,
        requested: RequestedAuthnContext?,
    ): String =
        requested
            ?.authnContextClassRefs
            ?.mapNotNull { ref -> ref.uri?.let { uri -> levels[uri]?.let { uri to it } } }
            ?.filter { (_, level) -> level <= reached }
            ?.maxByOrNull { (_, level) -> level }
            ?.first ?: own.getValue(reached)
}

/** What an SP demands of the login. A live session counts only where it meets all of it. */
internal class Demands(
    val freshLogin: Boolean,
    val passive: Boolean,
    val assurance: Assurance,
) {
    /**
     * Asked again when the browser comes back, because it may come back with another session than the one the flow left: a forced
     * login must be newer than the request, and the factors must reach what was asked.
     */
    fun metBy(
        session: SessionView,
        acceptedAt: Instant,
    ): Boolean = Assurance.of(session.factors) >= assurance && (!freshLogin || !session.authenticatedAt.isBefore(acceptedAt))

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
