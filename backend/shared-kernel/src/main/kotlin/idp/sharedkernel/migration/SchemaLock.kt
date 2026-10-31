package idp.sharedkernel.migration

import javax.sql.DataSource

private const val KEY = 7_320_411L

/**
 * Keeps a tenant from being created on an old schema version while the migrator runs.
 * A session-level advisory lock needs a direct connection: PgBouncer in transaction mode would lose it.
 */
class SchemaLock(
    private val dataSource: DataSource,
) {
    fun <T> exclusive(block: () -> T): T = locked("pg_advisory_lock", "pg_advisory_unlock", block)

    fun <T> shared(block: () -> T): T = locked("pg_advisory_lock_shared", "pg_advisory_unlock_shared", block)

    private fun <T> locked(
        lock: String,
        unlock: String,
        block: () -> T,
    ): T =
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("SELECT $lock($KEY)")
                try {
                    block()
                } finally {
                    // A pooled connection stays open after close(), and the lock would stay with it.
                    statement.execute("SELECT $unlock($KEY)")
                }
            }
        }
}
