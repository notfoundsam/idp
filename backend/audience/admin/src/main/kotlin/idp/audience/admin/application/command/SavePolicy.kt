package idp.audience.admin.application.command

import idp.authentication.application.AuthenticationService
import idp.authentication.application.PolicyChange
import idp.sharedkernel.bus.Command
import idp.sharedkernel.bus.MessageHandler
import idp.sharedkernel.security.RequiresFreshAuthentication

/** Can relax MFA for every user of the tenant, so a stolen session alone must not be enough. Returns the new version. */
class SavePolicy(
    val policy: PolicyChange,
    val version: Long,
) : Command<Long>,
    RequiresFreshAuthentication

class SavePolicyHandler(
    private val authentication: AuthenticationService,
) : MessageHandler<SavePolicy, Long> {
    override val messageType = SavePolicy::class

    override fun handle(message: SavePolicy): Long = authentication.savePolicy(message.policy, message.version, currentAdmin(), clientIp())
}
