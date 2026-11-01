package idp.audience.operator.application.command

import idp.sharedkernel.bus.Command
import idp.sharedkernel.bus.MessageHandler
import idp.sharedkernel.bus.TransactionScope
import idp.tenancy.application.TenancyService
import java.util.UUID

data class ResumeTenant(
    val id: UUID,
) : Command<Unit> {
    override val transaction = TransactionScope.PLATFORM
}

class ResumeTenantHandler(
    private val tenancy: TenancyService,
) : MessageHandler<ResumeTenant, Unit> {
    override val messageType = ResumeTenant::class

    override fun handle(message: ResumeTenant) = tenancy.resume(message.id, operator(), operatorIp())
}
