package idp.audience.admin.application.command

import idp.identity.application.IdentityService
import idp.sharedkernel.bus.Command
import idp.sharedkernel.bus.MessageHandler
import idp.sharedkernel.security.RequiresFreshAuthentication
import idp.sharedkernel.security.UserRole
import java.util.UUID

/**
 * Not a data class: `toString` must not print the email. The mail with the link leaves from `worker`, so the token never gets here.
 * The invited mailbox may get the admin role and keeps the account after the session is gone, so a stolen session alone must not be enough.
 */
class InviteUser(
    val email: String,
    val role: UserRole,
) : Command<UUID>,
    RequiresFreshAuthentication

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
