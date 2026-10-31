package idp.sharedkernel.migration

import org.flywaydb.core.Flyway
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import javax.sql.DataSource

data class SchemaDrift(
    val schema: String,
    val current: String?,
    val expected: String?,
)

class TenantMigrationFailed(
    val schema: String,
    cause: Throwable,
) : RuntimeException("Migration of $schema failed", cause)

/** Needs a direct connection: migrations do not go through PgBouncer. */
class TenantMigrationRunner(
    private val dataSource: DataSource,
    private val parallelism: Int = 4,
    private val platformLocations: List<String> = listOf("classpath:db/platform"),
    private val tenantLocations: List<String> = listOf("classpath:db/tenant"),
) {
    private val lock = SchemaLock(dataSource)

    fun migrateAll() {
        lock.exclusive {
            flyway("platform", platformLocations).migrate()
            migrate(tenantSchemas())
            verify()
        }
    }

    fun migrateTenant(tenantId: UUID) {
        lock.shared { tenantFlyway(TenantSchema(tenantId).name).migrate() }
    }

    fun drift(): List<SchemaDrift> =
        tenantSchemas().mapNotNull { schema ->
            val info = tenantFlyway(schema).info()
            val current = info.current()?.version
            val expected = info.all().mapNotNull { it.version }.maxOrNull()
            if (current == expected) null else SchemaDrift(schema, current?.version, expected?.version)
        }

    fun verify() {
        val drift = drift()
        check(drift.isEmpty()) { "Tenant schemas lag behind the migrations: $drift" }
    }

    private fun migrate(schemas: List<String>) {
        val failure = AtomicReference<TenantMigrationFailed>()
        Executors.newFixedThreadPool(parallelism).use { pool ->
            schemas.forEach { schema ->
                pool.execute {
                    if (failure.get() == null) {
                        runCatching { tenantFlyway(schema).migrate() }
                            .onFailure { failure.compareAndSet(null, TenantMigrationFailed(schema, it)) }
                    }
                }
            }
        }
        failure.get()?.let { throw it }
    }

    private fun tenantSchemas(): List<String> =
        dataSource.connection.use { connection ->
            val existing = connection.strings("SELECT nspname FROM pg_namespace").toSet()
            connection
                .strings("SELECT id FROM platform.tenants")
                .map { TenantSchema(UUID.fromString(it)).name }
                .filter { it in existing }
                .sorted()
        }

    private fun java.sql.Connection.strings(sql: String): List<String> =
        createStatement().use { statement ->
            statement.executeQuery(sql).use { rows ->
                generateSequence { if (rows.next()) rows.getString(1) else null }.toList()
            }
        }

    private fun tenantFlyway(schema: String) = flyway(schema, tenantLocations, createSchemas = false)

    private fun flyway(
        schema: String,
        locations: List<String>,
        createSchemas: Boolean = true,
    ) = Flyway
        .configure(javaClass.classLoader)
        .dataSource(dataSource)
        .locations(*locations.toTypedArray())
        .defaultSchema(schema)
        .schemas(schema)
        .createSchemas(createSchemas)
        .load()
}
