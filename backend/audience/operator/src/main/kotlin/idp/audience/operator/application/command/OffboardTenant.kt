package idp.audience.operator.application.command

import idp.sharedkernel.bus.Command
import idp.sharedkernel.bus.MessageHandler
import idp.sharedkernel.bus.TransactionScope
import idp.tenancy.application.TenancyService
import java.time.Instant
import java.util.UUID

/** Answers with the end of the waiting period: until then the operator can cancel, after it the data is gone for good. */
data class OffboardTenant(
    val id: UUID,
) : Command<Instant> {
    override val transaction = TransactionScope.PLATFORM
}

class OffboardTenantHandler(
    private val tenancy: TenancyService,
) : MessageHandler<OffboardTenant, Instant> {
    override val messageType = OffboardTenant::class

    override fun handle(message: OffboardTenant) = tenancy.startOffboarding(message.id, operator(), operatorIp())
}
