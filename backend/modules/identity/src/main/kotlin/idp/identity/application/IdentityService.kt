package idp.identity.application

import idp.identity.adapters.persistence.EmailChangeNotices
import idp.identity.adapters.persistence.EmailChanges
import idp.identity.adapters.persistence.InvitationCleanup
import idp.identity.adapters.persistence.Invitations
import idp.identity.adapters.persistence.PasskeyChallengeCleanup
import idp.identity.adapters.persistence.Passkeys
import idp.identity.adapters.persistence.PasswordResets
import idp.identity.adapters.persistence.RecoveryCleanup
import idp.identity.adapters.persistence.RecoveryCodes
import idp.identity.adapters.persistence.Totps
import idp.identity.adapters.persistence.UserProfiles
import idp.identity.adapters.persistence.Users
import idp.identity.domain.Email
import idp.identity.domain.EmailTaken
import idp.identity.domain.Invitation
import idp.identity.domain.OneTimeToken
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
private const val PROFILE_UPDATED = "user.profile_updated"
private const val INVITATION_ISSUED = "user.invitation_issued"
private const val INVITATION_ACCEPTED = "user.invitation_accepted"
private const val REINVITED = "user.reinvited"
private const val PASSWORD_RESET_ACTION = "user.password_reset"
private const val PASSWORD_RESET_ISSUED = "user.password_reset_issued"
private const val MFA_RESET = "user.mfa_reset"
private const val EMAIL_CHANGE_ISSUED = "user.email_change_issued"

private const val EMAIL_FIELD = "users.email_ciphertext"
private const val NEW_EMAIL_FIELD = "email_changes.new_email_ciphertext"
private const val NAME_FIELD = "user_profiles.name_ciphertext"
private const val PHONE_FIELD = "user_profiles.phone_ciphertext"

/** Long enough for an outage of the mail server, and no longer: the row holds an address the user has given up. */
private val EMAIL_NOTICE_TTL: Duration = Duration.ofDays(7)

class UserNotFound(
    id: UUID,
) : NotFound("No user $id")

/** The same answer for an unknown, spent and expired token, so a guess learns nothing. */
class InvitationNotValid : DomainRuleViolation("The invitation is not valid")

/** The same answer for an unknown, spent and expired token, so a guess learns nothing. */
class PasswordResetNotValid : DomainRuleViolation("The password reset is not valid")

/** The same answer for an unknown, spent and expired token and for a taken address, so an address cannot be probed. */
class EmailChangeNotValid : DomainRuleViolation("The email change is not valid")

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

/** What a user lets other systems know of them. Each one is a field of its own, decrypted only when asked for. */
enum class UserClaim {
    EMAIL,
    NAME,
    PHONE,
}

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
    private val recoveryTtl: Duration = Duration.ofHours(1),
) {
    private val users = Users()
    private val profiles = UserProfiles()
    private val invitations = Invitations()
    private val passwordResets = PasswordResets()
    private val emailChanges = EmailChanges()
    private val emailNotices = EmailChangeNotices()
    private val totps = Totps()
    private val recoveryCodes = RecoveryCodes()
    private val passkeys = Passkeys()
    private val random = SecureRandom()

    val invitationCleanup: TenantCleanup = InvitationCleanup()

    val recoveryCleanup: TenantCleanup = RecoveryCleanup()

    /** Lives here because `worker` has no use for `PasskeyService` of its own. */
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

    /**
     * The way back for a tenant that lost its admin, after the customer was proved outside the system. Whoever holds [email] starts
     * over as an invited admin, so the factors go. The caller revokes the sessions in the same transaction.
     */
    fun reinviteAdmin(email: String): UUID {
        val address = Email.of(email).value
        return transaction.execute { dsl ->
            val user =
                users.findByEmailIndex(dsl, emailIndex(address))
                    ?: return@execute invite(address, UserRole.TENANT_ADMIN, Actor.System)
            user.reinvite()
            removeFactors(dsl, user.id)
            passwordResets.delete(dsl, user.id)
            emailChanges.delete(dsl, user.id)
            outbox.publish(dsl, NewEvent(IdentityEvents.AGGREGATE, user.id, users.save(dsl, user), IdentityEvents.INVITED))
            audit.recordChange(AuditRecord(Actor.System, REINVITED, AuditTarget.user(user.id)))
            user.id
        }
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

    /** Spends nothing, so a refused password can be typed again. */
    fun invitationOwner(token: String): UUID? =
        transaction.execute { dsl -> invitations.ownerOf(dsl, OneTimeToken(token).hash(), clock.instant()) }

    fun acceptInvitation(
        token: String,
        actor: Actor,
        ip: String? = null,
    ): UUID =
        transaction.execute { dsl ->
            val userId = invitations.consume(dsl, OneTimeToken(token).hash(), clock.instant()) ?: throw InvitationNotValid()
            users.save(dsl, load(dsl, userId).apply { activate() })
            audit.recordChange(AuditRecord(actor, INVITATION_ACCEPTED, AuditTarget.user(userId), ip))
            userId
        }

    /**
     * Takes one index lookup, one event and one audit row whether the login matched anyone or not, so neither the answer nor its time
     * tells. The event of a login that matched nobody names a random user and ends in [issuePasswordReset].
     */
    fun requestPasswordReset(
        login: String,
        ip: String? = null,
    ) {
        transaction.execute { dsl ->
            val user = users.findByEmailIndex(dsl, emailIndex(login))
            val id = user?.id ?: UUID.randomUUID()
            outbox.publish(
                dsl,
                NewEvent(IdentityEvents.AGGREGATE, id, user?.version ?: 0, IdentityEvents.PASSWORD_RESET_REQUESTED),
            )
            val target = user?.let { AuditTarget.user(it.id) }
            audit.recordChange(AuditRecord(Actor.Anonymous, IdentityEvents.PASSWORD_RESET_REQUESTED, target, ip))
        }
    }

    /** Null for anyone but an active user: an invited user holds an invitation, and a blocked one may not come back this way. */
    fun issuePasswordReset(
        userId: UUID,
        actor: Actor,
        ip: String? = null,
    ): String? =
        transaction.execute { dsl ->
            if (users.find(dsl, userId)?.status != UserStatus.ACTIVE) return@execute null
            val token = OneTimeToken.generate(random)
            passwordResets.replace(dsl, userId, token.hash(), clock.instant().plus(recoveryTtl))
            audit.recordChange(AuditRecord(actor, PASSWORD_RESET_ISSUED, AuditTarget.user(userId), ip))
            token.value
        }

    /** Spends nothing, so a refused password can be typed again. */
    fun passwordResetOwner(token: String): UUID? =
        transaction.execute { dsl ->
            passwordResets.ownerOf(dsl, OneTimeToken(token).hash(), clock.instant())?.takeIf { isActive(dsl, it) }
        }

    /**
     * Spends the token. The caller stores the password and revokes the sessions in the same transaction. A waiting email change goes
     * too: whoever needs this may have lost the session that asked for it.
     */
    fun completePasswordReset(
        token: String,
        ip: String? = null,
    ): UUID =
        transaction.execute { dsl ->
            val userId = passwordResets.consume(dsl, OneTimeToken(token).hash(), clock.instant()) ?: throw PasswordResetNotValid()
            if (!isActive(dsl, userId)) throw PasswordResetNotValid()
            emailChanges.delete(dsl, userId)
            audit.recordChange(AuditRecord(Actor.User(userId), PASSWORD_RESET_ACTION, AuditTarget.user(userId), ip))
            userId
        }

    /** The way back after losing every factor, so only an admin reaches it. The caller revokes the sessions in the same transaction. */
    fun resetMfa(
        userId: UUID,
        actor: Actor,
        ip: String? = null,
    ) {
        transaction.execute { dsl ->
            load(dsl, userId)
            removeFactors(dsl, userId)
            audit.recordChange(AuditRecord(actor, MFA_RESET, AuditTarget.user(userId), ip))
        }
    }

    /** Accepts an address that belongs to someone else, because refusing it here would tell who has an account. See [confirmEmailChange]. */
    fun requestEmailChange(
        userId: UUID,
        newEmail: String,
        actor: Actor,
        ip: String? = null,
    ) {
        val address = Email.of(newEmail)
        transaction.execute { dsl ->
            val user = load(dsl, userId)
            val ciphertext = keys.userCipher(userId).encrypt(address.value.toByteArray(), FieldContext(NEW_EMAIL_FIELD, userId))
            emailChanges.replace(dsl, userId, ciphertext, clock.instant().plus(recoveryTtl))
            outbox.publish(dsl, NewEvent(IdentityEvents.AGGREGATE, userId, user.version, IdentityEvents.EMAIL_CHANGE_REQUESTED))
            audit.recordChange(AuditRecord(actor, IdentityEvents.EMAIL_CHANGE_REQUESTED, AuditTarget.user(userId), ip))
        }
    }

    /** Null when no live request waits, so a late event mails nothing. */
    fun issueEmailChange(
        userId: UUID,
        actor: Actor,
        ip: String? = null,
    ): String? =
        transaction.execute { dsl ->
            val token = OneTimeToken.generate(random)
            if (!emailChanges.attachToken(dsl, userId, token.hash(), clock.instant())) return@execute null
            audit.recordChange(AuditRecord(actor, EMAIL_CHANGE_ISSUED, AuditTarget.user(userId), ip))
            token.value
        }

    /** The address that waits for its confirmation, or null when none does. */
    fun pendingEmailOf(userId: UUID): String? =
        transaction.execute { dsl ->
            emailChanges.newEmailOf(dsl, userId, clock.instant())?.let { ciphertext ->
                keys.userCipher(userId).decrypt(ciphertext, FieldContext(NEW_EMAIL_FIELD, userId)).decodeToString()
            }
        }

    /** Only a mailbox that received the token gets here, and a reset that was waiting for the old address goes. */
    fun confirmEmailChange(
        token: String,
        ip: String? = null,
    ): UUID =
        transaction.execute { dsl ->
            val now = clock.instant()
            val change = emailChanges.consume(dsl, OneTimeToken(token).hash(), now) ?: throw EmailChangeNotValid()
            val user = load(dsl, change.userId).takeIf { it.status == UserStatus.ACTIVE } ?: throw EmailChangeNotValid()
            val cipher = keys.userCipher(user.id)
            val address = cipher.decrypt(change.newEmailCiphertext, FieldContext(NEW_EMAIL_FIELD, user.id))
            val index = emailIndex(address.decodeToString())
            if (users.findByEmailIndex(dsl, index) != null) throw EmailChangeNotValid()
            emailNotices.add(dsl, user.id, user.emailCiphertext, now.plus(EMAIL_NOTICE_TTL))
            user.changeEmail(index, cipher.encrypt(address, FieldContext(EMAIL_FIELD, user.id)))
            passwordResets.delete(dsl, user.id)
            outbox.publish(dsl, NewEvent(IdentityEvents.AGGREGATE, user.id, users.save(dsl, user), IdentityEvents.EMAIL_CHANGED))
            audit.recordChange(AuditRecord(Actor.User(user.id), IdentityEvents.EMAIL_CHANGED, AuditTarget.user(user.id), ip))
            user.id
        }

    /** The address the last change left behind, kept until its owner is told. */
    fun previousEmailOf(userId: UUID): String? =
        transaction.execute { dsl ->
            emailNotices.oldEmailOf(dsl, userId)?.let { ciphertext ->
                keys.userCipher(userId).decrypt(ciphertext, FieldContext(EMAIL_FIELD, userId)).decodeToString()
            }
        }

    fun forgetPreviousEmail(userId: UUID) {
        transaction.execute { dsl -> emailNotices.delete(dsl, userId) }
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

    /**
     * The record goes first: its IP is sealed with the key of the actor, who may be this user. The rest leaves by FK, the key in
     * `user_keys` too, so a copy of the rows in a backup cannot be read. The ID is never given out again.
     */
    fun eraseUser(
        userId: UUID,
        actor: Actor,
        ip: String? = null,
    ) {
        transaction.execute { dsl ->
            val others = otherActiveAdmins(dsl, userId)
            val user = load(dsl, userId).apply { ensureErasable(others) }
            audit.recordChange(AuditRecord(actor, IdentityEvents.ERASED, AuditTarget.user(userId), ip))
            outbox.publish(dsl, NewEvent(IdentityEvents.AGGREGATE, userId, user.version, IdentityEvents.ERASED))
            users.delete(dsl, userId)
        }
    }

    /**
     * For a restored backup: the user asked to go before the backup was restored. The last-admin rule protected the tenant then,
     * and a restored row must not hold the erasure back. False when the row is not there.
     */
    fun eraseAgain(userId: UUID): Boolean =
        transaction.execute { dsl ->
            if (users.find(dsl, userId) == null) return@execute false
            audit.recordChange(AuditRecord(Actor.System, IdentityEvents.ERASED, AuditTarget.user(userId)))
            users.delete(dsl, userId)
            true
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

    /** The admin invited at provisioning, until someone erases them. Null before that step. */
    fun firstAdmin(): UUID? = transaction.execute { dsl -> users.firstAdmin(dsl) }

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

    /** Opens [wanted] only, so a Response that names no phone never decrypts one. A field without a value is left out. */
    fun claims(
        userId: UUID,
        wanted: Set<UserClaim>,
    ): Map<UserClaim, String> =
        transaction.execute { dsl ->
            val user = users.find(dsl, userId) ?: throw UserNotFound(userId)
            val profile = if (wanted.any { it != UserClaim.EMAIL }) profiles.find(dsl, userId) else null
            val cipher = keys.userCipher(userId)
            wanted
                .mapNotNull { claim ->
                    val value =
                        when (claim) {
                            UserClaim.EMAIL -> cipher.read(user.emailCiphertext, EMAIL_FIELD, userId)
                            UserClaim.NAME -> cipher.read(profile?.nameCiphertext, NAME_FIELD, userId)
                            UserClaim.PHONE -> cipher.read(profile?.phoneCiphertext, PHONE_FIELD, userId)
                        }
                    value?.let { claim to it }
                }.toMap()
        }

    private fun FieldCipher.read(
        ciphertext: ByteArray?,
        field: String,
        userId: UUID,
    ): String? = ciphertext?.let { decrypt(it, FieldContext(field, userId)).decodeToString() }

    private fun emailIndex(email: String): ByteArray = keys.blindIndex().email(email)

    private fun isActive(
        dsl: DSLContext,
        userId: UUID,
    ): Boolean = users.find(dsl, userId)?.status == UserStatus.ACTIVE

    private fun removeFactors(
        dsl: DSLContext,
        userId: UUID,
    ) {
        totps.delete(dsl, userId)
        recoveryCodes.delete(dsl, userId)
        passkeys.deleteOfUser(dsl, userId)
    }

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
