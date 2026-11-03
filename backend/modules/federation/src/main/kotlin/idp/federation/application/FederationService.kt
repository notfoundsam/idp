package idp.federation.application

import idp.federation.adapters.crypto.SigningKeyGenerator
import idp.federation.adapters.persistence.SamlIdentities
import idp.federation.adapters.persistence.SamlIdentityRow
import idp.federation.adapters.persistence.ServiceProviders
import idp.federation.adapters.persistence.SigningKeys
import idp.federation.adapters.persistence.SpAssignments
import idp.federation.adapters.saml.IdpMetadata
import idp.federation.adapters.saml.MetadataImporter
import idp.federation.domain.ServiceProvider
import idp.federation.domain.SigningKey
import idp.federation.domain.SigningKeyStatus
import idp.federation.domain.SpAssignment
import idp.federation.domain.x509
import idp.identity.application.IdentityService
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
import idp.sharedkernel.tenant.TenantTransaction
import org.jooq.DSLContext
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Clock
import java.util.UUID

private const val SP_REGISTERED = "sp.registered"
private const val SP_SETTINGS_CHANGED = "sp.settings_changed"
private const val SP_REMOVED = "sp.removed"
private const val SP_USER_ASSIGNED = "sp.user_assigned"
private const val SP_USER_UNASSIGNED = "sp.user_unassigned"
private const val KEY_CREATED = "saml.signing_key_created"
private const val ROTATION_STARTED = "saml.key_rotation_started"
private const val ROTATION_COMPLETED = "saml.key_rotation_completed"

private const val PRIVATE_KEY_FIELD = "saml_signing_keys.private_key_ciphertext"
private const val METADATA_PATH = "/saml/metadata"

class ServiceProviderNotFound(
    id: UUID,
) : NotFound("No Service Provider $id")

class EntityIdTaken : Conflict("The tenant already has a Service Provider with this EntityID")

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
) {
    private val identities = SamlIdentities()
    private val signingKeys = SigningKeys()
    private val serviceProviders = ServiceProviders()
    private val assignments = SpAssignments()
    private val importer = MetadataImporter()
    private val generator = SigningKeyGenerator()

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

    fun changeSettings(
        spId: UUID,
        settings: SpSettings,
        actor: Actor,
        ip: String? = null,
    ) {
        transaction.execute { dsl ->
            val sp = load(dsl, spId)
            if (sp.settings == settings) return@execute
            sp.changeSettings(settings)
            serviceProviders.save(dsl, sp)
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
                    ),
                ),
            )
        }
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
