package idp.authentication.domain

import idp.sharedkernel.domain.DomainRuleViolation
import idp.sharedkernel.security.UserRole
import java.time.Duration

/** Ordered from weakest to strongest. A passkey verifies the user on its own, so it satisfies MFA too. */
internal enum class Assurance {
    SINGLE_FACTOR,
    MULTI_FACTOR,
    PASSKEY,
}

class SessionTimeoutsNotValid : DomainRuleViolation("The session timeouts are not valid")

/** MFA has a switch only for the `USER` role, so no value of this class relaxes it for `TENANT_ADMIN`. See V6.3.3 in `docs/security/asvs-l2.md`. */
internal class TenantPolicy(
    val mfaForUsers: Boolean = true,
    val passkeyRequired: Boolean = false,
    val sessionIdleTimeout: Duration = Duration.ofMinutes(30),
    val sessionAbsoluteTimeout: Duration = Duration.ofHours(12),
    val version: Long = 0,
) {
    init {
        if (!sessionIdleTimeout.isPositive || sessionAbsoluteTimeout < sessionIdleTimeout) throw SessionTimeoutsNotValid()
    }

    fun assuranceFor(role: UserRole): Assurance =
        when {
            passkeyRequired -> Assurance.PASSKEY
            mfaForUsers || role == UserRole.TENANT_ADMIN -> Assurance.MULTI_FACTOR
            else -> Assurance.SINGLE_FACTOR
        }
}
