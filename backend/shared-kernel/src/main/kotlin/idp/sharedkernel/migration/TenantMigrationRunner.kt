package idp.sharedkernel.migration

import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
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

/** Its first line is `-- contract of V<n>: <what goes>`, so the migrator can tell that V<n> must have been released before. */
private val CONTRACT = Regex("""^-- contract of V(\d+): \S""")

/** Old tasks still read the schema during a rollout, so a contract step waits for a run that applied its expand step. */
class ContractWithoutExpand(
    val schema: String,
    val contract: String,
    val expand: String,
) : IllegalStateException("V$contract of $schema removes what V$expand added, and V$expand must be released before it")

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
            val platform = flyway("platform", platformLocations)
            val existing = if (platform.info().applied().isEmpty()) emptyList() else tenantSchemas()
            checkContracts("platform", platform, platformLocations)
            existing.forEach { checkContracts(it, tenantFlyway(it), tenantLocations) }
            platform.migrate()
            migrate(tenantSchemas())
            verify()
        }
    }

    /** Skips the check of contract steps: the schema is new, so no old code reads it, and a retried creation must not get stuck. */
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

    /** A schema without any migration holds no data and no release reads it, so it takes contract steps with their expand steps. */
    private fun checkContracts(
        schema: String,
        flyway: Flyway,
        locations: List<String>,
    ) {
        val info = flyway.info()
        val applied = info.applied().mapNotNull { it.version }.toSet()
        if (applied.isEmpty()) return
        info.pending().forEach { pending ->
            val expand = contractOf(pending.script, locations) ?: return@forEach
            if (MigrationVersion.fromVersion(expand) !in applied) throw ContractWithoutExpand(schema, pending.version.version, expand)
        }
    }

    private fun contractOf(
        script: String,
        locations: List<String>,
    ): String? {
        val resource =
            locations.firstNotNullOfOrNull { javaClass.classLoader.getResource("${it.removePrefix("classpath:")}/$script") } ?: return null
        val first =
            resource
                .openStream()
                .bufferedReader()
                .use { it.readLine() }
                .orEmpty()
        return CONTRACT.find(first)?.groupValues?.get(1)
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
