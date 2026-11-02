package idp.audience.login.application.command

import idp.authentication.application.AuthenticationService
import idp.authentication.application.FlowAccess
import idp.authentication.application.FlowAttempt
import idp.authentication.application.LoginFactor
import idp.sharedkernel.audit.Actor
import idp.sharedkernel.audit.AuditLog
import idp.sharedkernel.audit.AuditRecord
import idp.sharedkernel.audit.AuditTarget
import idp.sharedkernel.domain.DomainRuleViolation
import idp.sharedkernel.web.ClientIp
import java.util.UUID

private const val LOGIN_FAILED = "login.failed"
private const val FACTOR = "factor"

/** The one refusal for a wrong credential, a running delay, an unknown login and a user who may not log in. */
class LoginRejected : DomainRuleViolation("The login data is not valid, or the next attempt has to wait")

internal fun clientIp(): String? = ClientIp.currentOrNull()?.address

/** Commands here run without a transaction: a refusal must keep the attempt it counted, and a hash must hold no connection. */
class LoginAttempts(
    private val authentication: AuthenticationService,
    private val audit: AuditLog,
) {
    /** [check] answers null to refuse. */
    fun <T : Any> step(
        flow: FlowAccess,
        factor: LoginFactor,
        check: (FlowAttempt) -> T?,
    ): T {
        val attempt = authentication.claimStep(flow, factor)
        return checked(flow, factor, attempt.userId) { check(attempt) }
    }

    /** [check] answers null to refuse. */
    fun <T : Any> enrollment(
        flow: FlowAccess,
        factor: LoginFactor,
        check: (UUID) -> T?,
    ): T {
        val userId = authentication.claimEnrollment(flow, factor)
        return checked(flow, factor, userId) { check(userId) }
    }

    private fun <T : Any> checked(
        flow: FlowAccess,
        factor: LoginFactor,
        userId: UUID?,
        check: () -> T?,
    ): T =
        try {
            check() ?: refuse(factor, userId)
        } finally {
            authentication.releaseStep(flow)
        }

    private fun refuse(
        factor: LoginFactor,
        userId: UUID?,
    ): Nothing {
        audit.recordDenial(
            AuditRecord(Actor.Anonymous, LOGIN_FAILED, userId?.let(AuditTarget::user), clientIp(), mapOf(FACTOR to factor.name)),
        )
        throw LoginRejected()
    }
}
