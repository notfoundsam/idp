package idp.sharedkernel.migration

import idp.sharedkernel.testing.TestDatabase
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.flywaydb.core.Flyway
import java.sql.SQLException
import java.util.UUID
import javax.sql.DataSource

private const val BEFORE_BOUNDS = "15"

class PolicyBoundsMigrationTest :
    FunSpec({
        val owner = TestDatabase.create().owner()
        TenantMigrationRunner(owner).migrateAll()

        fun flyway(
            schema: String,
            target: String? = null,
        ) = Flyway
            .configure(javaClass.classLoader)
            .dataSource(owner)
            .locations("classpath:db/tenant")
            .defaultSchema(schema)
            .schemas(schema)
            .createSchemas(false)
            .apply { target?.let(::target) }
            .load()

        /** A tenant whose policy was stored before the bounds, with [idle] and [absolute] in seconds. */
        fun storedBefore(
            idle: Long,
            absolute: Long,
        ): String {
            val id = UUID.randomUUID()
            owner.update("INSERT INTO platform.tenants (id, name, status) VALUES ('$id', 'tenant', 'PROVISIONING')")
            val schema = TenantSchemaFactory(owner).create(id).name
            flyway(schema, BEFORE_BOUNDS).migrate()
            owner.update(
                """UPDATE "$schema".tenant_policies SET session_idle_seconds = $idle, session_absolute_seconds = $absolute""",
            )
            flyway(schema).migrate()
            return schema
        }

        fun DataSource.policy(schema: String) =
            query(
                """SELECT session_idle_seconds || '/' || session_absolute_seconds || '/' || version FROM "$schema".tenant_policies""",
            )

        test("a stored policy outside the bounds moves to the nearest one, and its version moves too") {
            owner.policy(storedBefore(idle = 60, absolute = 100_000)) shouldBe "300/86400/1"
            owner.policy(storedBefore(idle = 7_200, absolute = 7_200)) shouldBe "3600/7200/1"
            owner.policy(storedBefore(idle = 60, absolute = 120)) shouldBe "300/300/1"
        }

        test("a policy inside the bounds stays as it was") {
            owner.policy(storedBefore(idle = 1_800, absolute = 43_200)) shouldBe "1800/43200/0"
        }

        test("the database refuses a policy outside the bounds from then on") {
            val schema = storedBefore(idle = 1_800, absolute = 43_200)

            listOf(
                "session_idle_seconds = 299",
                "session_idle_seconds = 3601, session_absolute_seconds = 7200",
                "session_absolute_seconds = 86401",
                "session_absolute_seconds = 1000",
            ).forEach { change ->
                val failure = shouldThrow<SQLException> { owner.update("""UPDATE "$schema".tenant_policies SET $change""") }
                failure.message shouldContain "_in_bounds"
            }
            owner.policy(schema) shouldBe "1800/43200/0"
        }
    })
