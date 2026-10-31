package idp.audit.domain

import idp.sharedkernel.audit.Actor
import idp.sharedkernel.audit.AuditRecord
import idp.sharedkernel.audit.AuditTarget
import idp.sharedkernel.crypto.FieldCipher
import idp.sharedkernel.crypto.FieldContext
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID

private const val IP_FIELD = "audit_entries.ip"
private const val DETAILS_FIELD = "audit_entries.details"

private val json = JsonMapper()

/** Who decides the key: erasing a user destroys what they left, and never what someone did to them. */
internal fun Actor.keyOwner(): UUID? = (this as? Actor.User)?.id

/** A null cipher means the actor's key is gone. Falling back to a tenant key would keep what the erasure must destroy. */
internal class AuditEntry(
    val id: UUID,
    val occurredAt: Instant,
    val action: String,
    val actor: Actor,
    val target: AuditTarget?,
    val ipCiphertext: ByteArray?,
    val detailsCiphertext: ByteArray?,
) {
    val keyOwner: UUID? get() = actor.keyOwner()

    fun ip(cipher: FieldCipher?): String? {
        if (cipher == null || ipCiphertext == null) return null
        return cipher.decrypt(ipCiphertext, FieldContext(IP_FIELD, id)).decodeToString()
    }

    fun details(cipher: FieldCipher?): Map<String, String>? {
        if (cipher == null || detailsCiphertext == null) return null
        val plaintext = cipher.decrypt(detailsCiphertext, FieldContext(DETAILS_FIELD, id))
        return json.readValue(plaintext, object : TypeReference<Map<String, String>>() {})
    }

    companion object {
        fun sealed(
            record: AuditRecord,
            id: UUID,
            occurredAt: Instant,
            cipher: FieldCipher?,
        ) = AuditEntry(
            id = id,
            occurredAt = occurredAt,
            action = record.action,
            actor = record.actor,
            target = record.target,
            ipCiphertext = record.ip?.let { cipher?.encrypt(it.toByteArray(), FieldContext(IP_FIELD, id)) },
            detailsCiphertext = cipher?.encrypt(json.writeValueAsBytes(record.details), FieldContext(DETAILS_FIELD, id)),
        )
    }
}
