package idp.audience.operator.application.command

import idp.sharedkernel.bus.Command
import idp.sharedkernel.bus.MessageHandler
import idp.sharedkernel.bus.TransactionScope
import idp.tenancy.application.TenancyService
import java.util.UUID

data class SuspendTenant(
    val id: UUID,
) : Command<Unit> {
    override val transaction = TransactionScope.PLATFORM
}

class SuspendTenantHandler(
    private val tenancy: TenancyService,
) : MessageHandler<SuspendTenant, Unit> {
    override val messageType = SuspendTenant::class

    override fun handle(message: SuspendTenant) = tenancy.suspend(message.id, operator(), operatorIp())
}
