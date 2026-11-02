package idp.audience.login.application.command

import idp.authentication.application.AuthenticationService
import idp.authentication.application.FlowAccess
import idp.authentication.application.FlowView
import idp.sharedkernel.bus.Command
import idp.sharedkernel.bus.MessageHandler

/** Not a data class: `toString` must not print the login. */
class SubmitLogin(
    val flow: FlowAccess,
    val login: String,
) : Command<FlowView>

class SubmitLoginHandler(
    private val authentication: AuthenticationService,
) : MessageHandler<SubmitLogin, FlowView> {
    override val messageType = SubmitLogin::class

    override fun handle(message: SubmitLogin): FlowView = authentication.identify(message.flow, message.login)
}
