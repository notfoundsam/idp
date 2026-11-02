package idp.audience.login.application.command

import idp.authentication.application.AuthenticationService
import idp.authentication.application.FlowAccess
import idp.authentication.application.FlowContinuation
import idp.authentication.application.FlowView
import idp.sharedkernel.bus.Command
import idp.sharedkernel.bus.MessageHandler

/** Not a data class: `toString` must not print the tokens. [reauthenticate] makes the session count for nothing. */
class StartFlow(
    val sessionToken: String?,
    val browserSecret: String?,
    val reauthenticate: Boolean,
) : Command<StartedFlow>

class StartedFlow(
    val access: FlowAccess,
    val view: FlowView,
)

class StartFlowHandler(
    private val authentication: AuthenticationService,
) : MessageHandler<StartFlow, StartedFlow> {
    override val messageType = StartFlow::class

    override fun handle(message: StartFlow): StartedFlow {
        val continuation = FlowContinuation(requiresFreshLogin = message.reauthenticate)
        val access = authentication.startFlow(continuation, message.sessionToken, message.browserSecret)
        return StartedFlow(access, authentication.step(access))
    }
}
