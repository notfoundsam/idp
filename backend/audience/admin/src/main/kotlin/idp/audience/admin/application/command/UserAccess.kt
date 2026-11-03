package idp.audience.admin.application.command

import idp.authentication.application.AuthenticationService
import idp.identity.application.IdentityService
import idp.sharedkernel.bus.Command
import idp.sharedkernel.bus.MessageHandler
import idp.sharedkernel.security.RequiresFreshAuthentication
import idp.sharedkernel.security.UserRole
import java.util.UUID

/** The role is read on every request, so a live session of the user gets the new one on its next request. */
class ChangeRole(
    val userId: UUID,
    val role: UserRole,
) : Command<Unit>

class ChangeRoleHandler(
    private val identity: IdentityService,
) : MessageHandler<ChangeRole, Unit> {
    override val messageType = ChangeRole::class

    override fun handle(message: ChangeRole) = identity.changeRole(message.userId, message.role, currentAdmin(), clientIp())
}

class BlockUser(
    val userId: UUID,
) : Command<Unit>

/** The block and the end of the sessions share one transaction, so a block that rolled back leaves the sessions alone. */
class BlockUserHandler(
    private val identity: IdentityService,
    private val authentication: AuthenticationService,
) : MessageHandler<BlockUser, Unit> {
    override val messageType = BlockUser::class

    override fun handle(message: BlockUser) {
        val admin = currentAdmin()
        val ip = clientIp()
        identity.block(message.userId, admin, ip)
        authentication.revokeUserSessions(message.userId, admin, ip)
    }
}

class UnblockUser(
    val userId: UUID,
) : Command<Unit>

class UnblockUserHandler(
    private val identity: IdentityService,
) : MessageHandler<UnblockUser, Unit> {
    override val messageType = UnblockUser::class

    override fun handle(message: UnblockUser) = identity.unblock(message.userId, currentAdmin(), clientIp())
}

/** Cannot be undone, so a stolen session alone must not be enough. The sessions and logins in progress leave with the row by FK. */
class EraseUser(
    val userId: UUID,
) : Command<Unit>,
    RequiresFreshAuthentication

class EraseUserHandler(
    private val identity: IdentityService,
) : MessageHandler<EraseUser, Unit> {
    override val messageType = EraseUser::class

    override fun handle(message: EraseUser) = identity.eraseUser(message.userId, currentAdmin(), clientIp())
}

/** Opens the account to whoever holds the password, so a stolen session alone must not be enough. */
class ResetMfa(
    val userId: UUID,
) : Command<Unit>,
    RequiresFreshAuthentication

/** A session opened with the old factors must not outlive them, so it ends in the same transaction. */
class ResetMfaHandler(
    private val identity: IdentityService,
    private val authentication: AuthenticationService,
) : MessageHandler<ResetMfa, Unit> {
    override val messageType = ResetMfa::class

    override fun handle(message: ResetMfa) {
        val admin = currentAdmin()
        val ip = clientIp()
        identity.resetMfa(message.userId, admin, ip)
        authentication.revokeUserSessions(message.userId, admin, ip)
    }
}
