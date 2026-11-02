package idp.audience.login.application.command

import idp.authentication.application.AuthenticationService
import idp.authentication.application.CompletedFlow
import idp.authentication.application.FlowAccess
import idp.sharedkernel.audit.Actor
import idp.sharedkernel.audit.AuditLog
import idp.sharedkernel.audit.AuditRecord
import idp.sharedkernel.audit.AuditTarget
import idp.sharedkernel.bus.Command
import idp.sharedkernel.bus.MessageHandler

private const val LOGIN_SUCCEEDED = "login.succeeded"
private const val FACTORS = "factors"

class CompleteFlow(
    val flow: FlowAccess,
    val userAgent: String?,
) : Command<CompletedFlow>

class CompleteFlowHandler(
    private val authentication: AuthenticationService,
    private val audit: AuditLog,
) : MessageHandler<CompleteFlow, CompletedFlow> {
    override val messageType = CompleteFlow::class

    /** A flow that proved nothing new is no login, so it leaves no record. The record shares the transaction of the session it reports. */
    override fun handle(message: CompleteFlow): CompletedFlow {
        val ip = clientIp()
        val completed = authentication.completeFlow(message.flow, ip, message.userAgent)
        if (completed.session != null) {
            val factors =
                completed.factors
                    .map { it.name }
                    .sorted()
                    .joinToString(",")
            val user = completed.userId
            audit.recordChange(AuditRecord(Actor.User(user), LOGIN_SUCCEEDED, AuditTarget.user(user), ip, mapOf(FACTORS to factors)))
        }
        return completed
    }
}
