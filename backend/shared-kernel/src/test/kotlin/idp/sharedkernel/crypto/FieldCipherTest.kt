package idp.sharedkernel.crypto

import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.aead.AeadConfig2026
import com.google.crypto.tink.aead.PredefinedAeadParameters
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.nio.ByteBuffer
import java.util.UUID

private const val TINK_PREFIX_SIZE = 5

private val PLAINTEXT = "alice@example.com".toByteArray()

private fun newKeyset() = KeysetHandle.generateNew(PredefinedAeadParameters.AES256_GCM, AeadConfig2026.get())

private fun KeysetHandle.withNewPrimary() =
    KeysetHandle
        .newBuilder(this)
        .addEntry(KeysetHandle.importKey(newKeyset().primary.key).makePrimary())
        .build()

private fun ByteArray.keyVersion() = ByteBuffer.wrap(this, 1, TINK_PREFIX_SIZE - 1).int

class FieldCipherTest :
    FunSpec({
        val row = UUID.randomUUID()
        val email = FieldContext("users.email", row)

        test("a value decrypts in its own field and row") {
            val cipher = FieldCipher.of(newKeyset())

            cipher.decrypt(cipher.encrypt(PLAINTEXT, email), email) shouldBe PLAINTEXT
        }

        test("the same value encrypts differently each time") {
            val cipher = FieldCipher.of(newKeyset())

            cipher.encrypt(PLAINTEXT, email) shouldNotBe cipher.encrypt(PLAINTEXT, email)
        }

        test("a ciphertext moved to another row does not decrypt") {
            val cipher = FieldCipher.of(newKeyset())
            val ciphertext = cipher.encrypt(PLAINTEXT, email)

            shouldThrow<FieldDecryptionException> { cipher.decrypt(ciphertext, email.copy(row = UUID.randomUUID())) }
        }

        test("a ciphertext moved to another field does not decrypt") {
            val cipher = FieldCipher.of(newKeyset())
            val ciphertext = cipher.encrypt(PLAINTEXT, email)

            shouldThrow<FieldDecryptionException> { cipher.decrypt(ciphertext, email.copy(field = "users.phone")) }
        }

        test("another key does not decrypt") {
            val ciphertext = FieldCipher.of(newKeyset()).encrypt(PLAINTEXT, email)

            shouldThrow<FieldDecryptionException> { FieldCipher.of(newKeyset()).decrypt(ciphertext, email) }
        }

        test("an altered ciphertext does not decrypt") {
            val cipher = FieldCipher.of(newKeyset())
            val ciphertext = cipher.encrypt(PLAINTEXT, email)
            ciphertext[ciphertext.lastIndex] = (ciphertext.last() + 1).toByte()

            shouldThrow<FieldDecryptionException> { cipher.decrypt(ciphertext, email) }
        }

        test("a ciphertext shorter than a nonce does not decrypt") {
            shouldThrow<FieldDecryptionException> { FieldCipher.of(newKeyset()).decrypt(ByteArray(3), email) }
        }

        test("a ciphertext names the version of its key") {
            val keyset = newKeyset()

            FieldCipher.of(keyset).encrypt(PLAINTEXT, email).keyVersion() shouldBe keyset.primary.id
        }

        test("a new key version keeps old ciphertexts readable and encrypts with the new one") {
            val old = newKeyset()
            val rotated = old.withNewPrimary()
            val ciphertext = FieldCipher.of(old).encrypt(PLAINTEXT, email)

            FieldCipher.of(rotated).decrypt(ciphertext, email) shouldBe PLAINTEXT
            FieldCipher.of(rotated).encrypt(PLAINTEXT, email).keyVersion() shouldBe rotated.primary.id
        }

        test("a keyset without the version cannot read a newer ciphertext") {
            val old = newKeyset()
            val ciphertext = FieldCipher.of(old.withNewPrimary()).encrypt(PLAINTEXT, email)

            shouldThrow<FieldDecryptionException> { FieldCipher.of(old).decrypt(ciphertext, email) }
        }
    })
