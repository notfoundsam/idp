package idp.authentication.domain

import idp.sharedkernel.security.UserRole

/** Ordered from weakest to strongest. A passkey verifies the user on its own, so it satisfies MFA too. */
internal enum class Assurance {
    SINGLE_FACTOR,
    MULTI_FACTOR,
    PASSKEY,
}

/** MFA has a switch only for the `USER` role, so no value of this class relaxes it for `TENANT_ADMIN`. See V6.3.3 in `docs/security/asvs-l2.md`. */
internal class TenantPolicy(
    val mfaForUsers: Boolean = true,
    val passkeyRequired: Boolean = false,
) {
    fun assuranceFor(role: UserRole): Assurance =
        when {
            passkeyRequired -> Assurance.PASSKEY
            mfaForUsers || role == UserRole.TENANT_ADMIN -> Assurance.MULTI_FACTOR
            else -> Assurance.SINGLE_FACTOR
        }
}
