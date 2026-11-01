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

/** One transaction holds both steps, so a failed run leaves the reserved name free for the next one. */
class BootstrapServiceTenantHandler(
    private val tenancy: TenancyService,
) : MessageHandler<BootstrapServiceTenant, UUID> {
    override val messageType = BootstrapServiceTenant::class

    override fun handle(message: BootstrapServiceTenant): UUID {
        val application = tenancy.submitServiceTenant(message.companyName, message.contact, operator(), operatorIp())
        tenancy.approve(application, operator(), operatorIp())
        return application
    }
}
