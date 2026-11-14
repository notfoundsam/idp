package idp.audience.admin.application.query

import idp.federation.application.FederationService
import idp.federation.application.ServiceProviderNotFound
import idp.federation.application.ServiceProviderView
import idp.federation.application.SigningKeyView
import idp.identity.application.IdentityService
import idp.identity.application.UserView
import idp.sharedkernel.bus.MessageHandler
import idp.sharedkernel.bus.Query
import java.util.UUID

class ListServiceProviders : Query<List<ServiceProviderView>>

class ListServiceProvidersHandler(
    private val federation: FederationService,
) : MessageHandler<ListServiceProviders, List<ServiceProviderView>> {
    override val messageType = ListServiceProviders::class

    override fun handle(message: ListServiceProviders): List<ServiceProviderView> = federation.serviceProviders()
}

class GetServiceProvider(
    val spId: UUID,
) : Query<ServiceProviderView>

class GetServiceProviderHandler(
    private val federation: FederationService,
) : MessageHandler<GetServiceProvider, ServiceProviderView> {
    override val messageType = GetServiceProvider::class

    override fun handle(message: GetServiceProvider): ServiceProviderView =
        federation.serviceProvider(message.spId) ?: throw ServiceProviderNotFound(message.spId)
}

class GetAssignedUsers(
    val spId: UUID,
) : Query<List<UserView>>

/** One query over `federation` and `identity`, so the list and the users it names come from one transaction. */
class GetAssignedUsersHandler(
    private val federation: FederationService,
    private val identity: IdentityService,
) : MessageHandler<GetAssignedUsers, List<UserView>> {
    override val messageType = GetAssignedUsers::class

    override fun handle(message: GetAssignedUsers): List<UserView> {
        federation.serviceProvider(message.spId) ?: throw ServiceProviderNotFound(message.spId)
        return federation.assignedUsers(message.spId).mapNotNull(identity::user)
    }
}

class ListSigningKeys : Query<List<SigningKeyView>>

class ListSigningKeysHandler(
    private val federation: FederationService,
) : MessageHandler<ListSigningKeys, List<SigningKeyView>> {
    override val messageType = ListSigningKeys::class

    override fun handle(message: ListSigningKeys): List<SigningKeyView> = federation.signingKeys()
}
