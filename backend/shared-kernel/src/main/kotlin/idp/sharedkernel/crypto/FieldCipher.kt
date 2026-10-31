package idp.sharedkernel.crypto

import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.aead.AeadConfig2026
import com.google.crypto.tink.aead.KmsEnvelopeAead
import com.google.crypto.tink.aead.PredefinedAeadParameters
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.util.UUID

/** Binds a ciphertext to its place, so a copy in another row or field does not decrypt. */
data class FieldContext(
    val field: String,
    val row: UUID,
) {
    internal fun associatedData(): ByteArray {
        val name = field.toByteArray()
        return ByteBuffer
            .allocate(2 * Long.SIZE_BYTES + name.size)
            .putLong(row.mostSignificantBits)
            .putLong(row.leastSignificantBits)
            .put(name)
            .array()
    }
}

class FieldDecryptionException(
    cause: GeneralSecurityException,
) : RuntimeException("Ciphertext does not belong to this key and place", cause)

/** Tink starts each ciphertext with the key version, so a keyset with a new key still reads old values. */
class FieldCipher internal constructor(
    private val aead: Aead,
) {
    fun encrypt(
        plaintext: ByteArray,
        context: FieldContext,
    ): ByteArray = aead.encrypt(plaintext, context.associatedData())

    fun decrypt(
        ciphertext: ByteArray,
        context: FieldContext,
    ): ByteArray =
        try {
            aead.decrypt(ciphertext, context.associatedData())
        } catch (e: GeneralSecurityException) {
            throw FieldDecryptionException(e)
        }

    companion object {
        internal fun of(keyset: KeysetHandle) = FieldCipher(keyset.getPrimitive(AeadConfig2026.get(), Aead::class.java))

        /** A fresh key per value is wrapped by the KEK, so encrypting needs `Encrypt` alone and `ops` stays without `Decrypt`. */
        fun platform(
            kms: KeyManagementService,
            kekUri: String,
        ) = FieldCipher(KmsEnvelopeAead.create(PredefinedAeadParameters.AES256_GCM, KekAead(kms, kekUri)))
    }
}

private class KekAead(
    private val kms: KeyManagementService,
    private val kekUri: String,
) : Aead {
    override fun encrypt(
        plaintext: ByteArray,
        associatedData: ByteArray,
    ): ByteArray = kms.wrap(kekUri, plaintext)

    override fun decrypt(
        ciphertext: ByteArray,
        associatedData: ByteArray,
    ): ByteArray = kms.unwrap(kekUri, ciphertext)
}
