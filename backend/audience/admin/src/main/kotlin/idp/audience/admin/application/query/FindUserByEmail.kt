package idp.audience.admin.application.query

import idp.identity.application.IdentityService
import idp.identity.application.UserView
import idp.sharedkernel.bus.MessageHandler
import idp.sharedkernel.bus.Query

/**
 * Only the whole address finds a user: it is stored encrypted, and its blind index matches nothing less. Not a data class: `toString`
 * must not print the email.
 */
class FindUserByEmail(
    val email: String,
) : Query<UserView?>

class FindUserByEmailHandler(
    private val identity: IdentityService,
) : MessageHandler<FindUserByEmail, UserView?> {
    override val messageType = FindUserByEmail::class

    override fun handle(message: FindUserByEmail): UserView? = identity.findByEmail(message.email)
}
