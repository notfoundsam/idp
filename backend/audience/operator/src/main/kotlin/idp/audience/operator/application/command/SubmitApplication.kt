package idp.audience.operator.application.command

import idp.sharedkernel.bus.Command
import idp.sharedkernel.bus.MessageHandler
import idp.sharedkernel.bus.TransactionScope
import idp.tenancy.application.NewApplication
import idp.tenancy.application.TenancyService
import java.util.UUID

/** Not a data class: `toString` must not print the contact. */
class SubmitApplication(
    val companyName: String,
    val subdomain: String,
    val contact: String,
) : Command<UUID> {
    override val transaction = TransactionScope.PLATFORM
}

class SubmitApplicationHandler(
    private val tenancy: TenancyService,
) : MessageHandler<SubmitApplication, UUID> {
    override val messageType = SubmitApplication::class

    override fun handle(message: SubmitApplication): UUID =
        tenancy.submit(NewApplication(message.companyName, message.subdomain, message.contact), operator(), operatorIp())
}
