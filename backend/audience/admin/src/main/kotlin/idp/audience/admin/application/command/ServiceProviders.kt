package idp.audience.admin.application.command

import idp.federation.application.FederationService
import idp.federation.application.SpSettings
import idp.sharedkernel.bus.Command
import idp.sharedkernel.bus.MessageHandler
import idp.sharedkernel.security.RequiresFreshAuthentication
import java.util.UUID

/** Only the content of the file arrives here, never its name. */
class RegisterServiceProvider(
    val name: String,
    val metadata: ByteArray,
) : Command<UUID>

class RegisterServiceProviderHandler(
    private val federation: FederationService,
) : MessageHandler<RegisterServiceProvider, UUID> {
    override val messageType = RegisterServiceProvider::class

    override fun handle(message: RegisterServiceProvider): UUID =
        federation.registerServiceProvider(message.name, message.metadata, currentAdmin(), clientIp())
}

/** Can release personal data to the SP or pick the legacy algorithm, so a stolen session alone must not be enough. Returns the version. */
class ChangeSpSettings(
    val spId: UUID,
    val settings: SpSettings,
    val version: Long,
) : Command<Long>,
    RequiresFreshAuthentication

class ChangeSpSettingsHandler(
    private val federation: FederationService,
) : MessageHandler<ChangeSpSettings, Long> {
    override val messageType = ChangeSpSettings::class

    override fun handle(message: ChangeSpSettings): Long =
        federation.changeSettings(message.spId, message.settings, currentAdmin(), clientIp(), message.version)
}

/** Cannot be undone: the assignments leave with it. */
class RemoveServiceProvider(
    val spId: UUID,
) : Command<Unit>,
    RequiresFreshAuthentication

class RemoveServiceProviderHandler(
    private val federation: FederationService,
) : MessageHandler<RemoveServiceProvider, Unit> {
    override val messageType = RemoveServiceProvider::class

    override fun handle(message: RemoveServiceProvider) = federation.removeServiceProvider(message.spId, currentAdmin(), clientIp())
}

/** The whole list in one transaction, so a user who is not there leaves everyone of the list unassigned. */
class AssignUsers(
    val spId: UUID,
    val userIds: List<UUID>,
) : Command<Unit>

class AssignUsersHandler(
    private val federation: FederationService,
) : MessageHandler<AssignUsers, Unit> {
    override val messageType = AssignUsers::class

    override fun handle(message: AssignUsers) = federation.assign(message.spId, message.userIds, currentAdmin(), clientIp())
}

class UnassignUsers(
    val spId: UUID,
    val userIds: List<UUID>,
) : Command<Unit>

class UnassignUsersHandler(
    private val federation: FederationService,
) : MessageHandler<UnassignUsers, Unit> {
    override val messageType = UnassignUsers::class

    override fun handle(message: UnassignUsers) = federation.unassign(message.spId, message.userIds, currentAdmin(), clientIp())
}

/** Publishes the next key in metadata. It signs nothing until the rotation completes. */
class StartKeyRotation : Command<UUID>

class StartKeyRotationHandler(
    private val federation: FederationService,
) : MessageHandler<StartKeyRotation, UUID> {
    override val messageType = StartKeyRotation::class

    override fun handle(message: StartKeyRotation): UUID = federation.startKeyRotation(currentAdmin(), clientIp())
}

/** Deletes the active key, and an SP that has not read the next one fails every login, so a stolen session alone must not be enough. */
class CompleteKeyRotation :
    Command<Unit>,
    RequiresFreshAuthentication

class CompleteKeyRotationHandler(
    private val federation: FederationService,
) : MessageHandler<CompleteKeyRotation, Unit> {
    override val messageType = CompleteKeyRotation::class

    override fun handle(message: CompleteKeyRotation) = federation.completeKeyRotation(currentAdmin(), clientIp())
}
