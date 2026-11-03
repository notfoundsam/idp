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

/** The last seen time moves once a minute, so a shorter idle timeout would end a session in use, the one of the admin too. */
internal val MIN_IDLE_TIMEOUT: Duration = Duration.ofMinutes(5)

/** The limits of AAL2 in NIST SP 800-63B-4, so no tenant leaves it. See `docs/security/sessions.md`. */
internal val MAX_IDLE_TIMEOUT: Duration = Duration.ofHours(1)
internal val MAX_ABSOLUTE_TIMEOUT: Duration = Duration.ofHours(24)

class SessionTimeoutsNotValid : DomainRuleViolation("The session timeouts are not valid")

/**
 * MFA has a switch only for the `USER` role, so no value of this class relaxes it for `TENANT_ADMIN`. See V6.3.3 in
 * `docs/security/asvs-l2.md`. A password never expires, so the policy has no field for it (V6.2.10).
 */
internal class TenantPolicy(
    val mfaForUsers: Boolean = true,
    val passkeyRequired: Boolean = false,
    val sessionIdleTimeout: Duration = Duration.ofMinutes(30),
    val sessionAbsoluteTimeout: Duration = Duration.ofHours(12),
    val version: Long = 0,
) {
    init {
        if (sessionIdleTimeout !in MIN_IDLE_TIMEOUT..MAX_IDLE_TIMEOUT ||
            sessionAbsoluteTimeout !in sessionIdleTimeout..MAX_ABSOLUTE_TIMEOUT
        ) {
            throw SessionTimeoutsNotValid()
        }
    }

    fun withPasskeysRequired() = TenantPolicy(mfaForUsers, true, sessionIdleTimeout, sessionAbsoluteTimeout, version)

    fun assuranceFor(role: UserRole): Assurance =
        when {
            passkeyRequired -> Assurance.PASSKEY
            mfaForUsers || role == UserRole.TENANT_ADMIN -> Assurance.MULTI_FACTOR
            else -> Assurance.SINGLE_FACTOR
        }
}
