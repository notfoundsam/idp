package idp.sharedkernel.migration

import idp.sharedkernel.testing.TestDatabase
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.postgresql.util.PSQLException
import java.util.UUID
import javax.sql.DataSource

class DatabaseRolesTest :
    FunSpec({
        class Fixture {
            val database = TestDatabase.create()
            val owner = database.owner()
            val tenantId: UUID = UUID.randomUUID()
            val tenant: TenantSchema

            init {
                TenantMigrationRunner(owner).migrateAll()
                owner.update("INSERT INTO platform.tenants (id, name, status) VALUES ('$tenantId', 'tenant', 'PROVISIONING')")
                tenant = TenantSchemaFactory(owner).create(tenantId)
                TenantMigrationRunner(owner).migrateTenant(tenantId)
                owner.update("""CREATE TABLE "${tenant.name}".fixture (id int)""")
            }
        }

        fun denied(block: () -> Unit) {
            shouldThrow<PSQLException>(block).message shouldContain "permission denied"
        }

        fun DataSource.asRole(
            role: String,
            sql: String,
        ) {
            connection.use {
                it.createStatement().use { statement ->
                    statement.execute("""SET ROLE "$role"""")
                    statement.execute(sql)
                }
            }
        }

        test("app_runtime sees no tenant schema without SET ROLE") {
            Fixture().run {
                denied { database.role("app_runtime").query("""SELECT count(*) FROM "${tenant.name}".fixture""") }
            }
        }

        test("worker_runtime sees no tenant schema without SET ROLE") {
            Fixture().run {
                denied { database.role("worker_runtime").query("""SELECT count(*) FROM "${tenant.name}".fixture""") }
            }
        }

        test("app_runtime and worker_runtime enter a tenant through SET ROLE") {
            Fixture().run {
                listOf("app_runtime", "worker_runtime").forEach {
                    database.role(it).asRole(tenant.name, """INSERT INTO "${tenant.name}".fixture VALUES (1)""")
                }
                owner.query("""SELECT count(*) FROM "${tenant.name}".fixture""") shouldBe "2"
            }
        }

        test("ops_runtime cannot enter a tenant") {
            Fixture().run {
                denied { database.role("ops_runtime").asRole(tenant.name, "SELECT 1") }
            }
        }

        test("idp_owner can enter a tenant") {
            Fixture().run {
                owner.asRole(tenant.name, """SELECT count(*) FROM "${tenant.name}".fixture""")
            }
        }

        test("a tenant role cannot alter or drop tables of its schema") {
            Fixture().run {
                val app = database.role("app_runtime")
                shouldThrow<PSQLException> {
                    app.asRole(tenant.name, """ALTER TABLE "${tenant.name}".fixture ADD COLUMN extra int""")
                }.message shouldContain "must be owner"
                shouldThrow<PSQLException> {
                    app.asRole(tenant.name, """DROP TABLE "${tenant.name}".fixture""")
                }.message shouldContain "must be owner"
            }
        }

        test("a tenant role gets exactly DML on a new table") {
            Fixture().run {
                owner.query(
                    """
                    SELECT string_agg(privilege_type, ',' ORDER BY privilege_type)
                    FROM information_schema.role_table_grants
                    WHERE grantee = '${tenant.name}' AND table_schema = '${tenant.name}' AND table_name = 'fixture'
                    """,
                ) shouldBe "DELETE,INSERT,SELECT,UPDATE"
            }
        }

        test("a tenant role cannot create tables or touch the migration history") {
            Fixture().run {
                val app = database.role("app_runtime")
                denied { app.asRole(tenant.name, """CREATE TABLE "${tenant.name}".extra (id int)""") }
                denied { app.asRole(tenant.name, """SELECT * FROM "${tenant.name}".flyway_schema_history""") }
            }
        }

        test("a tenant role cannot reach another tenant") {
            Fixture().run {
                val other = UUID.randomUUID()
                val otherSchema = TenantSchemaFactory(owner).create(other)
                TenantMigrationRunner(owner).migrateTenant(other)
                owner.update("""CREATE TABLE "${otherSchema.name}".fixture (id int)""")
                denied {
                    database.role("app_runtime").asRole(tenant.name, """SELECT * FROM "${otherSchema.name}".fixture""")
                }
            }
        }

        test("a tenant role only inserts into the outbox") {
            Fixture().run {
                val app = database.role("app_runtime")
                val event =
                    """
                    INSERT INTO platform.outbox (id, tenant_id, aggregate_type, aggregate_id, aggregate_version, event_type, correlation_id)
                    VALUES (gen_random_uuid(), '$tenantId', 'tenant', '$tenantId', 1, 'created', gen_random_uuid())
                    """
                app.asRole(tenant.name, event)
                denied { app.asRole(tenant.name, "SELECT * FROM platform.outbox") }
                denied { app.asRole(tenant.name, "SELECT * FROM platform.tenants") }
            }
        }

        test("app_runtime reads tenants but not applications") {
            Fixture().run {
                val app = database.role("app_runtime")
                app.query("SELECT count(*) FROM platform.tenants") shouldBe "1"
                app.query("SELECT count(*) FROM platform.tenant_domains") shouldBe "0"
                denied { app.query("SELECT count(*) FROM platform.tenant_applications") }
                denied { app.query("SELECT count(*) FROM platform.operator_audit") }
                denied { app.update("UPDATE platform.tenants SET name = 'x'") }
                denied { app.update("CREATE TABLE platform.extra (id int)") }
            }
        }

        test("ops_runtime stays inside platform") {
            Fixture().run {
                val ops = database.role("ops_runtime")
                ops.query("SELECT count(*) FROM platform.tenant_applications") shouldBe "0"
                ops.update("INSERT INTO platform.operator_audit (actor, action) VALUES ('operator', 'approved')")
                denied { ops.update("DELETE FROM platform.operator_audit") }
                denied { ops.query("SELECT count(*) FROM platform.outbox") }
                denied { ops.query("""SELECT count(*) FROM "${tenant.name}".fixture""") }
            }
        }

        test("runtime roles cannot use the public schema") {
            Fixture().run {
                denied { database.role("app_runtime").update("CREATE TABLE public.x (id int)") }
                denied { database.role("worker_runtime").query("SELECT count(*) FROM public.x") }
            }
        }

        test("provisioner creates a tenant as idp_owner") {
            Fixture().run {
                val id = UUID.randomUUID()
                val provisioner = database.role("provisioner", options = "-c role=idp_owner")

                val created = TenantSchemaFactory(provisioner).create(id)
                TenantMigrationRunner(provisioner).migrateTenant(id)

                owner.exists(created.name, "flyway_schema_history") shouldBe true
            }
        }

        test("provisioner without SET ROLE has no owner rights") {
            Fixture().run {
                denied { database.role("provisioner").update("""CREATE TABLE "${tenant.name}".x (id int)""") }
            }
        }

        test("creating a tenant twice changes nothing") {
            Fixture().run {
                TenantSchemaFactory(owner).create(tenantId).id shouldBe tenantId
                owner.query("""SELECT count(*) FROM "${tenant.name}".fixture""") shouldBe "0"
            }
        }
    })
