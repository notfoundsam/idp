package idp.audience.admin.application.query

import idp.audit.application.AuditEntryView
import idp.audit.application.AuditService
import idp.federation.application.FederationService
import idp.federation.application.ServiceProviderView
import idp.identity.application.IdentityService
import idp.identity.application.UserNotFound
import idp.identity.application.UserView
import idp.sharedkernel.audit.AuditTarget
import idp.sharedkernel.bus.MessageHandler
import idp.sharedkernel.bus.Query
import java.util.UUID

private const val CARD_EVENTS = 20

/** Not a data class: `toString` must not print the email or the name. */
class UserCard(
    val user: UserView,
    val email: String,
    val name: String?,
    val serviceProviders: List<ServiceProviderView>,
    val events: List<AuditEntryView>,
)

class GetUserCard(
    val userId: UUID,
) : Query<UserCard>

/** One query over `identity`, `federation` and `audit`, so the card reads all three in one transaction. */
class GetUserCardHandler(
    private val identity: IdentityService,
    private val federation: FederationService,
    private val audit: AuditService,
) : MessageHandler<GetUserCard, UserCard> {
    override val messageType = GetUserCard::class

    override fun handle(message: GetUserCard): UserCard {
        val id = message.userId
        val user = identity.user(id) ?: throw UserNotFound(id)
        val assigned = federation.assignmentsOf(id).toSet()
        return UserCard(
            user,
            checkNotNull(identity.emailOf(id)),
            identity.profile(id)?.name,
            federation.serviceProviders().filter { it.id in assigned }.sortedBy { it.name },
            audit.read(CARD_EVENTS, target = AuditTarget.user(id)),
        )
    }
}
