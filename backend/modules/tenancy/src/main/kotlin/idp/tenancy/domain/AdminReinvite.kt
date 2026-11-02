package idp.tenancy.domain

import idp.sharedkernel.crypto.FieldCipher
import idp.sharedkernel.crypto.FieldContext
import java.util.UUID

private const val CONTACT_FIELD = "admin_reinvites.contact_ciphertext"

/** An address on its way from `ops`, which cannot open it, to `worker`, which holds it only until the invitation exists. */
internal class AdminReinvite(
    val id: UUID,
    val tenantId: UUID,
    val contactCiphertext: ByteArray,
) {
    fun contact(cipher: FieldCipher): String = cipher.decrypt(contactCiphertext, FieldContext(CONTACT_FIELD, id)).decodeToString()

    companion object {
        fun requested(
            id: UUID,
            tenantId: UUID,
            contact: String,
            cipher: FieldCipher,
        ): AdminReinvite {
            requireContact(contact)
            return AdminReinvite(id, tenantId, cipher.encrypt(contact.toByteArray(), FieldContext(CONTACT_FIELD, id)))
        }
    }
}
