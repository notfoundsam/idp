package idp.tenancy.domain

import idp.sharedkernel.crypto.FieldCipher
import idp.sharedkernel.crypto.FieldContext
import idp.sharedkernel.domain.DomainRuleViolation
import java.util.UUID

private const val CONTACT_FIELD = "tenant_applications.contact_ciphertext"
private const val MAX_CONTACT_LENGTH = 254
private val EMAIL = Regex("""[^@\s]+@[^@\s]+""")

class InvalidApplication(
    message: String,
) : DomainRuleViolation(message)

internal enum class ApplicationStatus {
    SUBMITTED,
    APPROVED,
}

internal class TenantApplication(
    val id: UUID,
    val tenantId: UUID,
    val companyName: String,
    val subdomain: Subdomain,
    contactCiphertext: ByteArray?,
    contactMask: String?,
    status: ApplicationStatus,
    val version: Long,
) {
    var contactCiphertext = contactCiphertext
        private set
    var contactMask = contactMask
        private set
    var status = status
        private set

    fun approve() {
        if (status != ApplicationStatus.SUBMITTED) throw ForbiddenTenantChange("Application $id is already $status")
        status = ApplicationStatus.APPROVED
    }

    /** Null once the tenant is active. */
    fun contact(cipher: FieldCipher): String? =
        contactCiphertext?.let { cipher.decrypt(it, FieldContext(CONTACT_FIELD, id)).decodeToString() }

    /** The mask goes too: it still names the person and the mailbox of the company. */
    fun eraseContact() {
        contactCiphertext = null
        contactMask = null
    }

    companion object {
        /** The mask is computed here, because `ops` holds `Encrypt` alone and never sees this contact again. */
        fun submitted(
            id: UUID,
            tenantId: UUID,
            companyName: String,
            subdomain: Subdomain,
            contact: String,
            cipher: FieldCipher,
        ): TenantApplication {
            if (contact.length > MAX_CONTACT_LENGTH || !EMAIL.matches(contact)) {
                throw InvalidApplication("A contact must be an email address")
            }
            return TenantApplication(
                id,
                tenantId,
                companyName,
                subdomain,
                cipher.encrypt(contact.toByteArray(), FieldContext(CONTACT_FIELD, id)),
                mask(contact),
                ApplicationStatus.SUBMITTED,
                version = 0,
            )
        }

        private fun mask(contact: String) = "${contact.first()}***@${contact.substringAfter('@')}"
    }
}
