package idp.audit.application

import idp.audit.adapters.persistence.AuditEntries
import idp.audit.domain.AuditEntry
import idp.audit.domain.keyOwner
import idp.sharedkernel.audit.Actor
import idp.sharedkernel.audit.AuditLog
import idp.sharedkernel.audit.AuditRecord
import idp.sharedkernel.audit.AuditTarget
import idp.sharedkernel.crypto.DerivedKey
import idp.sharedkernel.crypto.FieldCipher
import idp.sharedkernel.crypto.KeyHierarchy
import idp.sharedkernel.tenant.TenantTransaction
import org.jooq.DSLContext
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** `ip` is null without an address and once the actor's key is gone, `details` only in the second case. */
data class AuditEntryView(
    val id: UUID,
    val occurredAt: Instant,
    val action: String,
    val actor: Actor,
    val target: AuditTarget?,
    val ip: String?,
    val details: Map<String, String>?,
)

data class AuditCursor(
    val occurredAt: Instant,
    val id: UUID,
)

class AuditService(
    private val transaction: TenantTransaction,
    private val keys: KeyHierarchy,
    private val clock: Clock = Clock.systemUTC(),
) : AuditLog {
    private val entries = AuditEntries()

    override fun recordChange(record: AuditRecord) {
        transaction.join { dsl -> add(dsl, record) }
    }

    override fun recordDenial(record: AuditRecord) {
        transaction.executeSeparately { dsl -> add(dsl, record) }
    }

    /** One erased actor must not fail the journal, so a row without its key reads as a bare fact. */
    fun read(
        limit: Int,
        before: AuditCursor? = null,
    ): List<AuditEntryView> =
        transaction.execute { dsl ->
            val page = entries.page(dsl, limit, before)
            val derived = keys.derivedCipher(DerivedKey.AUDIT)
            val ciphers = keys.userCiphers(page.mapNotNull { it.keyOwner })
            page.map { entry ->
                val owner = entry.keyOwner
                val cipher = if (owner == null) derived else ciphers[owner]
                AuditEntryView(
                    entry.id,
                    entry.occurredAt,
                    entry.action,
                    entry.actor,
                    entry.target,
                    entry.ip(cipher),
                    entry.details(cipher),
                )
            }
        }

    private fun add(
        dsl: DSLContext,
        record: AuditRecord,
    ) {
        entries.add(dsl, AuditEntry.sealed(record, UUID.randomUUID(), clock.instant(), cipherFor(record.actor)))
    }

    private fun cipherFor(actor: Actor): FieldCipher? =
        when (val owner = actor.keyOwner()) {
            null -> keys.derivedCipher(DerivedKey.AUDIT)
            else -> keys.userCiphers(listOf(owner))[owner]
        }
}
