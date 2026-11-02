package idp.audience.operator.application.command

import idp.sharedkernel.bus.Command
import idp.sharedkernel.bus.MessageHandler
import idp.sharedkernel.bus.TransactionScope
import idp.tenancy.application.TenancyService
import java.util.UUID

data class CancelOffboarding(
    val id: UUID,
) : Command<Unit> {
    override val transaction = TransactionScope.PLATFORM
}

class CancelOffboardingHandler(
    private val tenancy: TenancyService,
) : MessageHandler<CancelOffboarding, Unit> {
    override val messageType = CancelOffboarding::class

    override fun handle(message: CancelOffboarding) = tenancy.cancelOffboarding(message.id, operator(), operatorIp())
}
