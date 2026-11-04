package idp.federation.application

import idp.federation.adapters.crypto.SigningKeyGenerator
import idp.federation.adapters.persistence.SamlIdentities
import idp.federation.adapters.persistence.SamlIdentityRow
import idp.federation.adapters.persistence.SamlRequestCleanup
import idp.federation.adapters.persistence.SamlRequestRow
import idp.federation.adapters.persistence.SamlRequests
import idp.federation.adapters.persistence.ServiceProviders
import idp.federation.adapters.persistence.SigningKeys
import idp.federation.adapters.persistence.SpAssignments
import idp.federation.adapters.persistence.SubjectIdentifiers
import idp.federation.adapters.saml.IdpMetadata
import idp.federation.adapters.saml.MetadataImporter
import idp.federation.domain.ServiceProvider
import idp.federation.domain.SigningKey
import idp.federation.domain.SigningKeyStatus
import idp.federation.domain.SpAssignment
import idp.federation.domain.SubjectIdentifier
import idp.federation.domain.x509
import idp.identity.application.IdentityService
import idp.identity.application.UserClaim
import idp.identity.application.UserNotFound
import idp.sharedkernel.audit.Actor
import idp.sharedkernel.audit.AuditLog
import idp.sharedkernel.audit.AuditRecord
import idp.sharedkernel.audit.AuditTarget
import idp.sharedkernel.crypto.DerivedKey
import idp.sharedkernel.crypto.FieldContext
import idp.sharedkernel.crypto.KeyHierarchy
import idp.sharedkernel.domain.Conflict
import idp.sharedkernel.domain.NotFound
import idp.sharedkernel.persistence.VersionConflictException
import idp.sharedkernel.scheduling.TenantCleanup
import idp.sharedkernel.tenant.TenantTransaction
import idp.sharedkernel.throttle.TooManyRequestsException
import org.jooq.DSLContext
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

private const val SP_REGISTERED = "sp.registered"
private const val SP_SETTINGS_CHANGED = "sp.settings_changed"
private const val SP_REMOVED = "sp.removed"
private const val SP_USER_ASSIGNED = "sp.user_assigned"
private const val SP_USER_UNASSIGNED = "sp.user_unassigned"
private const val SP_LOGIN = "sp.login"
private const val SP_LOGIN_DENIED = "sp.login_denied"
private const val KEY_CREATED = "saml.signing_key_created"
private const val ROTATION_STARTED = "saml.key_rotation_started"
private const val ROTATION_COMPLETED = "saml.key_rotation_completed"

private const val PRIVATE_KEY_FIELD = "saml_signing_keys.private_key_ciphertext"
private const val REQUEST_FIELD = "saml_requests.request_ciphertext"
private const val RELAY_STATE_FIELD = "saml_requests.relay_state_ciphertext"
private const val METADATA_PATH = "/saml/metadata"
private const val SERVICE_PROVIDERS_TABLE = "service_providers"

private val REQUESTS_FULL_RETRY: Duration = Duration.ofMinutes(1)

class ServiceProviderNotFound(
    id: UUID,
) : NotFound("No Service Provider $id")

class EntityIdTaken : Conflict("The tenant already has a Service Provider with this EntityID")

class RequestReplayed : Conflict("The Service Provider sent a request with this ID before")

/** An AuthnRequest whose signature, sender and ACS were checked. [idValidUntil] is how long its ID must not come again. */
class VerifiedRequest(
    val spId: UUID,
    val requestId: String,
    val acsUrl: String,
    val xml: ByteArray,
    val relayState: String?,
    val idValidUntil: Instant,
)

/** Not a data class: `toString` must not print the request or the RelayState. */
class PendingRequest(
    val id: UUID,
    val spId: UUID,
    val acsUrl: String,
    val xml: ByteArray,
    val relayState: String?,
    val acceptedAt: Instant,
) {
    override fun toString(): String = "PendingRequest($id, $spId)"
}

/** Whom a Response names to one SP, and what it tells it. Not a data class: `toString` must not print either. */
class SamlSubject(
    val nameId: String,
    val nameIdFormat: NameIdFormat,
    val attributes: Map<SamlAttribute, String>,
) {
    override fun toString(): String = "SamlSubject($nameIdFormat, ${attributes.keys})"
}

class SamlIdentityMissing : IllegalStateException("The tenant has no SAML identity yet")

data class AcsEndpointView(
    val index: Int,
    val url: String,
    val isDefault: Boolean,
)

class ServiceProviderView(
    val id: UUID,
    val entityId: String,
    val name: String,
    val acs: List<AcsEndpointView>,
    val signingCertificates: List<X509Certificate>,
    val authnRequestsSigned: Boolean,
    val settings: SpSettings,
    val version: Long,
)

/** A published key. A [next] one is in metadata beside the active one, and signs only once the rotation completes. */
class SigningKeyView(
    val id: UUID,
    val next: Boolean,
    val certificate: X509Certificate,
    val createdAt: Instant,
)

/** Not a data class: `toString` must not print the private key. */
class SigningCredential(
    val certificate: X509Certificate,
    val privateKey: PrivateKey,
) {
    override fun toString(): String = "SigningCredential(${certificate.serialNumber})"
}

class FederationService(
    private val transaction: TenantTransaction,
    private val keys: KeyHierarchy,
    private val audit: AuditLog,
    private val identity: IdentityService,
    private val clock: Clock = Clock.systemUTC(),
    private val requestTtl: Duration = Duration.ofMinutes(15),
    private val maxOpenRequests: Int = 100_000,
) {
    private val identities = SamlIdentities()
    private val signingKeys = SigningKeys()
    private val serviceProviders = ServiceProviders()
    private val assignments = SpAssignments()
    private val requests = SamlRequests()
    private val subjects = SubjectIdentifiers()
    private val random = SecureRandom()
    private val importer = MetadataImporter()
    private val generator = SigningKeyGenerator()

    val requestCleanup: TenantCleanup = SamlRequestCleanup()

    /**
     * Fixes the EntityID from [host] and creates the first signing key. Safe to repeat: a provisioning step can die after this
     * commits and before the step is recorded.
     */
    fun createIdentity(host: String) {
        val key = newKey(host, SigningKeyStatus.ACTIVE)
        transaction.execute { dsl ->
            identities.add(dsl, SamlIdentityRow("https://$host$METADATA_PATH", host))
            if (signingKeys.lockedRing(dsl).active != null) return@execute
            signingKeys.add(dsl, key)
            audit.recordChange(AuditRecord(Actor.System, KEY_CREATED, AuditTarget.signingKey(key.id)))
        }
    }

    /** Publishes a new key in metadata. It signs only after [completeKeyRotation], once the SPs have read it. */
    fun startKeyRotation(
        actor: Actor,
        ip: String? = null,
    ): UUID {
        val host = transaction.execute { dsl -> identities.find(dsl) ?: throw SamlIdentityMissing() }.host
        val key = newKey(host, SigningKeyStatus.NEXT)
        transaction.execute { dsl ->
            signingKeys.lockedRing(dsl).ensureRotationCanStart()
            signingKeys.add(dsl, key)
            audit.recordChange(AuditRecord(actor, ROTATION_STARTED, AuditTarget.signingKey(key.id), ip))
        }
        return key.id
    }

    fun completeKeyRotation(
        actor: Actor,
        ip: String? = null,
    ) {
        transaction.execute { dsl ->
            val ring = signingKeys.lockedRing(dsl)
            val retired = ring.active
            val promoted = ring.completeRotation()
            retired?.let { signingKeys.delete(dsl, it.id) }
            signingKeys.saveStatus(dsl, promoted)
            audit.recordChange(AuditRecord(actor, ROTATION_COMPLETED, AuditTarget.signingKey(promoted.id), ip))
        }
    }

    /** Null before provisioning created the identity. */
    fun metadata(): String? =
        transaction.execute { dsl ->
            val identity = identities.find(dsl) ?: return@execute null
            IdpMetadata.write(identity.entityId, identity.host, signingKeys.ring(dsl).published.map { it.certificate })
        }

    fun entityId(): String? = transaction.execute { dsl -> identities.find(dsl)?.entityId }

    /** The active key first, as in metadata. */
    fun signingKeys(): List<SigningKeyView> =
        transaction.execute { dsl ->
            signingKeys.ring(dsl).published.map { SigningKeyView(it.id, it.status == SigningKeyStatus.NEXT, it.certificate, it.createdAt) }
        }

    fun signingCredential(): SigningCredential {
        val key = transaction.execute { dsl -> signingKeys.ring(dsl).active } ?: throw SamlIdentityMissing()
        val pkcs8 = keys.derivedCipher(DerivedKey.SAML_SIGNING).decrypt(key.privateKeyCiphertext, FieldContext(PRIVATE_KEY_FIELD, key.id))
        return SigningCredential(key.certificate, KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(pkcs8)))
    }

    /** Parses before the transaction, so a large or hostile file holds no connection. */
    fun registerServiceProvider(
        name: String,
        metadata: ByteArray,
        actor: Actor,
        ip: String? = null,
    ): UUID {
        val sp = ServiceProvider.registered(UUID.randomUUID(), name, importer.read(metadata))
        transaction.execute { dsl ->
            if (!serviceProviders.add(dsl, sp, clock.instant())) throw EntityIdTaken()
            audit.recordChange(
                AuditRecord(actor, SP_REGISTERED, AuditTarget.serviceProvider(sp.id), ip, mapOf("entity_id" to sp.entityId)),
            )
        }
        return sp.id
    }

    /**
     * [version] is the one the caller read, so a change made meanwhile is not undone unseen. A system caller that never read the
     * settings passes none. Returns the version after the change.
     */
    fun changeSettings(
        spId: UUID,
        settings: SpSettings,
        actor: Actor,
        ip: String? = null,
        version: Long? = null,
    ): Long =
        transaction.execute { dsl ->
            val sp = load(dsl, spId)
            if (version != null && version != sp.version) throw VersionConflictException(SERVICE_PROVIDERS_TABLE, spId, version)
            if (sp.settings == settings) return@execute sp.version
            sp.changeSettings(settings)
            val saved = serviceProviders.save(dsl, sp)
            audit.recordChange(
                AuditRecord(
                    actor,
                    SP_SETTINGS_CHANGED,
                    AuditTarget.serviceProvider(spId),
                    ip,
                    mapOf(
                        "name_id_format" to settings.nameIdFormat.name,
                        "signed_part" to settings.signedPart.name,
                        "signature_algorithm" to settings.signatureAlgorithm.name,
                        "attributes" to
                            settings.attributes
                                .map { it.name }
                                .sorted()
                                .joinToString(","),
                    ),
                ),
            )
            saved
        }

    /** The assignments go with it by FK, so a later SP with the same EntityID starts with nobody. */
    fun removeServiceProvider(
        spId: UUID,
        actor: Actor,
        ip: String? = null,
    ) {
        transaction.execute { dsl ->
            val sp = load(dsl, spId)
            serviceProviders.delete(dsl, spId)
            audit.recordChange(
                AuditRecord(actor, SP_REMOVED, AuditTarget.serviceProvider(spId), ip, mapOf("entity_id" to sp.entityId)),
            )
        }
    }

    fun serviceProvider(id: UUID): ServiceProviderView? = transaction.execute { dsl -> serviceProviders.find(dsl, id)?.view() }

    fun serviceProviderByEntityId(entityId: String): ServiceProviderView? =
        transaction.execute { dsl -> serviceProviders.findByEntityId(dsl, entityId)?.view() }

    fun serviceProviders(): List<ServiceProviderView> = transaction.execute { dsl -> serviceProviders.all(dsl).map { it.view() } }

    /** Records only what changed, so a repeated request leaves the journal as it was. */
    fun assign(
        spId: UUID,
        userIds: Collection<UUID>,
        actor: Actor,
        ip: String? = null,
    ) {
        transaction.execute { dsl ->
            load(dsl, spId)
            userIds.distinct().forEach { userId ->
                identity.user(userId) ?: throw UserNotFound(userId)
                if (assignments.add(dsl, SpAssignment(spId, userId, clock.instant()))) {
                    audit.recordChange(assignmentRecord(actor, SP_USER_ASSIGNED, spId, userId, ip))
                }
            }
        }
    }

    fun unassign(
        spId: UUID,
        userIds: Collection<UUID>,
        actor: Actor,
        ip: String? = null,
    ) {
        transaction.execute { dsl ->
            load(dsl, spId)
            userIds.distinct().forEach { userId ->
                if (assignments.delete(dsl, spId, userId)) {
                    audit.recordChange(assignmentRecord(actor, SP_USER_UNASSIGNED, spId, userId, ip))
                }
            }
        }
    }

    fun isAssigned(
        spId: UUID,
        userId: UUID,
    ): Boolean = transaction.execute { dsl -> assignments.exists(dsl, spId, userId) }

    fun assignedUsers(spId: UUID): List<UUID> = transaction.execute { dsl -> assignments.usersOf(dsl, spId) }

    fun assignmentsOf(userId: UUID): List<UUID> = transaction.execute { dsl -> assignments.spsOf(dsl, userId) }

    /**
     * Keeps [request] until the browser comes back for it, sealed outside the transaction. Refuses beyond [maxOpenRequests], because
     * an anonymous caller can send an unsigned request on behalf of any SP.
     */
    fun acceptRequest(request: VerifiedRequest): UUID {
        val id = UUID.randomUUID()
        val cipher = keys.derivedCipher(DerivedKey.FLOW)
        val row =
            SamlRequestRow(
                id,
                request.spId,
                request.acsUrl,
                cipher.encrypt(request.xml, FieldContext(REQUEST_FIELD, id)),
                request.relayState?.let { cipher.encrypt(it.toByteArray(), FieldContext(RELAY_STATE_FIELD, id)) },
                clock.instant(),
                clock.instant().plus(requestTtl),
            )
        transaction.execute { dsl ->
            val now = clock.instant()
            if (requests.countOpen(dsl, now, maxOpenRequests) >= maxOpenRequests) throw TooManyRequestsException(REQUESTS_FULL_RETRY)
            if (!requests.remember(dsl, request.spId, sha256(request.requestId), request.idValidUntil)) throw RequestReplayed()
            requests.add(dsl, row)
        }
        return id
    }

    /** Binds the request to the browser that came back first. Null when it is gone, expired or taken by another browser. */
    fun claimRequest(
        id: UUID,
        browserSecret: String,
    ): PendingRequest? {
        val row = transaction.execute { dsl -> requests.claim(dsl, id, sha256(browserSecret), clock.instant()) } ?: return null
        return pending(row)
    }

    /** Takes the request away for good. Null unless it is there, live, and bound to the browser that holds [browserSecret]. */
    fun consumeRequest(
        id: UUID,
        browserSecret: String,
    ): PendingRequest? {
        val row = transaction.execute { dsl -> requests.consume(dsl, id, sha256(browserSecret), clock.instant()) } ?: return null
        return pending(row)
    }

    /** For a request answered with an error before any login: nothing may resume it. */
    fun discardRequest(id: UUID) {
        transaction.execute { dsl -> requests.delete(dsl, id) }
    }

    /**
     * Checked on every Response, after the login, so a live session gets no further than a fresh one and an anonymous caller learns
     * nothing. Null when [userId] is not assigned to the SP, which the journal records apart from the rollback. Reads only the fields
     * the SP is given, and fixes the persistent NameID the first time this pair meets.
     */
    fun signIn(
        spId: UUID,
        userId: UUID,
        ip: String? = null,
    ): SamlSubject? {
        val target = AuditTarget.user(userId)
        val details = mapOf("sp_id" to spId.toString())
        return transaction.execute { dsl ->
            val sp = load(dsl, spId)
            if (!assignments.exists(dsl, spId, userId)) {
                audit.recordDenial(AuditRecord(Actor.User(userId), SP_LOGIN_DENIED, target, ip, details))
                return@execute null
            }
            val format = sp.settings.nameIdFormat
            val wanted =
                sp.settings.attributes
                    .map { it.claim }
                    .toSet() + setOfNotNull(UserClaim.EMAIL.takeIf { format == NameIdFormat.EMAIL })
            val claims = identity.claims(userId, wanted)
            val nameId =
                when (format) {
                    NameIdFormat.PERSISTENT -> subjects.valueOf(dsl, spId, userId, SubjectIdentifier.generate(random), clock.instant())
                    NameIdFormat.EMAIL -> checkNotNull(claims[UserClaim.EMAIL])
                }
            val attributes =
                sp.settings.attributes
                    .mapNotNull { attribute -> claims[attribute.claim]?.let { attribute to it } }
                    .toMap()
            audit.recordChange(AuditRecord(Actor.User(userId), SP_LOGIN, target, ip, details))
            SamlSubject(nameId, format, attributes)
        }
    }

    private fun pending(row: SamlRequestRow): PendingRequest {
        val cipher = keys.derivedCipher(DerivedKey.FLOW)
        return PendingRequest(
            row.id,
            row.spId,
            row.acsUrl,
            cipher.decrypt(row.requestCiphertext, FieldContext(REQUEST_FIELD, row.id)),
            row.relayStateCiphertext?.let { String(cipher.decrypt(it, FieldContext(RELAY_STATE_FIELD, row.id))) },
            row.acceptedAt,
        )
    }

    private fun assignmentRecord(
        actor: Actor,
        action: String,
        spId: UUID,
        userId: UUID,
        ip: String?,
    ) = AuditRecord(actor, action, AuditTarget.user(userId), ip, mapOf("sp_id" to spId.toString()))

    /** Generated and encrypted outside the transaction: an RSA key takes long enough to matter to a held connection. */
    private fun newKey(
        host: String,
        status: SigningKeyStatus,
    ): SigningKey {
        val id = UUID.randomUUID()
        val now = clock.instant()
        val material = generator.generate(host, now)
        val ciphertext =
            keys.derivedCipher(DerivedKey.SAML_SIGNING).encrypt(material.keyPair.private.encoded, FieldContext(PRIVATE_KEY_FIELD, id))
        return SigningKey(id, status, material.certificate, ciphertext, now)
    }

    private fun load(
        dsl: DSLContext,
        id: UUID,
    ): ServiceProvider = serviceProviders.find(dsl, id) ?: throw ServiceProviderNotFound(id)

    private fun sha256(value: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())

    private fun ServiceProvider.view() =
        ServiceProviderView(
            id,
            entityId,
            name,
            acs.map { AcsEndpointView(it.index, it.url.value, it.isDefault) },
            signingCertificates.map(::x509),
            authnRequestsSigned,
            settings,
            version,
        )
}
