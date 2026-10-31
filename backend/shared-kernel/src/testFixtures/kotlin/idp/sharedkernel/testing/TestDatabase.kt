package idp.sharedkernel.testing

import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.MountableFile
import java.sql.DriverManager
import java.util.UUID
import javax.sql.DataSource

/** One Postgres per test JVM. Roles are cluster-wide, so tests isolate themselves with a new database. */
private val postgres: PostgreSQLContainer by lazy {
    PostgreSQLContainer(System.getProperty("idp.postgres.image"))
        .withDatabaseName("idp")
        .withUsername("postgres")
        .withCopyFileToContainer(
            MountableFile.forHostPath(System.getProperty("idp.postgres.init")),
            "/docker-entrypoint-initdb.d/init.sql",
        ).also { it.start() }
}

class TestDatabase private constructor(
    val url: String,
) {
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
