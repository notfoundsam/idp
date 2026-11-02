package idp.migrator

import idp.audit.application.AuditService
import idp.identity.application.IdentityService
import idp.sharedkernel.audit.Actor
import idp.sharedkernel.crypto.KeyHierarchy
import idp.sharedkernel.crypto.KeyNotFoundException
import idp.sharedkernel.crypto.adapter.OpenBaoKeyManagementService
import idp.sharedkernel.erasure.Erasure
import idp.sharedkernel.erasure.adapter.FileErasureJournal
import idp.sharedkernel.migration.TenantSchema
import idp.sharedkernel.migration.TenantSchemaFactory
import idp.sharedkernel.outbox.OutboxPublisher
import idp.sharedkernel.security.UserRole
import idp.sharedkernel.tenant.TenantContext
import idp.sharedkernel.tenant.TenantTransaction
import idp.sharedkernel.testing.TestDatabase
import idp.sharedkernel.testing.TestOpenBao
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.springframework.jdbc.core.JdbcTemplate
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID

private const val BASE_DOMAIN = "idp.test"
private val ADMIN = Actor.User(UUID.randomUUID())

private class Restore {
    val database = TestDatabase.migrated()
    private val sql = JdbcTemplate(database.owner())
    val kms = OpenBaoKeyManagementService(TestOpenBao.address, TestOpenBao.TOKEN)
    private val platformKekUri = kms.createKey("platform-${UUID.randomUUID()}")
    val journalFile: Path = Files.createTempDirectory("erasures").resolve("journal")
    val journal = FileErasureJournal(journalFile)
    private val transaction = TenantTransaction(database.pooled("worker_runtime"))
    private val keys = KeyHierarchy(kms, transaction, Duration.ofMinutes(1))
    private val identity = IdentityService(transaction, keys, AuditService(transaction, keys), OutboxPublisher())

    /** A tenant whose role a test drops needs an id of its own: pooled roles are shared by every clone. */
    fun tenant(own: Boolean = false): Pair<UUID, String> {
        val id = if (own) database.ownTenantSchema() else database.tenantSchema()
        val kek = kms.createKey("tenant-${UUID.randomUUID()}")
        sql.update("INSERT INTO platform.tenants (id, name, status, kek_uri) VALUES (?, 'Acme', 'ACTIVE', ?)", id, kek)
        TenantContext.with(TenantContext(id, kek)) { keys.createTenantKey() }
        return id to kek
    }

    fun invite(
        tenant: Pair<UUID, String>,
        email: String,
        role: UserRole = UserRole.USER,
    ): UUID = TenantContext.with(TenantContext(tenant.first, tenant.second)) { identity.invite(email, role, ADMIN) }

    fun active(
        tenant: Pair<UUID, String>,
        email: String,
        role: UserRole,
    ): UUID =
        TenantContext.with(TenantContext(tenant.first, tenant.second)) {
            val user = identity.invite(email, role, ADMIN)
            identity.acceptInvitation(checkNotNull(identity.issueInvitation(user, ADMIN)), Actor.Anonymous)
        }

    fun erase(
        tenant: Pair<UUID, String>,
        user: UUID,
    ) {
        TenantContext.with(TenantContext(tenant.first, tenant.second)) { identity.eraseUser(user, ADMIN) }
    }

    /** The real application as the release runs it: `idp_owner` straight to Postgres, which enters a tenant with `SET LOCAL ROLE`. */
    fun migrate(
        restored: TestDatabase,
        journal: Path = journalFile,
    ): Int =
        runMigrator(
            "--DB_URL=${restored.url}",
            "--DB_USERNAME=idp_owner",
            "--DB_PASSWORD=idp_owner",
            "--REAPPLY_ERASURES=true",
            "--ERASURE_JOURNAL_FILE=$journal",
            "--KMS_NEW_KEYS=hcvault",
            "--PLATFORM_KEK_URI=$platformKekUri",
            "--OPENBAO_ADDRESS=${TestOpenBao.address}",
            "--OPENBAO_TOKEN=${TestOpenBao.TOKEN}",
            "--TENANTS_BASE_DOMAIN=$BASE_DOMAIN",
        )
}

private fun TestDatabase.sql() = JdbcTemplate(owner())

private fun TestDatabase.users(tenant: UUID): List<UUID?> =
    sql().queryForList("""SELECT id FROM "${TenantSchema(tenant).name}".users""", UUID::class.java)

private fun TestDatabase.userKeys(tenant: UUID): List<UUID?> =
    sql().queryForList("""SELECT user_id FROM "${TenantSchema(tenant).name}".user_keys""", UUID::class.java)

private fun TestDatabase.erasureRecords(tenant: UUID): List<Map<String, Any?>> =
    sql().queryForList(
        """SELECT actor_type, target_id FROM "${TenantSchema(tenant).name}".audit_entries WHERE action = 'user.erased'""",
    )

private fun TestDatabase.status(tenant: UUID): String? =
    sql().queryForObject("SELECT status FROM platform.tenants WHERE id = ?", String::class.java, tenant)

class ReapplyErasuresJobTest :
    FunSpec({
        context("a user") {
            test("erased after the backup is erased again by the real migrator, under its role and the tenant role") {
                Restore().run {
                    val tenant = tenant()
                    val user = invite(tenant, "ada@acme.test")
                    val other = invite(tenant, "grace@acme.test")
                    val backup = database.backup()
                    erase(tenant, user)
                    journal.record(Erasure.User(tenant.first, user))

                    migrate(backup) shouldBe 0

                    backup.users(tenant.first).shouldContainExactly(other)
                    backup.userKeys(tenant.first).shouldContainExactly(other)
                    backup.erasureRecords(tenant.first) shouldBe listOf(mapOf("actor_type" to "SYSTEM", "target_id" to user))
                }
            }

            test("goes even when the backup holds them as the last admin") {
                Restore().run {
                    val tenant = tenant()
                    val admin = active(tenant, "ada@acme.test", UserRole.TENANT_ADMIN)
                    val backup = database.backup()
                    journal.record(Erasure.User(tenant.first, admin))

                    migrate(backup) shouldBe 0

                    backup.users(tenant.first).shouldBeEmpty()
                }
            }

            test("erased before the backup leaves no second audit record") {
                Restore().run {
                    val tenant = tenant()
                    val user = invite(tenant, "ada@acme.test")
                    erase(tenant, user)
                    journal.record(Erasure.User(tenant.first, user))
                    val backup = database.backup()
                    val before = backup.erasureRecords(tenant.first)

                    migrate(backup) shouldBe 0

                    backup.erasureRecords(tenant.first) shouldBe before
                }
            }
        }

        context("a tenant") {
            test("deleted after the backup does not become ACTIVE again, and its schema and role go") {
                Restore().run {
                    val tenant = tenant(own = true)
                    val user = invite(tenant, "ada@acme.test")
                    val backup = database.backup()
                    database.drop()
                    journal.record(Erasure.Tenant(tenant.first))
                    journal.record(Erasure.User(tenant.first, user))

                    migrate(backup) shouldBe 0

                    backup.status(tenant.first) shouldBe "DELETED"
                    TenantSchemaFactory(backup.owner()).exists(tenant.first) shouldBe false
                    backup
                        .sql()
                        .queryForList(
                            "SELECT rolname FROM pg_roles WHERE rolname = ?",
                            String::class.java,
                            TenantSchema(tenant.first).name,
                        ).shouldBeEmpty()
                }
            }

            test("whose KEK survived a crash of worker loses it now") {
                Restore().run {
                    val tenant = tenant(own = true)
                    val backup = database.backup()
                    database.drop()
                    journal.record(Erasure.Tenant(tenant.first))

                    migrate(backup) shouldBe 0

                    shouldThrow<KeyNotFoundException> { kms.wrap(tenant.second, ByteArray(32)) }
                }
            }

            test("that the backup does not know yet stays unknown") {
                Restore().run {
                    val backup = database.backup()
                    journal.record(Erasure.Tenant(UUID.randomUUID()))

                    migrate(backup) shouldBe 0

                    backup.sql().queryForList("SELECT id FROM platform.tenants").shouldBeEmpty()
                }
            }
        }

        context("the journal") {
            test("is not read by a release, which runs without the flag") {
                Restore().run {
                    val tenant = tenant()
                    val user = invite(tenant, "ada@acme.test")
                    journal.record(Erasure.User(tenant.first, user))

                    runMigrator(
                        "--DB_URL=${database.url}",
                        "--DB_USERNAME=idp_owner",
                        "--DB_PASSWORD=idp_owner",
                        "--ERASURE_JOURNAL_FILE=/missing/journal",
                    ) shouldBe 0

                    database.users(tenant.first).shouldContainExactly(user)
                }
            }

            test("that cannot be read fails the run, so app and worker do not start on the restored data") {
                Restore().run {
                    val tenant = tenant()
                    val user = invite(tenant, "ada@acme.test")

                    migrate(database, journalFile.resolveSibling("missing").resolve("journal")) shouldBe 1

                    database.users(tenant.first).shouldContainExactly(user)
                }
            }
        }
    })
