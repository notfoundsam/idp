package idp.migrator

import idp.sharedkernel.testing.TestDatabase
import idp.sharedkernel.throttle.RateLimiter
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.context.ApplicationContext
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource
import kotlin.io.path.listDirectoryEntries

private val limiters = AtomicInteger(-1)

private class LimiterCount(
    private val context: ApplicationContext,
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        limiters.set(context.getBeansOfType(RateLimiter::class.java).size)
    }
}

class MigratorApplicationTest :
    StringSpec({
        fun appliedMigrations(
            database: DataSource,
            schema: String,
        ) = database.connection.use {
            it.createStatement().use { statement ->
                statement
                    .executeQuery("""SELECT count(*) FROM "$schema".flyway_schema_history WHERE success AND version IS NOT NULL""")
                    .use { rows ->
                        rows.next()
                        rows.getInt(1)
                    }
            }
        }

        fun migrationFiles(area: String) = Path.of("../db/src/main/resources/db/$area").listDirectoryEntries("V*.sql").size

        "a successful run migrates platform and exits with 0" {
            val database = TestDatabase.create()

            val code =
                runMigrator(
                    "--DB_URL=${database.url}",
                    "--DB_USERNAME=idp_owner",
                    "--DB_PASSWORD=idp_owner",
                )

            code shouldBe 0
            appliedMigrations(database.owner(), "platform") shouldBe migrationFiles("platform")
        }

        "migrator creates no Valkey adapter" {
            val database = TestDatabase.create()

            runMigrator(
                "--DB_URL=${database.url}",
                "--DB_USERNAME=idp_owner",
                "--DB_PASSWORD=idp_owner",
                sources = arrayOf(MigratorApplication::class.java, LimiterCount::class.java),
            ) shouldBe 0

            limiters.get() shouldBe 0
        }

        "a failed run exits with a non-zero code" {
            runMigrator(
                "--DB_URL=jdbc:postgresql://127.0.0.1:1/idp",
                "--DB_USERNAME=idp_owner",
                "--DB_PASSWORD=idp_owner",
                "--spring.datasource.hikari.connection-timeout=250",
            ) shouldBe 1
        }

        "the boot jar finds every migration inside the nested jars" {
            val database = TestDatabase.create()
            val owner = database.owner()
            val migrator = {
                ProcessBuilder(
                    Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-jar",
                    System.getProperty("idp.migrator.jar"),
                ).apply {
                    environment() +=
                        mapOf(
                            "DB_URL" to database.url,
                            "DB_USERNAME" to "idp_owner",
                            "DB_PASSWORD" to "idp_owner",
                        )
                    redirectErrorStream(true)
                    redirectOutput(ProcessBuilder.Redirect.DISCARD)
                }.start()
                    .waitFor()
            }
            migrator() shouldBe 0
            val tenant = UUID.randomUUID()
            val schema = "tenant_" + tenant.toString().replace("-", "")
            owner.connection.use {
                it.createStatement().use { statement ->
                    statement.execute("INSERT INTO platform.tenants (id, name, status) VALUES ('$tenant', 'tenant', 'PROVISIONING')")
                    statement.execute("CREATE ROLE $schema")
                    statement.execute("CREATE SCHEMA $schema")
                }
            }

            migrator() shouldBe 0

            appliedMigrations(owner, "platform") shouldBe migrationFiles("platform")
            appliedMigrations(owner, schema) shouldBe migrationFiles("tenant")
        }
    })
