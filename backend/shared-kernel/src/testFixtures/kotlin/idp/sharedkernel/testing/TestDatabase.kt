package idp.sharedkernel.testing

import idp.sharedkernel.migration.TenantMigrationRunner
import idp.sharedkernel.migration.TenantSchemaFactory
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Network
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.MountableFile
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import javax.sql.DataSource

/** One Postgres per test JVM. Roles are cluster-wide, so tests isolate themselves with a new database. */
private val network: Network by lazy { Network.newNetwork() }

private val postgres: PostgreSQLContainer by lazy {
    PostgreSQLContainer(System.getProperty("idp.postgres.image"))
        .withDatabaseName("idp")
        .withUsername("postgres")
        .withNetwork(network)
        .withNetworkAliases("postgres")
        // PgBouncer keeps idle connections to every test database for ten minutes, and each test has a database.
        .withCommand("postgres", "-c", "fsync=off", "-c", "max_connections=500")
        .withCopyFileToContainer(
            MountableFile.forHostPath(System.getProperty("idp.postgres.init")),
            "/docker-entrypoint-initdb.d/init.sql",
        ).also { it.start() }
}

private const val PGBOUNCER_PORT = 5432

/** Runs the configuration from dev-tools, so tests exercise what the local environment runs. */
private val pgbouncer: GenericContainer<*> by lazy {
    val config = System.getProperty("idp.pgbouncer.config")
    postgres
    GenericContainer(System.getProperty("idp.pgbouncer.image"))
        .withNetwork(network)
        .withExposedPorts(PGBOUNCER_PORT)
        .withCopyFileToContainer(MountableFile.forHostPath("$config/pgbouncer.ini"), "/etc/pgbouncer/pgbouncer.ini")
        .withCopyFileToContainer(MountableFile.forHostPath("$config/userlist.txt"), "/etc/pgbouncer/userlist.txt")
        .waitingFor(Wait.forListeningPort())
        .also { it.start() }
}

private const val POOLED_TENANTS = 4

class TestDatabase private constructor(
    val url: String,
    tenants: List<UUID> = emptyList(),
) {
    private val pooledTenants = ConcurrentLinkedQueue(tenants)

    /**
     * A migrated tenant schema that `platform.tenants` does not know yet. The caller adds the row.
     *
     * Every clone hands out the same ids, so nothing outside this database may be keyed by them: a test
     * that keeps a tenant's data in OpenBao or Valkey for longer than itself needs its own names there.
     */
    fun tenantSchema(): UUID = pooledTenants.poll() ?: ownTenantSchema()

    /**
     * A migrated tenant schema with an id of its own, for a test that drops the tenant. Its role belongs to the cluster, and
     * Postgres keeps a role that a pooled schema of another clone still grants to.
     */
    fun ownTenantSchema(): UUID =
        UUID.randomUUID().also { id ->
            val owner = owner()
            TenantSchemaFactory(owner).create(id)
            TenantMigrationRunner(owner).migrateTenant(id)
        }

    /** The database is lost, as before a restore: its grants stop holding cluster roles. */
    fun drop() {
        val name = url.substringAfterLast('/')
        superuser(postgres.jdbcUrl) { execute("DROP DATABASE $name WITH (FORCE)") }
    }

    /** Passwords match dev-tools/pgbouncer/userlist.txt, which holds each role under its own name. */
    fun pooledUrl(): String {
        val name = url.substringAfterLast('/')
        return "jdbc:postgresql://${pgbouncer.host}:${pgbouncer.getMappedPort(PGBOUNCER_PORT)}/$name?prepareThreshold=0"
    }

    fun pooled(name: String): DataSource {
        role(name)
        return PGSimpleDataSource().also {
            it.setUrl(pooledUrl())
            it.user = name
            it.password = name
        }
    }

    fun owner(): DataSource = role("idp_owner", password = "idp_owner")

    /**
     * A copy of this database as it is now, which a test restores later like a backup. A template takes no connections, so the
     * ones PgBouncer keeps open go first, and it opens new ones on demand.
     */
    fun backup(): TestDatabase {
        val name = url.substringAfterLast('/')
        superuser(postgres.jdbcUrl) { execute("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '$name'") }
        return database(pooledTenants.toList()) { copy -> "CREATE DATABASE $copy OWNER idp_owner TEMPLATE $name" }
    }

    /** Sets the role password as a superuser, because migrations never do that. */
    fun role(
        name: String,
        password: String = name,
        options: String? = null,
    ): DataSource {
        superuser(url) { execute("""ALTER ROLE "$name" PASSWORD '$password'""") }
        return PGSimpleDataSource().also {
            it.setUrl(url)
            it.user = name
            it.password = password
            it.options = options
        }
    }

    private class Template(
        val name: String,
        val tenants: List<UUID>,
    )

    companion object {
        /** Migrations take most of a test's setup, and a clone costs a tenth of them. Tests of the migrations start from [create]. */
        private val template: Template by lazy {
            val database = create()
            TenantMigrationRunner(database.owner()).migrateAll()
            Template(database.url.substringAfterLast('/'), List(POOLED_TENANTS) { database.tenantSchema() })
        }

        /** An empty database. */
        fun create(): TestDatabase = database { name -> "CREATE DATABASE $name OWNER idp_owner" }

        /** A database with the current migrations, isolated from every other test like one from [create]. */
        fun migrated(): TestDatabase {
            val template = template
            return database(template.tenants) { name -> "CREATE DATABASE $name OWNER idp_owner TEMPLATE ${template.name}" }
        }

        private fun database(
            tenants: List<UUID> = emptyList(),
            statement: (String) -> String,
        ): TestDatabase {
            val name = "test_" + UUID.randomUUID().toString().replace("-", "")
            superuser(postgres.jdbcUrl) { execute(statement(name)) }
            return TestDatabase(postgres.jdbcUrl.replaceAfterLast('/', name), tenants)
        }

        private fun superuser(
            url: String,
            block: java.sql.Statement.() -> Unit,
        ) {
            DriverManager.getConnection(url, postgres.username, postgres.password).use { it.createStatement().use(block) }
        }
    }
}
