package idp.audience.operator.application.command

import idp.sharedkernel.bus.Command
import idp.sharedkernel.bus.MessageHandler
import idp.sharedkernel.bus.TransactionScope
import idp.tenancy.application.TenancyService
import java.util.UUID

/** Not a data class: `toString` must not print the contact. */
class BootstrapServiceTenant(
    val companyName: String,
    val contact: String,
) : Command<UUID> {
    override val transaction = TransactionScope.PLATFORM
}

class BootstrapServiceTenantHandler(
    private val tenancy: TenancyService,
) : MessageHandler<BootstrapServiceTenant, UUID> {
    override val messageType = BootstrapServiceTenant::class

    override fun handle(message: BootstrapServiceTenant): UUID =
        tenancy.bootstrapServiceTenant(message.companyName, message.contact, operator(), operatorIp())
}
