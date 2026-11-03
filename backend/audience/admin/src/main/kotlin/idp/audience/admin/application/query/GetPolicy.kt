package idp.audience.admin.application.query

import idp.authentication.application.AuthenticationService
import idp.authentication.application.PolicyView
import idp.sharedkernel.bus.MessageHandler
import idp.sharedkernel.bus.Query

class GetPolicy : Query<PolicyView>

class GetPolicyHandler(
    private val authentication: AuthenticationService,
) : MessageHandler<GetPolicy, PolicyView> {
    override val messageType = GetPolicy::class

    override fun handle(message: GetPolicy): PolicyView = authentication.policy()
}
