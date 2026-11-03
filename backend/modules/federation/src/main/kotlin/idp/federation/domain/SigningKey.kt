package idp.federation.domain

import idp.sharedkernel.domain.Conflict
import idp.sharedkernel.domain.DomainRuleViolation
import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import java.util.UUID

/** NIST SP 800-57 puts RSA-3072 at 128 bits of security, the level of the rest of the system. */
const val MIN_RSA_BITS = 3072

class WeakSigningKey : DomainRuleViolation("A SAML signing key must be RSA of at least $MIN_RSA_BITS bits")

class KeyRotationInProgress : Conflict("A rotation of the SAML signing key is already in progress")

class NoKeyRotation : Conflict("No rotation of the SAML signing key is in progress")

enum class SigningKeyStatus {
    ACTIVE,
    NEXT,
}

/** Not a data class: `toString` must not print the ciphertext, and two keys are never equal by value. */
internal class SigningKey(
    val id: UUID,
    status: SigningKeyStatus,
    val certificate: X509Certificate,
    val privateKeyCiphertext: ByteArray,
    val createdAt: Instant,
) {
    var status: SigningKeyStatus = status
        private set

    init {
        val key = certificate.publicKey as? RSAPublicKey
        if (key == null || key.modulus.bitLength() < MIN_RSA_BITS) throw WeakSigningKey()
    }

    fun promote() {
        check(status == SigningKeyStatus.NEXT) { "Only the next key becomes active" }
        status = SigningKeyStatus.ACTIVE
    }

    override fun toString(): String = "SigningKey($id, $status)"
}

/**
 * A rotation publishes the next key beside the active one, and SPs that read metadata learn it before it signs. Completing the
 * rotation retires the active key and lets the next one sign.
 */
internal class SigningKeyRing(
    private val keys: List<SigningKey>,
) {
    val active: SigningKey? get() = keys.firstOrNull { it.status == SigningKeyStatus.ACTIVE }

    val next: SigningKey? get() = keys.firstOrNull { it.status == SigningKeyStatus.NEXT }

    /** The active key first, because some SPs try only the first certificate. */
    val published: List<SigningKey> get() = listOfNotNull(active, next)

    fun ensureRotationCanStart() {
        if (next != null) throw KeyRotationInProgress()
    }

    /** Returns the promoted key. The caller deletes the retired one first, because a status belongs to one key. */
    fun completeRotation(): SigningKey {
        val promoted = next ?: throw NoKeyRotation()
        promoted.promote()
        return promoted
    }
}

internal fun x509(der: ByteArray): X509Certificate =
    CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as X509Certificate
