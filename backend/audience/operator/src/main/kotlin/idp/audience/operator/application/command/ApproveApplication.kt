package idp.audience.operator.application.command

import idp.sharedkernel.bus.Command
import idp.sharedkernel.bus.MessageHandler
import idp.sharedkernel.bus.TransactionScope
import idp.tenancy.application.TenancyService
import java.util.UUID

data class ApproveApplication(
    val id: UUID,
) : Command<Unit> {
    override val transaction = TransactionScope.PLATFORM
}

class ApproveApplicationHandler(
    private val tenancy: TenancyService,
) : MessageHandler<ApproveApplication, Unit> {
    override val messageType = ApproveApplication::class

    override fun handle(message: ApproveApplication) = tenancy.approve(message.id, operator(), operatorIp())
}
