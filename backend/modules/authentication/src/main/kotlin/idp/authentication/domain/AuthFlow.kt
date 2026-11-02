package idp.authentication.domain

import idp.authentication.domain.AuthFactor.PASSKEY
import idp.authentication.domain.AuthFactor.PASSWORD
import idp.authentication.domain.AuthFactor.RECOVERY_CODE
import idp.authentication.domain.AuthFactor.TOTP
import idp.sharedkernel.domain.Conflict
import idp.sharedkernel.domain.DomainRuleViolation
import idp.sharedkernel.domain.Expired
import idp.sharedkernel.security.UserRole
import java.time.Duration
import java.time.Instant
import java.util.UUID

class FlowExpired : Expired("The login has expired")

class StepNotAllowed : Conflict("This step is not allowed now")

/** Another request is checking a credential on this flow. */
class StepInProgress : Conflict("This step is already being checked")

/** The same answer whether the entered login exists or not: a passkey of one's own must not probe the logins of others. */
class PasskeyNotAccepted : DomainRuleViolation("The passkey is not valid")

internal class FlowUser(
    val id: UUID,
    val role: UserRole,
    val enrolled: Set<AuthFactor>,
)

internal class PriorAuthentication(
    val sessionId: UUID,
    val user: FlowUser,
    val factors: Set<AuthFactor>,
)

internal class SpRequirements(
    val assurance: Assurance = Assurance.SINGLE_FACTOR,
    val freshLogin: Boolean = false,
)

/**
 * The next step is derived from what was proved, so no client can skip one. The caller checks credentials and reports the outcome
 * here. A login that matched nobody walks the same steps as any other, with [user] left null, and never gets past the password.
 *
 * [sessionId] is the session of the browser when the flow began. A flow that took over its factors depends on it, a fresh login only
 * replaces it.
 */
internal class AuthFlow(
    val id: UUID,
    user: FlowUser?,
    login: ByteArray?,
    proved: Set<AuthFactor>,
    val policy: TenantPolicy,
    val requirements: SpRequirements,
    val returnUrl: String?,
    val expiresAt: Instant,
    val sessionId: UUID? = null,
    val version: Long = 0,
) {
    private var user = user

    var login = login
        private set
    var proved = proved
        private set

    val userId: UUID? get() = user?.id

    val continuesSession: Boolean get() = sessionId != null && !requirements.freshLogin

    fun step(now: Instant): FlowStep {
        if (!now.isBefore(expiresAt)) throw FlowExpired()
        val user = user
        return when {
            user != null && proved.isNotEmpty() -> afterFirstFactor(user)
            user != null || login != null -> FlowStep.Password
            else -> FlowStep.Identify
        }
    }

    /** Asked before a credential is checked, so a request for the wrong step costs no hash and spends no attempt. */
    fun accepts(
        factor: AuthFactor,
        now: Instant,
    ): Boolean =
        when (val step = step(now)) {
            FlowStep.Identify -> factor == PASSKEY
            FlowStep.Password -> factor == PASSWORD || factor == PASSKEY
            is FlowStep.SecondFactor -> factor in step.factors
            is FlowStep.EnrollMfa, FlowStep.Complete -> false
        }

    /** Separate from [accepts]: offering a factor as proof must never let someone add one. */
    fun enrolls(
        factor: AuthFactor,
        now: Instant,
    ): Boolean = (step(now) as? FlowStep.EnrollMfa)?.let { factor in it.factors } == true

    /** Pass null when the login matched nobody. [login] is what was entered, already encrypted. */
    fun identify(
        user: FlowUser?,
        login: ByteArray,
        now: Instant,
    ) {
        if (step(now) != FlowStep.Identify) throw StepNotAllowed()
        this.user = user
        this.login = login
    }

    fun passwordPassed(now: Instant) {
        if (step(now) != FlowStep.Password || user == null) throw StepNotAllowed()
        proved += PASSWORD
    }

    /** A passkey names its owner. Once the flow expects someone, nobody else's passkey fits, and an unknown login expects no one. */
    fun passkeyPassed(
        owner: FlowUser,
        now: Instant,
    ) {
        if (!accepts(PASSKEY, now)) throw StepNotAllowed()
        if (step(now) != FlowStep.Identify && user?.id != owner.id) throw PasskeyNotAccepted()
        user = owner
        proved += PASSKEY
    }

    fun totpPassed(now: Instant) = secondFactorPassed(TOTP, now)

    fun recoveryCodeUsed(now: Instant) = secondFactorPassed(RECOVERY_CODE, now)

    /** Enrolling proves the factor: TOTP is confirmed by a code, a passkey is registered with user verification. */
    fun enrolled(
        factor: AuthFactor,
        now: Instant,
    ) {
        if (!enrolls(factor, now)) throw StepNotAllowed()
        proved += factor
        user = user?.run { FlowUser(id, role, enrolled + factor) }
    }

    private fun secondFactorPassed(
        factor: AuthFactor,
        now: Instant,
    ) {
        if (step(now) !is FlowStep.SecondFactor || !accepts(factor, now)) throw StepNotAllowed()
        proved += factor
    }

    /** An enrolled factor is asked for even under a relaxed policy, and it comes before any new one: a password alone must not add a factor. */
    private fun afterFirstFactor(user: FlowUser): FlowStep {
        val wanted = wanted(user)
        val reached = reached()
        return when {
            reached >= wanted -> FlowStep.Complete
            wanted == Assurance.PASSKEY && PASSKEY in user.enrolled -> FlowStep.SecondFactor(setOf(PASSKEY))
            reached < Assurance.MULTI_FACTOR && user.enrolled.isNotEmpty() -> FlowStep.SecondFactor(user.enrolled + RECOVERY_CODE)
            wanted == Assurance.PASSKEY -> FlowStep.EnrollMfa(setOf(PASSKEY))
            else -> FlowStep.EnrollMfa(setOf(TOTP, PASSKEY))
        }
    }

    private fun wanted(user: FlowUser): Assurance {
        val own = if (user.enrolled.isEmpty()) Assurance.SINGLE_FACTOR else Assurance.MULTI_FACTOR
        return maxOf(policy.assuranceFor(user.role), requirements.assurance, own)
    }

    private fun reached(): Assurance =
        when {
            PASSKEY in proved -> Assurance.PASSKEY
            PASSWORD in proved && (TOTP in proved || RECOVERY_CODE in proved) -> Assurance.MULTI_FACTOR
            else -> Assurance.SINGLE_FACTOR
        }

    companion object {
        /** A demand for a fresh login makes a session count for nothing. */
        fun start(
            id: UUID,
            policy: TenantPolicy,
            requirements: SpRequirements,
            returnUrl: String?,
            prior: PriorAuthentication?,
            now: Instant,
            ttl: Duration,
        ): AuthFlow {
            val session = prior.takeUnless { requirements.freshLogin }
            return AuthFlow(
                id,
                session?.user,
                null,
                session?.factors.orEmpty(),
                policy,
                requirements,
                returnUrl,
                now.plus(ttl),
                prior?.sessionId,
            )
        }
    }
}
