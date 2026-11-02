package idp.audience.login.application.query

import idp.authentication.application.AuthenticationService
import idp.authentication.application.FlowAccess
import idp.authentication.application.FlowView
import idp.sharedkernel.bus.MessageHandler
import idp.sharedkernel.bus.Query

class GetFlow(
    val flow: FlowAccess,
) : Query<FlowView>

class GetFlowHandler(
    private val authentication: AuthenticationService,
) : MessageHandler<GetFlow, FlowView> {
    override val messageType = GetFlow::class

    override fun handle(message: GetFlow): FlowView = authentication.step(message.flow)
}
