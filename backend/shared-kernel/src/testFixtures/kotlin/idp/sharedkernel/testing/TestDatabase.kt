package idp.sharedkernel.testing

import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Network
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.MountableFile
import java.sql.DriverManager
import java.util.UUID
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

class TestDatabase private constructor(
    val url: String,
) {
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

    companion object {
        fun create(): TestDatabase {
            val name = "test_" + UUID.randomUUID().toString().replace("-", "")
            superuser(postgres.jdbcUrl) { execute("CREATE DATABASE $name OWNER idp_owner") }
            return TestDatabase(postgres.jdbcUrl.replaceAfterLast('/', name))
        }

        private fun superuser(
            url: String,
            block: java.sql.Statement.() -> Unit,
        ) {
            DriverManager.getConnection(url, postgres.username, postgres.password).use { it.createStatement().use(block) }
        }
    }
}
