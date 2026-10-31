package idp.sharedkernel.migration

import idp.sharedkernel.testing.TestDatabase
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import kotlin.io.path.listDirectoryEntries

private const val V1 = "classpath:fixtures/migrations/v1"
private const val V2 = "classpath:fixtures/migrations/v2"

class TenantMigrationRunnerTest :
    FunSpec({
        fun newTenant(
            owner: DataSource,
            id: UUID = UUID.randomUUID(),
        ): TenantSchema {
            owner.update("INSERT INTO platform.tenants (id, name, status) VALUES ('$id', 'tenant', 'PROVISIONING')")
            return TenantSchemaFactory(owner).create(id)
        }

        fun DataSource.applied(schema: String) =
            query(
                """SELECT count(*) FROM "$schema".flyway_schema_history WHERE success AND version IS NOT NULL""",
            ).toInt()

        test("migrates platform first and then every tenant schema") {
            val owner = TestDatabase.create().owner()
            val runner = TenantMigrationRunner(owner)
            runner.migrateAll()
            val first = newTenant(owner)
            val second = newTenant(owner)

            runner.migrateAll()

            owner.applied("platform") shouldBe migrationFiles("platform")
            owner.applied(first.name) shouldBe migrationFiles("tenant")
            owner.applied(second.name) shouldBe migrationFiles("tenant")
        }

        test("a tenant without a schema is skipped") {
            val owner = TestDatabase.create().owner()
            val runner = TenantMigrationRunner(owner)
            runner.migrateAll()
            owner.update("INSERT INTO platform.tenants (id, name, status) VALUES ('${UUID.randomUUID()}', 'new', 'REQUESTED')")

            runner.migrateAll()

            runner.drift().shouldBeEmpty()
        }

        test("a failure on one tenant stops the rollout") {
            val owner = TestDatabase.create().owner()
            TenantMigrationRunner(owner).migrateAll()
            val ids = List(3) { UUID.randomUUID() }.sortedBy { TenantSchema(it).name }
            val (first, broken, last) = ids.map { newTenant(owner, it) }
            owner.update("""CREATE TABLE "${broken.name}".fixture (id int)""")

            val failure =
                shouldThrow<TenantMigrationFailed> {
                    TenantMigrationRunner(owner, parallelism = 1, tenantLocations = listOf(V1)).migrateAll()
                }

            failure.schema shouldBe broken.name
            owner.applied(first.name) shouldBe 1
            owner.exists(last.name, "flyway_schema_history") shouldBe false
        }

        test("reports schemas that lag behind the migrations") {
            val owner = TestDatabase.create().owner()
            TenantMigrationRunner(owner).migrateAll()
            val current = newTenant(owner)
            val behind = newTenant(owner)
            val latest = TenantMigrationRunner(owner, tenantLocations = listOf(V1, V2))
            TenantMigrationRunner(owner, tenantLocations = listOf(V1)).migrateTenant(behind.id)
            latest.migrateTenant(current.id)

            latest.drift() shouldContainExactly listOf(SchemaDrift(behind.name, current = "1", expected = "2"))
        }

        test("verify fails while a schema lags behind") {
            val owner = TestDatabase.create().owner()
            TenantMigrationRunner(owner).migrateAll()
            val tenant = newTenant(owner)
            val latest = TenantMigrationRunner(owner, tenantLocations = listOf(V1, V2))
            TenantMigrationRunner(owner, tenantLocations = listOf(V1)).migrateTenant(tenant.id)

            shouldThrow<IllegalStateException> { latest.verify() }.message shouldContain tenant.name

            latest.migrateTenant(tenant.id)
            latest.verify()
        }

        test("the migrator and tenant creation do not run at the same time") {
            val owner = TestDatabase.create().owner()
            TenantMigrationRunner(owner).migrateAll()
            val id = UUID.randomUUID()
            owner.update("INSERT INTO platform.tenants (id, name, status) VALUES ('$id', 'tenant', 'PROVISIONING')")
            val entered = CompletableFuture<Unit>()
            val release = CompletableFuture<Unit>()
            val migration =
                CompletableFuture.runAsync {
                    SchemaLock(owner).exclusive {
                        entered.complete(Unit)
                        release.get(30, TimeUnit.SECONDS)
                    }
                }
            entered.get(10, TimeUnit.SECONDS)

            val creation = CompletableFuture.supplyAsync { TenantSchemaFactory(owner).create(id) }

            shouldThrow<java.util.concurrent.TimeoutException> { creation.get(1, TimeUnit.SECONDS) }
            release.complete(Unit)
            migration.get(10, TimeUnit.SECONDS)
            creation.get(10, TimeUnit.SECONDS).id shouldBe id
        }

        test("tenant creations do not block each other") {
            val owner = TestDatabase.create().owner()
            TenantMigrationRunner(owner).migrateAll()
            val id = UUID.randomUUID()

            SchemaLock(owner).shared {
                val creation = CompletableFuture.supplyAsync { TenantSchemaFactory(owner).create(id) }
                creation.get(10, TimeUnit.SECONDS).id shouldBe id
            }
        }

        test("the migrator waits for a running tenant creation") {
            val owner = TestDatabase.create().owner()
            val runner = TenantMigrationRunner(owner)
            runner.migrateAll()
            val entered = CompletableFuture<Unit>()
            val release = CompletableFuture<Unit>()
            val creation =
                CompletableFuture.runAsync {
                    SchemaLock(owner).shared {
                        entered.complete(Unit)
                        release.get(30, TimeUnit.SECONDS)
                    }
                }
            entered.get(10, TimeUnit.SECONDS)

            val migration = CompletableFuture.runAsync { runner.migrateAll() }

            shouldThrow<java.util.concurrent.TimeoutException> { migration.get(1, TimeUnit.SECONDS) }
            release.complete(Unit)
            creation.get(10, TimeUnit.SECONDS)
            migration.get(30, TimeUnit.SECONDS)
        }

        test("a broken platform migration stops before any tenant") {
            val owner = TestDatabase.create().owner()
            TenantMigrationRunner(owner).migrateAll()
            val tenant = newTenant(owner)

            shouldThrow<Exception> {
                TenantMigrationRunner(
                    owner,
                    platformLocations = listOf("classpath:fixtures/migrations/broken"),
                ).migrateAll()
            }

            owner.exists(tenant.name, "flyway_schema_history") shouldBe false
        }
    })

fun migrationFiles(area: String): Int = Path.of("../db/src/main/resources/db/$area").listDirectoryEntries("V*.sql").size

fun DataSource.update(sql: String) {
    connection.use { it.createStatement().use { statement -> statement.execute(sql) } }
}

fun DataSource.query(sql: String): String =
    connection.use {
        it.createStatement().use { statement ->
            statement.executeQuery(sql).use { rows ->
                rows.next()
                rows.getString(1)
            }
        }
    }

fun DataSource.exists(
    schema: String,
    table: String,
): Boolean = query("SELECT to_regclass('\"$schema\".\"$table\"') IS NOT NULL") == "t"
