package idp.authentication.application

import idp.authentication.domain.Assurance
import idp.authentication.domain.SpRequirements

/** What the caller demands of a login and where the browser goes after it. The URL stays opaque, so nothing here knows SAML. */
class FlowContinuation(
    val returnUrl: String,
    val requiresMultiFactor: Boolean = false,
    val requiresPasskey: Boolean = false,
    val requiresFreshLogin: Boolean = false,
) {
    internal fun requirements() =
        SpRequirements(
            when {
                requiresPasskey -> Assurance.PASSKEY
                requiresMultiFactor -> Assurance.MULTI_FACTOR
                else -> Assurance.SINGLE_FACTOR
            },
            requiresFreshLogin,
        )
}
