package idp.authentication.application

import idp.authentication.domain.AuthFactor
import idp.authentication.domain.AuthFlow
import idp.authentication.domain.FlowStep
import java.time.Instant
import java.util.UUID

enum class LoginFactor {
    PASSWORD,
    TOTP,
    PASSKEY,
    RECOVERY_CODE,
    ;

    internal fun domain(): AuthFactor = AuthFactor.valueOf(name)

    internal companion object {
        fun of(factor: AuthFactor): LoginFactor = valueOf(factor.name)
    }
}

enum class LoginStep {
    IDENTIFY,
    PASSWORD,
    SECOND_FACTOR,
    ENROLL_MFA,
    COMPLETE,
}

/** [factors] are those a step offers a choice of. */
data class FlowView(
    val id: UUID,
    val step: LoginStep,
    val factors: Set<LoginFactor>,
)

/** Who a credential is checked against, and whether that user may log in. Null when the login matched nobody or none was entered. */
class FlowAttempt(
    val userId: UUID?,
    val active: Boolean,
)

/** The id comes from a URL, so a flow opens only with the secret of the browser that began it. Not a data class: it would print it. */
class FlowAccess(
    val id: UUID,
    val browserSecret: String,
) {
    override fun toString(): String = "FlowAccess($id, ***)"
}

internal fun AuthFlow.view(now: Instant): FlowView =
    when (val step = step(now)) {
        FlowStep.Identify -> FlowView(id, LoginStep.IDENTIFY, emptySet())
        FlowStep.Password -> FlowView(id, LoginStep.PASSWORD, emptySet())
        is FlowStep.SecondFactor -> FlowView(id, LoginStep.SECOND_FACTOR, step.factors.map(LoginFactor::of).toSet())
        is FlowStep.EnrollMfa -> FlowView(id, LoginStep.ENROLL_MFA, step.factors.map(LoginFactor::of).toSet())
        FlowStep.Complete -> FlowView(id, LoginStep.COMPLETE, emptySet())
    }
