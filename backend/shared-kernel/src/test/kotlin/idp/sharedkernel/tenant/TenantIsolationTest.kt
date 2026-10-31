package idp.sharedkernel.tenant

import idp.db.jooq.tenant.tables.TenantKeys.TENANT_KEYS
import idp.sharedkernel.migration.TenantMigrationRunner
import idp.sharedkernel.migration.TenantSchema
import idp.sharedkernel.migration.TenantSchemaFactory
import idp.sharedkernel.migration.query
import idp.sharedkernel.migration.update
import idp.sharedkernel.testing.TestDatabase
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.jooq.DSLContext
import org.jooq.exception.DataAccessException
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import java.util.UUID
import javax.sql.DataSource

private class Tenants {
    val database = TestDatabase.create()
    val owner = database.owner()
    val a: UUID = UUID.randomUUID()
    val b: UUID = UUID.randomUUID()

    init {
        TenantMigrationRunner(owner).migrateAll()
        listOf(a, b).forEach { id ->
            owner.update("INSERT INTO platform.tenants (id, name, status) VALUES ('$id', 'tenant', 'ACTIVE')")
            TenantSchemaFactory(owner).create(id)
            TenantMigrationRunner(owner).migrateTenant(id)
            owner.update("""CREATE TABLE "${TenantSchema(id).name}".fixture (id int)""")
        }
    }

    fun <T> inTenant(
        id: UUID,
        block: () -> T,
    ): T = TenantContext.with(TenantContext(id, kekUri = null), block)
}

private fun DSLContext.value(sql: String): String = fetchOne(sql)!!.get(0).toString()

private fun denied(block: () -> Unit) {
    shouldThrow<DataAccessException>(block).message shouldContain "permission denied"
}

class TenantIsolationTest :
    FunSpec({
        test("a tenant reaches its own tables without naming the schema") {
            Tenants().run {
                val transaction = TenantTransaction(database.role("app_runtime"))

                inTenant(a) { transaction.execute { it.execute("INSERT INTO fixture VALUES (1)") } }

                owner.query("""SELECT count(*) FROM "${TenantSchema(a).name}".fixture""") shouldBe "1"
                owner.query("""SELECT count(*) FROM "${TenantSchema(b).name}".fixture""") shouldBe "0"
            }
        }

        test("a generated tenant table works through search_path alone") {
            Tenants().run {
                val transaction = TenantTransaction(database.role("app_runtime"))

                inTenant(a) {
                    transaction.execute {
                        it.render(it.selectFrom(TENANT_KEYS)) shouldContain """from "tenant_keys""""
                        it
                            .insertInto(TENANT_KEYS)
                            .set(TENANT_KEYS.VERSION, 1)
                            .set(TENANT_KEYS.WRAPPED_KEY, ByteArray(1))
                            .execute()
                    }
                }

                inTenant(a) { transaction.execute { it.fetchCount(TENANT_KEYS) } } shouldBe 1
                inTenant(b) { transaction.execute { it.fetchCount(TENANT_KEYS) } } shouldBe 0
            }
        }

        test("a query to another tenant fails in the database") {
            Tenants().run {
                val transaction = TenantTransaction(database.role("app_runtime"))

                denied {
                    inTenant(a) {
                        transaction.execute { it.value("""SELECT count(*) FROM "${TenantSchema(b).name}".fixture""") }
                    }
                }
            }
        }

        test("a transaction runs as the tenant role and sees only the tenant schema") {
            Tenants().run {
                val transaction = TenantTransaction(database.role("app_runtime"))

                inTenant(a) {
                    transaction.execute { it.value("SELECT current_user") } shouldBe TenantSchema(a).name
                    transaction.execute { it.value("SELECT current_setting('search_path')") } shouldBe TenantSchema(a).name
                }
            }
        }

        // Postgres checks SET ROLE against session_user, so injected SQL can leave the tenant. Isolation stops bugs, not SQL execution.
        test("raw SQL inside a transaction can still switch the role") {
            Tenants().run {
                val transaction = TenantTransaction(database.role("app_runtime"))

                inTenant(a) {
                    transaction.execute {
                        it.execute("""SET LOCAL ROLE "${TenantSchema(b).name}"""")
                        it.value("SELECT current_user")
                    } shouldBe TenantSchema(b).name
                }
            }
        }

        test("a tenant role only inserts into the outbox") {
            Tenants().run {
                val transaction = TenantTransaction(database.role("app_runtime"))
                val insert =
                    """
                    INSERT INTO platform.outbox (id, tenant_id, aggregate_type, aggregate_id, aggregate_version, event_type, correlation_id)
                    VALUES (gen_random_uuid(), '$a', 'user', gen_random_uuid(), 1, 'created', gen_random_uuid())
                    """

                inTenant(a) { transaction.execute { it.execute(insert) } }

                owner.query("SELECT count(*) FROM platform.outbox") shouldBe "1"
                listOf(
                    "SELECT count(*) FROM platform.outbox",
                    "UPDATE platform.outbox SET attempts = 1",
                    "DELETE FROM platform.outbox",
                    "SELECT count(*) FROM platform.tenants",
                    "SELECT count(*) FROM platform.tenant_domains",
                ).forEach { sql ->
                    denied { inTenant(a) { transaction.execute { it.execute(sql) } } }
                }
            }
        }

        test("a tenant transaction does not open without a tenant") {
            Tenants().run {
                val transaction = TenantTransaction(database.role("app_runtime"))

                shouldThrow<TenantContextRequired> { transaction.execute { it.value("SELECT 1") } }
            }
        }

        test("a platform transaction reads platform tables only") {
            Tenants().run {
                val transaction = PlatformTransaction(database.role("app_runtime"))

                transaction.execute { it.value("SELECT count(*) FROM tenants") } shouldBe "2"
                transaction.execute { it.value("SELECT current_user") } shouldBe "app_runtime"
                denied {
                    transaction.execute { it.value("""SELECT count(*) FROM "${TenantSchema(a).name}".fixture""") }
                }
            }
        }

        test("a platform transaction ignores the tenant in scope") {
            Tenants().run {
                val transaction = PlatformTransaction(database.role("app_runtime"))

                inTenant(a) {
                    transaction.execute { it.value("SELECT current_user") } shouldBe "app_runtime"
                }
            }
        }

        test("an exception rolls the transaction back") {
            Tenants().run {
                val transaction = TenantTransaction(database.role("app_runtime"))

                shouldThrow<IllegalArgumentException> {
                    inTenant(a) {
                        transaction.execute {
                            it.execute("INSERT INTO fixture VALUES (1)")
                            throw IllegalArgumentException("stop")
                        }
                    }
                }

                owner.query("""SELECT count(*) FROM "${TenantSchema(a).name}".fixture""") shouldBe "0"
            }
        }

        test("a nested transaction of the same tenant joins the outer one") {
            Tenants().run {
                val transaction = TenantTransaction(database.role("app_runtime"))

                shouldThrow<IllegalArgumentException> {
                    inTenant(a) {
                        transaction.execute {
                            transaction.execute { it.execute("INSERT INTO fixture VALUES (1)") }
                            throw IllegalArgumentException("stop")
                        }
                    }
                }

                owner.query("""SELECT count(*) FROM "${TenantSchema(a).name}".fixture""") shouldBe "0"
            }
        }

        test("a transaction cannot change the tenant or leave it") {
            Tenants().run {
                val dataSource = database.role("app_runtime")
                val tenant = TenantTransaction(dataSource)
                val platform = PlatformTransaction(dataSource)

                inTenant(a) {
                    shouldThrow<IllegalStateException> {
                        tenant.execute { inTenant(b) { tenant.execute { it.value("SELECT 1") } } }
                    }
                    shouldThrow<IllegalStateException> {
                        tenant.execute { platform.execute { it.value("SELECT 1") } }
                    }
                }
                shouldThrow<IllegalStateException> {
                    platform.execute { inTenant(a) { tenant.execute { it.value("SELECT 1") } } }
                }
            }
        }

        context("through PgBouncer") {
            // One client like a Hikari connection: PgBouncer restores search_path for a new client and would hide a leak.
            fun DataSource.oneClient() = SingleConnectionDataSource(connection, true)

            fun DataSource.backend(): Pair<String, String> =
                connection.use { connection ->
                    connection.createStatement().use { statement ->
                        statement.executeQuery("SELECT pg_backend_pid(), current_user").use { rows ->
                            rows.next()
                            rows.getString(1) to rows.getString(2)
                        }
                    }
                }

            fun DataSource.searchPath(): String =
                connection.use { connection ->
                    connection.createStatement().use { statement ->
                        statement.executeQuery("SELECT current_setting('search_path')").use { rows ->
                            rows.next()
                            rows.getString(1)
                        }
                    }
                }

            test("the role and search_path do not survive a commit on the same server connection") {
                Tenants().run {
                    val pooled = database.pooled("app_runtime").oneClient()
                    val transaction = TenantTransaction(pooled)

                    val insideTenant =
                        inTenant(a) {
                            transaction.execute { it.value("SELECT pg_backend_pid()") to it.value("SELECT current_user") }
                        }
                    val (pid, user) = pooled.backend()

                    pid shouldBe insideTenant.first
                    insideTenant.second shouldBe TenantSchema(a).name
                    user shouldBe "app_runtime"
                    pooled.searchPath() shouldNotContain "tenant_"
                }
            }

            test("the role and search_path do not survive a rollback on the same server connection") {
                Tenants().run {
                    val pooled = database.pooled("app_runtime").oneClient()
                    val transaction = TenantTransaction(pooled)
                    val pids = mutableListOf<String>()

                    shouldThrow<IllegalArgumentException> {
                        inTenant(a) {
                            transaction.execute {
                                pids += it.value("SELECT pg_backend_pid()")
                                throw IllegalArgumentException("stop")
                            }
                        }
                    }
                    val (pid, user) = pooled.backend()

                    pid shouldBe pids.single()
                    user shouldBe "app_runtime"
                    pooled.searchPath() shouldNotContain "tenant_"
                }
            }

            test("the next tenant on the same server connection sees only itself") {
                Tenants().run {
                    val transaction = TenantTransaction(database.pooled("app_runtime"))

                    val first = inTenant(a) { transaction.execute { it.value("SELECT pg_backend_pid()") } }
                    denied {
                        inTenant(b) {
                            transaction.execute {
                                it.value("SELECT pg_backend_pid()") shouldBe first
                                it.value("""SELECT count(*) FROM "${TenantSchema(a).name}".fixture""")
                            }
                        }
                    }
                }
            }

            test("repeated statements work without server-side prepared statements") {
                Tenants().run {
                    val transaction = TenantTransaction(database.pooled("app_runtime"))

                    val counts =
                        (1..20).map {
                            inTenant(a) { transaction.execute { it.fetch("SELECT count(*) FROM fixture WHERE id = ?", 1) } }
                        }

                    counts shouldHaveSize 20
                }
            }
        }
    })
