package idp.audit

import idp.audit.application.AuditCursor
import idp.audit.application.AuditEntryView
import idp.audit.application.AuditService
import idp.audit.application.OperatorAuditService
import idp.sharedkernel.audit.Actor
import idp.sharedkernel.audit.AuditRecord
import idp.sharedkernel.audit.AuditTarget
import idp.sharedkernel.crypto.DerivedKey
import idp.sharedkernel.crypto.FieldCipher
import idp.sharedkernel.crypto.FieldContext
import idp.sharedkernel.crypto.FieldDecryptionException
import idp.sharedkernel.crypto.KeyHierarchy
import idp.sharedkernel.crypto.adapter.OpenBaoKeyManagementService
import idp.sharedkernel.migration.TenantMigrationRunner
import idp.sharedkernel.migration.TenantSchema
import idp.sharedkernel.migration.TenantSchemaFactory
import idp.sharedkernel.tenant.PlatformTransaction
import idp.sharedkernel.tenant.TenantContext
import idp.sharedkernel.tenant.TenantContextRequired
import idp.sharedkernel.tenant.TenantTransaction
import idp.sharedkernel.testing.MovableClock
import idp.sharedkernel.testing.TestDatabase
import idp.sharedkernel.testing.TestOpenBao
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.jooq.exception.DataAccessException
import org.postgresql.util.PSQLException
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Clock
import java.time.Duration
import java.time.ZoneOffset
import java.util.UUID

private const val ATTACKER_IP = "203.0.113.7"
private val DETAILS = mapOf("reason" to "wrong password")
private val KEY_CACHE_TTL: Duration = Duration.ofMinutes(1)

private class Audit {
    val database = TestDatabase.create()
    val owner = database.owner()
    val sql = JdbcTemplate(owner)
    val kms = OpenBaoKeyManagementService(TestOpenBao.address, TestOpenBao.TOKEN)

    init {
        TenantMigrationRunner(owner).migrateAll()
    }

    val runtime = database.pooled("app_runtime")
    val transaction = TenantTransaction(runtime)
    val keys = KeyHierarchy(kms, transaction, KEY_CACHE_TTL)
    val clock = MovableClock()
    val audit = AuditService(transaction, keys, clock)

    fun tenant(): TenantContext {
        val id = UUID.randomUUID()
        sql.update("INSERT INTO platform.tenants (id, name, status) VALUES (?, 'tenant', 'ACTIVE')", id)
        TenantSchemaFactory(owner).create(id)
        TenantMigrationRunner(owner).migrateTenant(id)
        val context = TenantContext(id, kms.createKey("tenant-$id"))
        TenantContext.with(context) { keys.createTenantKey() }
        return context
    }

    fun TenantContext.user(): UUID =
        UUID.randomUUID().also { user ->
            sql.update(
                "INSERT INTO ${table("users")} (id, email_index, email_ciphertext, role, status) VALUES (?, ?, '\\x00', 'USER', 'ACTIVE')",
                user,
                user.toString().toByteArray(),
            )
            TenantContext.with(this) { keys.createUserKey(user) }
        }

    fun TenantContext.entries(): List<AuditEntryView> = TenantContext.with(this) { audit.read(limit = 100) }

    fun TenantContext.change(record: AuditRecord) {
        TenantContext.with(this) { transaction.execute { audit.recordChange(record) } }
    }

    fun TenantContext.table(name: String) = "\"${TenantSchema(tenantId).name}\".$name"

    fun TenantContext.erase(user: UUID) {
        sql.update("DELETE FROM ${table("user_keys")} WHERE user_id = ?", user)
    }

    fun TenantContext.ciphertext(
        column: String,
        entry: UUID,
    ): ByteArray = sql.queryForObject("SELECT $column FROM ${table("audit_entries")} WHERE id = ?", ByteArray::class.java, entry)!!

    fun TenantContext.opensWith(
        cipher: FieldCipher,
        entry: UUID,
    ): String {
        val ip = cipher.decrypt(ciphertext("ip_ciphertext", entry), FieldContext("audit_entries.ip", entry))
        val details = cipher.decrypt(ciphertext("details_ciphertext", entry), FieldContext("audit_entries.details", entry))
        details.decodeToString() shouldContain "wrong password"
        return ip.decodeToString()
    }

    fun TenantContext.cipherOf(user: UUID): FieldCipher = TenantContext.with(this) { keys.userCipher(user) }

    fun TenantContext.derivedCipher(): FieldCipher = TenantContext.with(this) { keys.derivedCipher(DerivedKey.AUDIT) }
}

private fun record(
    actor: Actor,
    target: UUID? = null,
    action: String = "login.failed",
) = AuditRecord(actor, action, target?.let(AuditTarget::user), ATTACKER_IP, DETAILS)

class AuditTest :
    FunSpec({
        context("append only") {
            fun Audit.asTenantRole(
                context: TenantContext,
                sql: String,
            ) {
                database.role("app_runtime").connection.use {
                    it.createStatement().use { statement ->
                        statement.execute("SET ROLE \"${TenantSchema(context.tenantId).name}\"")
                        statement.execute(sql)
                    }
                }
            }

            test("a tenant role cannot change or delete entries") {
                Audit().run {
                    val context = tenant()
                    context.change(record(Actor.System))
                    val table = context.table("audit_entries")

                    listOf("UPDATE $table SET action = 'x'", "DELETE FROM $table", "TRUNCATE $table").forEach {
                        shouldThrow<PSQLException> { asTenantRole(context, it) }.message shouldContain "permission denied"
                    }

                    context.entries().single().action shouldBe "login.failed"
                }
            }

            test("a tenant role still adds and reads entries") {
                Audit().run {
                    val context = tenant()

                    context.change(record(Actor.System))
                    context.change(record(Actor.System))

                    context.entries().size shouldBe 2
                }
            }
        }

        context("transactions") {
            test("a change rolls back with its transaction") {
                Audit().run {
                    val context = tenant()

                    shouldThrow<IllegalArgumentException> {
                        TenantContext.with(context) {
                            transaction.execute {
                                audit.recordChange(record(Actor.System))
                                throw IllegalArgumentException("stop")
                            }
                        }
                    }

                    context.entries().shouldBeEmpty()
                }
            }

            test("a change outside a transaction is rejected, because it would survive a rollback") {
                Audit().run {
                    val context = tenant()

                    shouldThrow<IllegalStateException> {
                        TenantContext.with(context) { audit.recordChange(record(Actor.System)) }
                    }

                    context.entries().shouldBeEmpty()
                }
            }

            test("a denial stays after the main transaction rolls back") {
                Audit().run {
                    val context = tenant()

                    shouldThrow<IllegalArgumentException> {
                        TenantContext.with(context) {
                            transaction.execute {
                                audit.recordDenial(record(Actor.Anonymous, action = "access.denied"))
                                throw IllegalArgumentException("stop")
                            }
                        }
                    }

                    context.entries().single().action shouldBe "access.denied"
                }
            }

            test("a denial is written after the main transaction broke on an SQL error") {
                Audit().run {
                    val context = tenant()
                    val user = context.user()

                    shouldThrow<IllegalArgumentException> {
                        TenantContext.with(context) {
                            transaction.execute { dsl ->
                                shouldThrow<DataAccessException> { dsl.execute("SELECT 1 / 0") }
                                audit.recordDenial(record(Actor.User(user), action = "access.denied"))
                                throw IllegalArgumentException("stop")
                            }
                        }
                    }

                    context.entries().single().ip shouldBe ATTACKER_IP
                }
            }

            test("a denial is written with no transaction around it") {
                Audit().run {
                    val context = tenant()

                    TenantContext.with(context) { audit.recordDenial(record(Actor.Anonymous)) }

                    context.entries().size shouldBe 1
                }
            }

            test("the main transaction goes on after a denial") {
                Audit().run {
                    val context = tenant()

                    TenantContext.with(context) {
                        transaction.execute {
                            audit.recordDenial(record(Actor.System, action = "first"))
                            audit.recordChange(record(Actor.System, action = "second"))
                        }
                    }

                    context.entries().map { it.action }.toSet() shouldBe setOf("first", "second")
                }
            }

            test("audit needs a tenant in scope") {
                Audit().run {
                    shouldThrow<TenantContextRequired> { audit.recordDenial(record(Actor.System)) }
                }
            }
        }

        context("keys") {
            test("a user's own action is encrypted with their key, not the key of the target") {
                Audit().run {
                    val context = tenant()
                    val actor = context.user()
                    val target = context.user()

                    context.change(record(Actor.User(actor), target))

                    val entry = context.entries().single().id
                    context.opensWith(context.cipherOf(actor), entry) shouldBe ATTACKER_IP
                    shouldThrow<FieldDecryptionException> { context.opensWith(context.cipherOf(target), entry) }
                    shouldThrow<FieldDecryptionException> { context.opensWith(context.derivedCipher(), entry) }
                }
            }

            test("a failed login is encrypted with the derived key, not the key of the target") {
                Audit().run {
                    val context = tenant()
                    val target = context.user()

                    TenantContext.with(context) { audit.recordDenial(record(Actor.Anonymous, target)) }

                    val entry = context.entries().single().id
                    context.opensWith(context.derivedCipher(), entry) shouldBe ATTACKER_IP
                    shouldThrow<FieldDecryptionException> { context.opensWith(context.cipherOf(target), entry) }
                }
            }

            test("a system action is encrypted with the derived key, not the key of the target") {
                Audit().run {
                    val context = tenant()
                    val target = context.user()

                    context.change(record(Actor.System, target))

                    val entry = context.entries().single().id
                    context.opensWith(context.derivedCipher(), entry) shouldBe ATTACKER_IP
                    shouldThrow<FieldDecryptionException> { context.opensWith(context.cipherOf(target), entry) }
                }
            }

            test("a ciphertext moved between the fields does not open") {
                Audit().run {
                    val context = tenant()
                    context.change(record(Actor.System))
                    val entry = context.entries().single().id

                    shouldThrow<FieldDecryptionException> {
                        context
                            .derivedCipher()
                            .decrypt(context.ciphertext("ip_ciphertext", entry), FieldContext("audit_entries.details", entry))
                    }
                }
            }

            test("the row holds no readable IP or details") {
                Audit().run {
                    val context = tenant()
                    context.change(record(Actor.System))
                    val entry = context.entries().single().id

                    val row = (context.ciphertext("ip_ciphertext", entry) + context.ciphertext("details_ciphertext", entry))

                    row.toString(Charsets.ISO_8859_1).contains(ATTACKER_IP) shouldBe false
                    row.toString(Charsets.ISO_8859_1).contains("wrong password") shouldBe false
                }
            }
        }

        context("erasure") {
            test("after the DEK is gone the fact stays, and IP and details do not read") {
                Audit().run {
                    val context = tenant()
                    val user = context.user()
                    val target = context.user()
                    context.change(record(Actor.User(user), target, action = "password.changed"))

                    context.erase(user)

                    val entry = context.entries().single()
                    entry.action shouldBe "password.changed"
                    entry.actor shouldBe Actor.User(user)
                    entry.target shouldBe AuditTarget.user(target)
                    entry.ip.shouldBeNull()
                    entry.details.shouldBeNull()
                }
            }

            test("reading a mix of erased and living users shows every row") {
                Audit().run {
                    val context = tenant()
                    val erased = context.user()
                    val living = context.user()
                    context.change(record(Actor.User(erased), action = "first"))
                    clock.offset = Duration.ofMinutes(1)
                    context.change(record(Actor.User(living), action = "second"))
                    clock.offset = Duration.ofMinutes(2)
                    context.change(record(Actor.System, action = "third"))

                    context.erase(erased)

                    val entries = context.entries()
                    entries.map { it.action } shouldContainExactly listOf("third", "second", "first")
                    entries.map { it.ip } shouldContainExactly listOf(ATTACKER_IP, ATTACKER_IP, null)
                }
            }

            test("the IP of an unauthenticated attacker stays readable after the victim is erased") {
                Audit().run {
                    val context = tenant()
                    val victim = context.user()
                    TenantContext.with(context) { audit.recordDenial(record(Actor.Anonymous, victim)) }

                    context.erase(victim)

                    val entry = context.entries().single()
                    entry.ip shouldBe ATTACKER_IP
                    entry.details shouldBe DETAILS
                    entry.target shouldBe AuditTarget.user(victim)
                }
            }

            test("the IP of an authenticated attacker stays readable after the victim is erased") {
                Audit().run {
                    val context = tenant()
                    val attacker = context.user()
                    val victim = context.user()
                    context.change(record(Actor.User(attacker), victim))

                    context.erase(victim)

                    context.entries().single().ip shouldBe ATTACKER_IP
                }
            }

            test("an action of an already erased actor keeps the fact and never falls back to the derived key") {
                Audit().run {
                    val context = tenant()
                    val erased = context.user()
                    context.erase(erased)

                    context.change(record(Actor.User(erased), action = "session.used"))

                    val entry = context.entries().single()
                    entry.action shouldBe "session.used"
                    entry.ip.shouldBeNull()
                    entry.details.shouldBeNull()
                    sql.queryForObject(
                        "SELECT count(*) FROM ${context.table("audit_entries")} WHERE ip_ciphertext IS NOT NULL",
                        Int::class.java,
                    ) shouldBe 0
                }
            }
        }

        context("journal") {
            test("the record comes back whole") {
                Audit().run {
                    val context = tenant()
                    val actor = context.user()
                    val target = context.user()

                    context.change(record(Actor.User(actor), target))

                    val entry = context.entries().single()
                    entry.action shouldBe "login.failed"
                    entry.actor shouldBe Actor.User(actor)
                    entry.target shouldBe AuditTarget.user(target)
                    entry.ip shouldBe ATTACKER_IP
                    entry.details shouldBe DETAILS
                }
            }

            test("an entry may have no IP, target or details") {
                Audit().run {
                    val context = tenant()

                    context.change(AuditRecord(Actor.System, "keys.rotated"))

                    val entry = context.entries().single()
                    entry.target.shouldBeNull()
                    entry.ip.shouldBeNull()
                    entry.details shouldBe emptyMap()
                }
            }

            test("the journal goes from the newest entry and pages by a cursor") {
                Audit().run {
                    val context = tenant()
                    listOf("first", "second", "third").forEachIndexed { minutes, action ->
                        clock.offset = Duration.ofMinutes(minutes.toLong())
                        context.change(record(Actor.System, action = action))
                    }

                    val (page, next) =
                        TenantContext.with(context) {
                            val page = audit.read(limit = 2)
                            page to audit.read(limit = 2, before = page.last().let { AuditCursor(it.occurredAt, it.id) })
                        }

                    page.map { it.action } shouldContainExactly listOf("third", "second")
                    next.map { it.action } shouldContainExactly listOf("first")
                }
            }

            test("entries with the same time are neither lost nor repeated between pages") {
                Audit().run {
                    val context = tenant()
                    clock.offset = Duration.ofMinutes(1)
                    val fixed = AuditService(transaction, keys, Clock.fixed(clock.instant(), ZoneOffset.UTC))
                    repeat(5) { TenantContext.with(context) { transaction.execute { fixed.recordChange(record(Actor.System)) } } }

                    val seen = mutableListOf<UUID>()
                    TenantContext.with(context) {
                        var cursor: AuditCursor? = null
                        do {
                            val page = audit.read(limit = 2, before = cursor)
                            seen += page.map { it.id }
                            cursor = page.lastOrNull()?.let { AuditCursor(it.occurredAt, it.id) }
                        } while (page.isNotEmpty())
                    }

                    seen.size shouldBe 5
                    seen.toSet().size shouldBe 5
                }
            }

            test("a tenant sees only its own journal") {
                Audit().run {
                    val a = tenant()
                    val b = tenant()

                    a.change(record(Actor.System))

                    a.entries().size shouldBe 1
                    b.entries().shouldBeEmpty()
                }
            }

            test("an operator does not belong in a tenant journal") {
                Audit().run {
                    val context = tenant()

                    shouldThrow<IllegalArgumentException> { context.change(record(Actor.Operator("alice"))) }

                    context.entries().shouldBeEmpty()
                }
            }
        }

        context("operator") {
            class Operators {
                val database = TestDatabase.create()
                val owner = database.owner().also { TenantMigrationRunner(it).migrateAll() }
                val sql = JdbcTemplate(owner)
                val platform = PlatformTransaction(database.pooled("ops_runtime"))
                val audit = OperatorAuditService(platform)
                val tenantId: UUID = UUID.randomUUID()

                init {
                    sql.update("INSERT INTO platform.tenants (id, name, status) VALUES (?, 'tenant', 'ACTIVE')", tenantId)
                }

                fun rows(): Int = sql.queryForObject("SELECT count(*) FROM platform.operator_audit", Int::class.java)!!

                fun value(column: String): String = sql.queryForObject("SELECT $column FROM platform.operator_audit", String::class.java)!!
            }

            fun approved(tenant: UUID) =
                AuditRecord(Actor.Operator("alice"), "tenant.approved", AuditTarget.tenant(tenant), "198.51.100.1", mapOf("note" to "ok"))

            test("an operator action goes to platform.operator_audit") {
                Operators().run {
                    platform.execute { audit.recordChange(approved(tenantId)) }

                    value("actor") shouldBe "alice"
                    value("action") shouldBe "tenant.approved"
                    value("tenant_id") shouldBe tenantId.toString()
                    value("details ->> 'note'") shouldBe "ok"
                    value("details ->> 'ip'") shouldBe "198.51.100.1"
                }
            }

            test("a target that is not a tenant stays in the details") {
                Operators().run {
                    val application = UUID.randomUUID()

                    val target = AuditTarget("application", application)
                    platform.execute { audit.recordChange(AuditRecord(Actor.Operator("alice"), "application.entered", target)) }

                    sql.queryForObject("SELECT count(*) FROM platform.operator_audit WHERE tenant_id IS NULL", Int::class.java) shouldBe 1
                    value("details ->> 'target'") shouldBe "application:$application"
                }
            }

            test("an operator change rolls back with its transaction") {
                Operators().run {
                    shouldThrow<IllegalArgumentException> {
                        platform.execute {
                            audit.recordChange(approved(tenantId))
                            throw IllegalArgumentException("stop")
                        }
                    }

                    rows() shouldBe 0
                }
            }

            test("an operator change outside a transaction is rejected") {
                Operators().run {
                    shouldThrow<IllegalStateException> { audit.recordChange(approved(tenantId)) }

                    rows() shouldBe 0
                }
            }

            test("an operator denial stays after a rollback and needs no tenant") {
                Operators().run {
                    shouldThrow<IllegalArgumentException> {
                        platform.execute {
                            audit.recordDenial(AuditRecord(Actor.Operator("alice"), "access.denied"))
                            throw IllegalArgumentException("stop")
                        }
                    }

                    value("action") shouldBe "access.denied"
                }
            }

            test("a request without a principal is denied in the same log") {
                Operators().run {
                    audit.recordDenial(AuditRecord(Actor.Anonymous, "access.denied"))

                    value("actor") shouldBe "anonymous"
                }
            }

            test("a tenant user does not belong in the operator log") {
                Operators().run {
                    val denial = AuditRecord(Actor.User(UUID.randomUUID()), "access.denied")

                    shouldThrow<IllegalArgumentException> { audit.recordDenial(denial) }

                    rows() shouldBe 0
                }
            }

            test("ops_runtime cannot change the log") {
                Operators().run {
                    platform.execute { audit.recordChange(approved(tenantId)) }

                    shouldThrow<DataAccessException> {
                        platform.execute { it.execute("DELETE FROM operator_audit") }
                    }.message shouldContain "permission denied"
                    rows() shouldBe 1
                }
            }
        }
    })
