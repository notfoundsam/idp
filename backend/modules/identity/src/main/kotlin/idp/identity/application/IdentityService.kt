package idp.identity.application

import idp.identity.adapters.persistence.InvitationCleanup
import idp.identity.adapters.persistence.Invitations
import idp.identity.adapters.persistence.PasskeyChallengeCleanup
import idp.identity.adapters.persistence.UserProfiles
import idp.identity.adapters.persistence.Users
import idp.identity.domain.Email
import idp.identity.domain.EmailTaken
import idp.identity.domain.Invitation
import idp.identity.domain.InvitationToken
import idp.identity.domain.User
import idp.identity.domain.UserProfile
import idp.sharedkernel.audit.Actor
import idp.sharedkernel.audit.AuditLog
import idp.sharedkernel.audit.AuditRecord
import idp.sharedkernel.audit.AuditTarget
import idp.sharedkernel.crypto.FieldCipher
import idp.sharedkernel.crypto.FieldContext
import idp.sharedkernel.crypto.KeyHierarchy
import idp.sharedkernel.domain.DomainRuleViolation
import idp.sharedkernel.domain.NotFound
import idp.sharedkernel.outbox.NewEvent
import idp.sharedkernel.outbox.OutboxPublisher
import idp.sharedkernel.scheduling.TenantCleanup
import idp.sharedkernel.security.UserRole
import idp.sharedkernel.security.UserStatus
import idp.sharedkernel.tenant.TenantTransaction
import org.jooq.DSLContext
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.util.UUID

private const val CREATED = "user.created"
private const val BLOCKED = "user.blocked"
private const val UNBLOCKED = "user.unblocked"
private const val ROLE_CHANGED = "user.role_changed"
private const val ERASED = "user.erased"
private const val PROFILE_UPDATED = "user.profile_updated"
private const val INVITATION_ISSUED = "user.invitation_issued"
private const val INVITATION_ACCEPTED = "user.invitation_accepted"

private const val EMAIL_FIELD = "users.email_ciphertext"
private const val NAME_FIELD = "user_profiles.name_ciphertext"
private const val PHONE_FIELD = "user_profiles.phone_ciphertext"

class UserNotFound(
    id: UUID,
) : NotFound("No user $id")

/** The same answer for an unknown, spent and expired token, so a guess learns nothing. */
class InvitationNotValid : DomainRuleViolation("The invitation is not valid")

data class UserView(
    val id: UUID,
    val role: UserRole,
    val status: UserStatus,
    val version: Long,
)

/** Not a data class: `toString` must not print the name or the phone. */
class UserProfileView(
    val userId: UUID,
    val name: String?,
    val phone: String?,
    val version: Long,
)

/** Not a data class, for the same reason as [UserProfileView]. */
class ProfileChange(
    val name: String?,
    val phone: String?,
)

class IdentityService(
    private val transaction: TenantTransaction,
    private val keys: KeyHierarchy,
    private val audit: AuditLog,
    private val outbox: OutboxPublisher,
    private val clock: Clock = Clock.systemUTC(),
    private val invitationTtl: Duration = Duration.ofHours(72),
) {
    private val users = Users()
    private val profiles = UserProfiles()
    private val invitations = Invitations()
    private val random = SecureRandom()

    val invitationCleanup: TenantCleanup = InvitationCleanup()

    /** Lives here because `worker` has this service and no reason to hold a WebAuthn verifier. */
    val passkeyChallengeCleanup: TenantCleanup = PasskeyChallengeCleanup()

    /** The event carries no token: its handler issues one and sends it in the same transaction. */
    fun invite(
        email: String,
        role: UserRole,
        actor: Actor,
        ip: String? = null,
    ): UUID {
        val address = Email.of(email)
        val id = UUID.randomUUID()
        transaction.execute { dsl ->
            keys.createUserKey(id)
            val ciphertext = keys.userCipher(id).encrypt(address.value.toByteArray(), FieldContext(EMAIL_FIELD, id))
            val user = User.invited(id, emailIndex(address.value), ciphertext, role)
            if (!users.add(dsl, user)) throw EmailTaken()
            profiles.add(dsl, UserProfile.empty(id))
            outbox.publish(dsl, NewEvent(IdentityEvents.AGGREGATE, id, user.version, IdentityEvents.INVITED))
            audit.recordChange(AuditRecord(actor, CREATED, AuditTarget.user(id), ip, mapOf("role" to role.name)))
        }
        return id
    }

    /** Safe to repeat: a provisioning step can die after this commits and before the step is recorded. */
    fun inviteFirstAdmin(email: String): UUID =
        transaction.execute { dsl ->
            users.findByEmailIndex(dsl, emailIndex(email))?.id ?: invite(email, UserRole.TENANT_ADMIN, Actor.System)
        }

    /** Null when nobody waits for a link: the user is gone or has already accepted. */
    fun issueInvitation(
        userId: UUID,
        actor: Actor,
        ip: String? = null,
    ): String? =
        transaction.execute { dsl ->
            if (users.find(dsl, userId)?.status != UserStatus.INVITED) return@execute null
            val issued = Invitation.issue(userId, clock.instant(), invitationTtl, random)
            invitations.replace(dsl, issued.invitation)
            audit.recordChange(AuditRecord(actor, INVITATION_ISSUED, AuditTarget.user(userId), ip))
            issued.token.value
        }

    fun acceptInvitation(
        token: String,
        actor: Actor,
        ip: String? = null,
    ): UUID =
        transaction.execute { dsl ->
            val userId = invitations.consume(dsl, InvitationToken(token).hash(), clock.instant()) ?: throw InvitationNotValid()
            users.save(dsl, load(dsl, userId).apply { activate() })
            audit.recordChange(AuditRecord(actor, INVITATION_ACCEPTED, AuditTarget.user(userId), ip))
            userId
        }

    fun block(
        userId: UUID,
        actor: Actor,
        ip: String? = null,
    ) {
        transaction.execute { dsl ->
            val others = otherActiveAdmins(dsl, userId)
            users.save(dsl, load(dsl, userId).apply { block(others) })
            audit.recordChange(AuditRecord(actor, BLOCKED, AuditTarget.user(userId), ip))
        }
    }

    fun unblock(
        userId: UUID,
        actor: Actor,
        ip: String? = null,
    ) {
        transaction.execute { dsl ->
            users.save(dsl, load(dsl, userId).apply { unblock() })
            audit.recordChange(AuditRecord(actor, UNBLOCKED, AuditTarget.user(userId), ip))
        }
    }

    fun changeRole(
        userId: UUID,
        role: UserRole,
        actor: Actor,
        ip: String? = null,
    ) {
        transaction.execute { dsl ->
            val others = otherActiveAdmins(dsl, userId)
            val user = load(dsl, userId)
            val before = user.role
            if (before == role) return@execute
            user.changeRole(role, others)
            users.save(dsl, user)
            audit.recordChange(
                AuditRecord(actor, ROLE_CHANGED, AuditTarget.user(userId), ip, mapOf("from" to before.name, "to" to role.name)),
            )
        }
    }

    /** The record goes first: its IP is sealed with the key of the actor, who may be this user. The rest leaves by FK. */
    fun eraseUser(
        userId: UUID,
        actor: Actor,
        ip: String? = null,
    ) {
        transaction.execute { dsl ->
            val others = otherActiveAdmins(dsl, userId)
            load(dsl, userId).ensureErasable(others)
            audit.recordChange(AuditRecord(actor, ERASED, AuditTarget.user(userId), ip))
            users.delete(dsl, userId)
        }
    }

    fun updateProfile(
        userId: UUID,
        change: ProfileChange,
        actor: Actor,
        ip: String? = null,
    ) {
        transaction.execute { dsl ->
            val profile = profiles.find(dsl, userId) ?: throw UserNotFound(userId)
            val cipher = keys.userCipher(userId)
            profile.update(
                change.name?.let { cipher.encrypt(it.toByteArray(), FieldContext(NAME_FIELD, userId)) },
                change.phone?.let { cipher.encrypt(it.toByteArray(), FieldContext(PHONE_FIELD, userId)) },
            )
            profiles.save(dsl, profile)
            audit.recordChange(AuditRecord(actor, PROFILE_UPDATED, AuditTarget.user(userId), ip))
        }
    }

    fun user(id: UUID): UserView? = transaction.execute { dsl -> users.find(dsl, id)?.view() }

    fun findByEmail(email: String): UserView? = transaction.execute { dsl -> users.findByEmailIndex(dsl, emailIndex(email))?.view() }

    /** Null for a deleted user, so a mail for one is dropped. */
    fun emailOf(userId: UUID): String? =
        transaction.execute { dsl ->
            users.find(dsl, userId)?.let { user ->
                keys.userCipher(userId).decrypt(user.emailCiphertext, FieldContext(EMAIL_FIELD, userId)).decodeToString()
            }
        }

    fun profile(userId: UUID): UserProfileView? =
        transaction.execute { dsl ->
            profiles.find(dsl, userId)?.let { profile ->
                val cipher = keys.userCipher(userId)
                UserProfileView(
                    userId,
                    cipher.read(profile.nameCiphertext, NAME_FIELD, userId),
                    cipher.read(profile.phoneCiphertext, PHONE_FIELD, userId),
                    profile.version,
                )
            }
        }

    private fun FieldCipher.read(
        ciphertext: ByteArray?,
        field: String,
        userId: UUID,
    ): String? = ciphertext?.let { decrypt(it, FieldContext(field, userId)).decodeToString() }

    private fun emailIndex(email: String): ByteArray = keys.blindIndex().email(email)

    private fun load(
        dsl: DSLContext,
        id: UUID,
    ): User = users.find(dsl, id) ?: throw UserNotFound(id)

    /** Locks them, so two admins removing each other at once cannot both count on the other. Load the user after this. */
    private fun otherActiveAdmins(
        dsl: DSLContext,
        userId: UUID,
    ): Int = (users.lockActiveAdmins(dsl) - userId).size

    private fun User.view() = UserView(id, role, status, version)
}
