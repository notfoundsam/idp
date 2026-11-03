package idp.audience.admin.application.command

import idp.identity.application.IdentityService
import idp.sharedkernel.bus.Command
import idp.sharedkernel.bus.MessageHandler
import idp.sharedkernel.security.UserRole
import java.util.UUID

/** Not a data class: `toString` must not print the email. The mail with the link leaves from `worker`, so the token never gets here. */
class InviteUser(
    val email: String,
    val role: UserRole,
) : Command<UUID>

class InviteUserHandler(
    private val identity: IdentityService,
) : MessageHandler<InviteUser, UUID> {
    override val messageType = InviteUser::class

    override fun handle(message: InviteUser): UUID = identity.invite(message.email, message.role, currentAdmin(), clientIp())
}

class ResendInvitation(
    val userId: UUID,
) : Command<Unit>

class ResendInvitationHandler(
    private val identity: IdentityService,
) : MessageHandler<ResendInvitation, Unit> {
    override val messageType = ResendInvitation::class

    override fun handle(message: ResendInvitation) = identity.resendInvitation(message.userId, currentAdmin(), clientIp())
}
