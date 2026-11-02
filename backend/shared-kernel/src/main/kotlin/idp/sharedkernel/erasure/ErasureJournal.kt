package idp.sharedkernel.erasure

import java.util.UUID

/** IDs alone: the journal outlives every backup, and a name in it would outlive the erasure it records. */
sealed interface Erasure {
    val tenantId: UUID

    data class Tenant(
        override val tenantId: UUID,
    ) : Erasure

    data class User(
        override val tenantId: UUID,
        val userId: UUID,
    ) : Erasure
}

class ErasureJournalUnavailableException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * Lives outside the database, so restoring a backup does not roll it back. Only appends: the storage keeps an entry
 * even from the code that wrote it.
 */
interface ErasureJournal {
    /** Repeating it is harmless, because an outbox event can be delivered twice. */
    fun record(erasure: Erasure)

    /** Every recorded erasure once, in no fixed order. */
    fun entries(): Set<Erasure>
}

internal fun Erasure.key(): String =
    when (this) {
        is Erasure.Tenant -> "tenants/$tenantId"
        is Erasure.User -> "users/$tenantId/$userId"
    }

/** Parses a key back and refuses anything else, so a stray object in the storage cannot pass for an erasure. */
internal fun erasureOf(key: String): Erasure {
    val parts = key.split('/')
    val erasure =
        try {
            when {
                parts.size == 2 && parts[0] == "tenants" -> Erasure.Tenant(UUID.fromString(parts[1]))
                parts.size == 3 && parts[0] == "users" -> Erasure.User(UUID.fromString(parts[1]), UUID.fromString(parts[2]))
                else -> null
            }
        } catch (_: IllegalArgumentException) {
            null
        }
    check(erasure?.key() == key) { "Not an erasure: $key" }
    return erasure
}
