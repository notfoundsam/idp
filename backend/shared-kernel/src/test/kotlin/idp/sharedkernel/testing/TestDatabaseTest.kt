package idp.sharedkernel.testing

import idp.sharedkernel.migration.TenantMigrationRunner
import idp.sharedkernel.migration.TenantSchema
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID

private const val TENANTS_OVER_THE_POOL = 32

private fun JdbcTemplate.strings(sql: String): List<String> = queryForList(sql, String::class.java).filterNotNull()

private fun JdbcTemplate.migrations(schema: String): List<String> =
    strings("SELECT version FROM \"$schema\".flyway_schema_history WHERE success AND version IS NOT NULL")

private fun JdbcTemplate.columns(schema: String): List<String> =
    strings(
        """
        SELECT table_name || '.' || column_name || ':' || data_type
        FROM information_schema.columns
        WHERE table_schema = '$schema'
        """,
    )

class TestDatabaseTest :
    FunSpec({
        test("a clone holds what the migrations build on an empty database") {
            val fresh = TestDatabase.create()
            val owner = fresh.owner()
            TenantMigrationRunner(owner).migrateAll()
            val freshTenant = fresh.tenantSchema()
            val migrated = TestDatabase.migrated()
            val tenant = migrated.tenantSchema()
            val expected = JdbcTemplate(owner)
            val clone = JdbcTemplate(migrated.owner())

            clone.migrations("platform") shouldContainExactlyInAnyOrderOf expected.migrations("platform")
            clone.columns("platform") shouldContainExactlyInAnyOrderOf expected.columns("platform")
            clone.migrations(TenantSchema(tenant).name) shouldContainExactlyInAnyOrderOf
                expected.migrations(TenantSchema(freshTenant).name)
            clone.columns(TenantSchema(tenant).name) shouldContainExactlyInAnyOrderOf
                expected.columns(TenantSchema(freshTenant).name)
        }

        test("clones share nothing") {
            val first = TestDatabase.migrated()
            val second = TestDatabase.migrated()
            val tenant = first.tenantSchema()

            JdbcTemplate(first.owner()).update("INSERT INTO platform.tenants (id, name, status) VALUES (?, 'tenant', 'ACTIVE')", tenant)

            second.tenantSchema() shouldBe tenant
            JdbcTemplate(second.owner()).strings("SELECT id::text FROM platform.tenants").shouldBeEmpty()
        }

        test("a pooled schema stays hidden until its tenant has a row") {
            val database = TestDatabase.migrated()
            val owner = database.owner()
            val tenant = database.tenantSchema()
            val runner = TenantMigrationRunner(owner)

            runner.migrateAll()

            JdbcTemplate(owner).strings("SELECT id::text FROM platform.tenants").shouldBeEmpty()
            runner.drift().shouldBeEmpty()
            JdbcTemplate(owner).update("INSERT INTO platform.tenants (id, name, status) VALUES (?, 'tenant', 'ACTIVE')", tenant)
            runner.drift().shouldBeEmpty()
        }

        test("the runtime role reaches a pooled schema through PgBouncer") {
            val database = TestDatabase.migrated()
            val tenant = database.tenantSchema()
            val schema = TenantSchema(tenant).name

            database.pooled("app_runtime").connection.use { connection ->
                connection.autoCommit = false
                connection.createStatement().use { statement ->
                    statement.execute("SET LOCAL ROLE \"$schema\"")
                    statement.executeQuery("SELECT count(*) FROM \"$schema\".users").use { it.next() shouldBe true }
                }
                connection.rollback()
            }
        }

        test("a database hands out each tenant once, and runs out of the pool without failing") {
            val database = TestDatabase.migrated()
            val owner = database.owner()

            val tenants = List(TENANTS_OVER_THE_POOL) { database.tenantSchema() }

            tenants.toSet().size shouldBe TENANTS_OVER_THE_POOL
            val runner = TenantMigrationRunner(owner)
            val sql = JdbcTemplate(owner)
            tenants.forEach { sql.update("INSERT INTO platform.tenants (id, name, status) VALUES (?, 'tenant', 'ACTIVE')", it) }
            runner.drift().shouldBeEmpty()
        }

        test("an empty database gets a schema once the platform is migrated") {
            val database = TestDatabase.create()
            val owner = database.owner()
            TenantMigrationRunner(owner).migrateAll()

            val tenant: UUID = database.tenantSchema()

            JdbcTemplate(owner).strings("SELECT nspname FROM pg_namespace WHERE nspname LIKE 'tenant%'") shouldContainExactlyInAnyOrderOf
                listOf(TenantSchema(tenant).name)
        }
    })

private infix fun List<String>.shouldContainExactlyInAnyOrderOf(expected: List<String>) {
    expected shouldNotBe emptyList<String>()
    this shouldContainExactlyInAnyOrder expected
}
