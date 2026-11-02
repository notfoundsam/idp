package idp.audience.operator.application.command.reinvite

import idp.audience.operator.application.command.operator
import idp.audience.operator.application.command.operatorIp
import idp.sharedkernel.bus.Command
import idp.sharedkernel.bus.MessageHandler
import idp.sharedkernel.bus.TransactionScope
import idp.tenancy.application.TenancyService
import java.util.UUID

/** Not a data class: `toString` must not print the contact. */
class ReinviteAdmin(
    val tenantId: UUID,
    val contact: String,
) : Command<Unit> {
    override val transaction = TransactionScope.PLATFORM
}

/** Only leaves a sealed address for `worker`, which alone can reach the tenant. See `docs/security/tenant-admin-recovery-runbook.md`. */
class ReinviteAdminHandler(
    private val tenancy: TenancyService,
) : MessageHandler<ReinviteAdmin, Unit> {
    override val messageType = ReinviteAdmin::class

    override fun handle(message: ReinviteAdmin) = tenancy.requestAdminReinvite(message.tenantId, message.contact, operator(), operatorIp())
}
