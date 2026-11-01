package idp.identity.application

import idp.identity.adapters.persistence.RecoveryCodes
import idp.identity.adapters.persistence.Totps
import idp.identity.adapters.persistence.Users
import idp.identity.domain.credential.RecoveryCode
import idp.identity.domain.credential.TotpCredential
import idp.identity.domain.credential.TotpSecret
import idp.sharedkernel.audit.Actor
import idp.sharedkernel.audit.AuditLog
import idp.sharedkernel.audit.AuditRecord
import idp.sharedkernel.audit.AuditTarget
import idp.sharedkernel.crypto.FieldContext
import idp.sharedkernel.crypto.KeyHierarchy
import idp.sharedkernel.domain.DomainRuleViolation
import idp.sharedkernel.tenant.TenantTransaction
import org.jooq.DSLContext
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.util.UUID

private const val TOTP_ENROLLED = "user.totp_enrolled"
private const val RECOVERY_CODES_ISSUED = "user.recovery_codes_issued"
private const val RECOVERY_CODE_USED = "user.recovery_code_used"

private const val SECRET_FIELD = "totp_credentials.secret_ciphertext"

class TotpAlreadyEnrolled : DomainRuleViolation("TOTP is already enrolled")

/** Not a data class: `toString` must not print the secret. */
class TotpEnrollment(
    val secret: String,
) {
    override fun toString(): String = "TotpEnrollment(***)"
}

class TotpService(
    private val transaction: TenantTransaction,
    private val keys: KeyHierarchy,
    private val audit: AuditLog,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val users = Users()
    private val totps = Totps()
    private val recoveryCodes = RecoveryCodes()
    private val random = SecureRandom()

    /** Replaces a secret nobody confirmed, so an abandoned enrollment does not get in the way. */
    fun enroll(userId: UUID): TotpEnrollment {
        val secret = TotpSecret.generate(random)
        transaction.execute { dsl ->
            ensureExists(dsl, userId)
            val ciphertext = keys.userCipher(userId).encrypt(secret.bytes, FieldContext(SECRET_FIELD, userId))
            if (!totps.begin(dsl, userId, ciphertext)) throw TotpAlreadyEnrolled()
        }
        return TotpEnrollment(secret.encoded())
    }

    fun confirm(
        userId: UUID,
        code: String,
        actor: Actor,
        ip: String? = null,
    ): Boolean =
        transaction.execute { dsl ->
            val now = clock.instant()
            val credential = totps.find(dsl, userId)?.takeUnless { it.isConfirmed } ?: return@execute false
            val step = checkedStep(dsl, credential, code, now) ?: return@execute false
            totps.confirm(dsl, credential, step, now).also { confirmed ->
                if (confirmed) audit.recordChange(AuditRecord(actor, TOTP_ENROLLED, AuditTarget.user(userId), ip))
            }
        }

    /** False for a wrong code, a repeated code, a running delay and a user without TOTP alike. */
    fun verify(
        userId: UUID,
        code: String,
    ): Boolean =
        transaction.execute { dsl ->
            val credential = totps.find(dsl, userId)?.takeIf { it.isConfirmed } ?: return@execute false
            val step = checkedStep(dsl, credential, code, clock.instant()) ?: return@execute false
            totps.markUsed(dsl, credential, step)
        }

    /** New codes replace the old ones: asking for them means the old ones may be lost. */
    fun issueRecoveryCodes(
        userId: UUID,
        actor: Actor,
        ip: String? = null,
    ): List<String> {
        val issued = RecoveryCode.generate(random)
        transaction.execute { dsl ->
            ensureExists(dsl, userId)
            recoveryCodes.replace(dsl, userId, issued.map { it.hash() })
            audit.recordChange(AuditRecord(actor, RECOVERY_CODES_ISSUED, AuditTarget.user(userId), ip))
        }
        return issued.map { it.value }
    }

    /** Counts no attempts: 128 bits cannot be guessed online, and a TOTP delay must not lock the user out of the way back in. */
    fun useRecoveryCode(
        userId: UUID,
        code: String,
        actor: Actor,
        ip: String? = null,
    ): Boolean =
        transaction.execute { dsl ->
            recoveryCodes.consume(dsl, userId, RecoveryCode(code).hash()).also { used ->
                if (used) audit.recordChange(AuditRecord(actor, RECOVERY_CODE_USED, AuditTarget.user(userId), ip))
            }
        }

    /** The attempt counts before the code is checked, and it stays counted unless the caller spends the step in this transaction. */
    private fun checkedStep(
        dsl: DSLContext,
        credential: TotpCredential,
        code: String,
        now: Instant,
    ): Long? {
        if (!credential.canAttemptAt(now) || !totps.claimAttempt(dsl, credential, now)) return null
        val secret = keys.userCipher(credential.userId).decrypt(credential.secretCiphertext, FieldContext(SECRET_FIELD, credential.userId))
        return TotpSecret(secret).matchedStep(code, now)
    }

    private fun ensureExists(
        dsl: DSLContext,
        userId: UUID,
    ) {
        users.find(dsl, userId) ?: throw UserNotFound(userId)
    }
}
