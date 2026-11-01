package idp.identity.application

import idp.identity.adapters.crypto.HashLimit
import idp.identity.adapters.crypto.PasswordHasher
import idp.identity.adapters.persistence.Passwords
import idp.identity.adapters.wordlist.BannedPasswords
import idp.identity.domain.credential.PasswordCredential
import idp.identity.domain.credential.PasswordPolicy
import idp.sharedkernel.audit.Actor
import idp.sharedkernel.audit.AuditLog
import idp.sharedkernel.audit.AuditRecord
import idp.sharedkernel.audit.AuditTarget
import idp.sharedkernel.tenant.TenantTransaction
import java.time.Clock
import java.util.UUID

private const val PASSWORD_SET = "user.password_set"

/** Every hash runs outside a transaction, so call these with none open: a connection must not wait for Argon2id. */
class PasswordService internal constructor(
    private val transaction: TenantTransaction,
    private val identity: IdentityService,
    private val audit: AuditLog,
    private val hasher: PasswordHasher,
    private val limit: HashLimit,
    private val policy: PasswordPolicy,
    private val clock: Clock,
) {
    private val passwords = Passwords()

    constructor(
        transaction: TenantTransaction,
        identity: IdentityService,
        audit: AuditLog,
        clock: Clock = Clock.systemUTC(),
    ) : this(transaction, identity, audit, PasswordHasher(), HashLimit(), PasswordPolicy(BannedPasswords.load()), clock)

    /** [tenantWords] are the subdomain and the name of the tenant: only the audience can reach them. */
    fun setPassword(
        userId: UUID,
        password: String,
        tenantWords: Set<String>,
        actor: Actor,
        ip: String? = null,
    ) {
        policy.checkLength(password)
        val email = identity.emailOf(userId) ?: throw UserNotFound(userId)
        policy.check(password, tenantWords + email.substringBefore('@'))
        val hash = limit.run { hasher.hash(password) }
        transaction.execute { dsl ->
            passwords.replace(dsl, PasswordCredential.new(userId, hash))
            audit.recordChange(AuditRecord(actor, PASSWORD_SET, AuditTarget.user(userId), ip))
        }
    }

    /**
     * False for a wrong password, a running delay and a missing user alike, each at the cost of one hash, so neither the answer nor
     * its time tells them apart. Pass null when the login matched nobody. The permit comes first: overload must not count as a guess.
     */
    fun verify(
        userId: UUID?,
        password: String,
    ): Boolean {
        if (!policy.fitsHash(password)) return false
        return limit.run {
            val credential = userId?.let(::claimAttempt)
            if (credential == null) {
                hasher.hash(password)
                return@run false
            }
            hasher.matches(password, credential.hash).also { matches ->
                if (matches) succeed(credential, password)
            }
        }
    }

    /** The attempt counts before the hash, so a crash or a parallel burst cannot win free guesses. */
    private fun claimAttempt(userId: UUID): PasswordCredential? =
        transaction.execute { dsl ->
            val now = clock.instant()
            passwords.find(dsl, userId)?.takeIf { it.canAttemptAt(now) && passwords.claimAttempt(dsl, it, now) }
        }

    private fun succeed(
        credential: PasswordCredential,
        password: String,
    ) {
        val upgraded = if (hasher.isCurrent(credential.hash)) null else hasher.hash(password)
        transaction.execute { dsl ->
            passwords.clearAttempts(dsl, credential.userId)
            upgraded?.let { passwords.upgradeHash(dsl, credential, it) }
        }
    }
}
