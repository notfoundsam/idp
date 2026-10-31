package idp.sharedkernel.crypto

import idp.sharedkernel.crypto.adapter.OpenBaoKeyManagementService
import idp.sharedkernel.testing.TestOpenBao
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

private val CONTACT = "admin@acme.example".toByteArray()

private class PlatformKey {
    private val name = "platform-${UUID.randomUUID()}"
    private val root = OpenBaoKeyManagementService(TestOpenBao.address, TestOpenBao.TOKEN)
    val kekUri = root.createKey(name)

    /** Reading the key is what the adapter does before encrypting, the rest is `Encrypt` of the Key Policy. */
    private val encryptOnly =
        TestOpenBao.tokenWithPolicy(
            "encrypt-$name",
            """
            path "transit/keys/$name" { capabilities = ["read"] }
            path "transit/encrypt/$name" { capabilities = ["update"] }
            """,
        )
    private val decryptOnly =
        TestOpenBao.tokenWithPolicy("decrypt-$name", """path "transit/decrypt/$name" { capabilities = ["update"] }""")

    val ops = FieldCipher.platform(OpenBaoKeyManagementService(TestOpenBao.address, encryptOnly), kekUri)
    val worker = FieldCipher.platform(OpenBaoKeyManagementService(TestOpenBao.address, decryptOnly), kekUri)
}

class PlatformCipherTest :
    FunSpec({
        val contact = FieldContext("tenant_applications.contact_ciphertext", UUID.randomUUID())

        test("ops encrypts and worker decrypts") {
            PlatformKey().run {
                worker.decrypt(ops.encrypt(CONTACT, contact), contact) shouldBe CONTACT
            }
        }

        test("ops cannot decrypt what it encrypted") {
            PlatformKey().run {
                val ciphertext = ops.encrypt(CONTACT, contact)

                shouldThrow<KeyManagementUnavailableException> { ops.decrypt(ciphertext, contact) }
            }
        }

        test("worker cannot encrypt") {
            PlatformKey().run {
                shouldThrow<KeyManagementUnavailableException> { worker.encrypt(CONTACT, contact) }
            }
        }

        test("a ciphertext moved to another application does not decrypt") {
            PlatformKey().run {
                val ciphertext = ops.encrypt(CONTACT, contact)

                shouldThrow<FieldDecryptionException> {
                    worker.decrypt(ciphertext, contact.copy(row = UUID.randomUUID()))
                }
            }
        }

        test("an altered ciphertext does not decrypt") {
            PlatformKey().run {
                val ciphertext = ops.encrypt(CONTACT, contact)
                ciphertext[ciphertext.lastIndex] = (ciphertext.last() + 1).toByte()

                shouldThrow<FieldDecryptionException> { worker.decrypt(ciphertext, contact) }
            }
        }

        test("a ciphertext cut before its wrapped key does not decrypt") {
            PlatformKey().run {
                shouldThrow<FieldDecryptionException> { worker.decrypt(ByteArray(2), contact) }
            }
        }
    })
