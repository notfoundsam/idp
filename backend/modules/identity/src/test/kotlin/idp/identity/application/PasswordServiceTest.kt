package idp.identity.application

import com.sun.management.ThreadMXBean
import idp.identity.adapters.crypto.HashCost
import idp.identity.adapters.crypto.HashLimit
import idp.identity.adapters.crypto.PasswordHasher
import idp.identity.adapters.persistence.Passwords
import idp.identity.domain.credential.PasswordPolicy
import idp.identity.domain.credential.PasswordRejection
import idp.identity.domain.credential.WeakPassword
import idp.sharedkernel.audit.Actor
import idp.sharedkernel.audit.AuditLog
import idp.sharedkernel.audit.AuditRecord
import idp.sharedkernel.audit.AuditTarget
import idp.sharedkernel.crypto.KeyHierarchy
import idp.sharedkernel.crypto.adapter.OpenBaoKeyManagementService
import idp.sharedkernel.migration.TenantMigrationRunner
import idp.sharedkernel.migration.TenantSchema
import idp.sharedkernel.migration.TenantSchemaFactory
import idp.sharedkernel.outbox.OutboxPublisher
import idp.sharedkernel.security.UserRole
import idp.sharedkernel.tenant.TenantContext
import idp.sharedkernel.tenant.TenantTransaction
import idp.sharedkernel.testing.TestDatabase
import idp.sharedkernel.testing.TestOpenBao
import idp.sharedkernel.throttle.TooManyRequestsException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.jooq.DSLContext
import org.springframework.jdbc.core.JdbcTemplate
import java.lang.management.ManagementFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private const val EMAIL = "ada.lovelace@acme.test"
private const val PASSWORD = "copper lantern river"
private const val WRONG = "copper lantern brook"
private const val IP = "198.51.100.7"
private const val WAIT_SECONDS = 30L
private const val ONE_HASH_BYTES = 19L * 1024L * 1024L
private val ADMIN = Actor.User(UUID.randomUUID())
private val KEY_CACHE_TTL: Duration = Duration.ofMinutes(1)
private val TEST_COST = HashCost(memoryKib = 8, iterations = 1, parallelism = 1)
private val RELAXED_LIMIT get() = HashLimit(permits = 32, queue = 32, wait = Duration.ofSeconds(WAIT_SECONDS))

private class PasswordAudit : AuditLog {
    val changes = CopyOnWriteArrayList<AuditRecord>()

    @Volatile var failing = false

    override fun recordChange(record: AuditRecord) {
        check(!failing) { "audit is down" }
        changes += record
    }

    override fun recordDenial(record: AuditRecord) = throw UnsupportedOperationException()
}

/** Stands still, so a delay of a second cannot run out under a slow test. */
private class SteppedClock : Clock() {
    @Volatile var now: Instant = Instant.parse("2026-11-01T00:00:00Z")

    override fun instant(): Instant = now

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this
}

private class Fixture(
    limit: HashLimit = RELAXED_LIMIT,
    hasher: PasswordHasher = PasswordHasher(TEST_COST),
) {
    val clock = SteppedClock()
    val database = TestDatabase.create()
    val owner = database.owner().also { TenantMigrationRunner(it).migrateAll() }
    val sql = JdbcTemplate(owner)
    val kms = OpenBaoKeyManagementService(TestOpenBao.address, TestOpenBao.TOKEN)
    val transaction = TenantTransaction(database.pooled("worker_runtime"))
    val keys = KeyHierarchy(kms, transaction, KEY_CACHE_TTL)
    val audit = PasswordAudit()
    val identity = IdentityService(transaction, keys, audit, OutboxPublisher(), clock)
    val policy = PasswordPolicy(setOf("password1"))
    val service = PasswordService(transaction, identity, audit, hasher, limit, policy, clock)

    fun tenant(): TenantContext {
        val id = UUID.randomUUID()
        sql.update("INSERT INTO platform.tenants (id, name, status) VALUES (?, 'tenant', 'ACTIVE')", id)
        TenantSchemaFactory(owner).create(id)
        TenantMigrationRunner(owner).migrateTenant(id)
        val context = TenantContext(id, kms.createKey("tenant-$id"))
        TenantContext.with(context) { keys.createTenantKey() }
        return context
    }

    fun <T> TenantContext.acting(block: PasswordService.() -> T): T = TenantContext.with(this) { service.block() }

    fun TenantContext.user(email: String = EMAIL): UUID =
        TenantContext.with(this) { identity.invite(email, UserRole.USER, ADMIN, IP) }.also { audit.changes.clear() }

    fun TenantContext.userWithPassword(email: String = EMAIL): UUID =
        user(email).also {
            acting { setPassword(it, PASSWORD, emptySet(), ADMIN, IP) }
            audit.changes.clear()
        }

    fun TenantContext.table(name: String) = "\"${TenantSchema(tenantId).name}\".$name"

    fun TenantContext.credential(user: UUID): Map<String, Any?>? =
        sql.queryForList("SELECT * FROM ${table("password_credentials")} WHERE user_id = ?", user).firstOrNull()

    fun TenantContext.hash(user: UUID): String = credential(user).shouldNotBeNull()["hash"] as String

    fun TenantContext.failedAttempts(user: UUID): Int = credential(user).shouldNotBeNull()["failed_attempts"] as Int

    fun TenantContext.userVersion(user: UUID): Long? =
        sql.queryForObject("SELECT version FROM ${table("users")} WHERE id = ?", Long::class.java, user)

    fun <T> TenantContext.inTransaction(block: (DSLContext) -> T): T = TenantContext.with(this) { transaction.execute(block) }

    fun TenantContext.wrongAttempts(
        user: UUID,
        count: Int,
    ) = repeat(count) { acting { verify(user, WRONG) } shouldBe false }
}

private fun allocatedBytes(block: () -> Unit): Long {
    val threads = ManagementFactory.getThreadMXBean() as ThreadMXBean
    val before = threads.currentThreadAllocatedBytes
    block()
    return threads.currentThreadAllocatedBytes - before
}

class PasswordServiceTest :
    FunSpec({
        context("setting a password") {
            test("only an Argon2id hash reaches the database") {
                Fixture().run {
                    val tenant = tenant()
                    val user = tenant.user()

                    tenant.acting { setPassword(user, PASSWORD, emptySet(), ADMIN, IP) }

                    val stored = tenant.hash(user)
                    stored shouldStartWith "\$argon2id\$"
                    stored shouldNotContain PASSWORD
                }
            }

            test("it is written to the audit log with the actor") {
                Fixture().run {
                    val tenant = tenant()
                    val user = tenant.user()

                    tenant.acting { setPassword(user, PASSWORD, emptySet(), ADMIN, IP) }

                    val record = audit.changes.single()
                    record.action shouldBe "user.password_set"
                    record.actor shouldBe ADMIN
                    record.target shouldBe AuditTarget.user(user)
                    record.ip shouldBe IP
                }
            }

            test("nothing is stored when the audit record fails") {
                Fixture().run {
                    val tenant = tenant()
                    val user = tenant.user()
                    audit.failing = true

                    shouldThrow<IllegalStateException> { tenant.acting { setPassword(user, PASSWORD, emptySet(), ADMIN) } }

                    tenant.credential(user).shouldBeNull()
                }
            }

            test("a new password replaces the old one and clears the attempts") {
                Fixture().run {
                    val tenant = tenant()
                    val user = tenant.userWithPassword()
                    tenant.wrongAttempts(user, 5)

                    tenant.acting { setPassword(user, "amber kettle meadow", emptySet(), ADMIN) }

                    tenant.failedAttempts(user) shouldBe 0
                    tenant.credential(user).shouldNotBeNull()["version"] shouldBe 1L
                    tenant.acting { verify(user, PASSWORD) } shouldBe false
                    tenant.acting { verify(user, "amber kettle meadow") } shouldBe true
                }
            }

            test("a banned password is refused and nothing is stored or audited") {
                Fixture().run {
                    val tenant = tenant()
                    val user = tenant.user()

                    val failure = shouldThrow<WeakPassword> { tenant.acting { setPassword(user, "password1", emptySet(), ADMIN) } }

                    failure.reason shouldBe PasswordRejection.BANNED
                    tenant.credential(user).shouldBeNull()
                    audit.changes.shouldBeEmpty()
                }
            }

            test("a part of the email before @ is refused") {
                Fixture().run {
                    val tenant = tenant()
                    val user = tenant.user()

                    val failure = shouldThrow<WeakPassword> { tenant.acting { setPassword(user, "lovelace-winter", emptySet(), ADMIN) } }

                    failure.reason shouldBe PasswordRejection.CONTEXT_WORD
                }
            }

            test("the subdomain and the name of the tenant are refused") {
                Fixture().run {
                    val tenant = tenant()
                    val user = tenant.user()
                    val words = setOf("globex", "Initech Systems")

                    listOf("globex-winter-26", "initech-winter-26").forEach { password ->
                        shouldThrow<WeakPassword> { tenant.acting { setPassword(user, password, words, ADMIN) } }
                            .reason shouldBe PasswordRejection.CONTEXT_WORD
                    }
                }
            }

            test("a domain of the email is no context word") {
                Fixture().run {
                    val tenant = tenant()
                    val user = tenant.user()

                    tenant.acting { setPassword(user, "acme-copper-lantern", emptySet(), ADMIN) }

                    tenant.credential(user).shouldNotBeNull()
                }
            }

            test("a password over the limit never reaches the hash") {
                Fixture(hasher = PasswordHasher()).run {
                    val tenant = tenant()
                    val user = tenant.user()
                    val tooLong = "k".repeat(129)

                    allocatedBytes {
                        shouldThrow<WeakPassword> { tenant.acting { setPassword(user, tooLong, emptySet(), ADMIN) } }
                            .reason shouldBe PasswordRejection.TOO_LONG
                    } shouldBeLessThan ONE_HASH_BYTES
                }
            }

            test("an unknown user is refused") {
                Fixture().run {
                    val tenant = tenant()

                    shouldThrow<UserNotFound> { tenant.acting { setPassword(UUID.randomUUID(), PASSWORD, emptySet(), ADMIN) } }
                }
            }

            test("erasing the user removes the credential") {
                Fixture().run {
                    val tenant = tenant()
                    val user = tenant.userWithPassword()

                    TenantContext.with(tenant) { identity.eraseUser(user, ADMIN) }

                    tenant.credential(user).shouldBeNull()
                }
            }
        }

        context("verifying a password") {
            test("the right password passes") {
                Fixture().run {
                    val tenant = tenant()
                    val user = tenant.userWithPassword()

                    tenant.acting { verify(user, PASSWORD) } shouldBe true
                }
            }

            test("a wrong password fails") {
                Fixture().run {
                    val tenant = tenant()
                    val user = tenant.userWithPassword()

                    tenant.acting { verify(user, WRONG) } shouldBe false
                }
            }

            test("a wrong password changes neither the version of the user nor of the credential") {
                Fixture().run {
                    val tenant = tenant()
                    val user = tenant.userWithPassword()
                    val before = tenant.userVersion(user)

                    tenant.wrongAttempts(user, 2)

                    tenant.userVersion(user) shouldBe before
                    tenant.credential(user).shouldNotBeNull()["version"] shouldBe 0L
                    tenant.failedAttempts(user) shouldBe 2
                }
            }

            test("a right password clears the attempts") {
                Fixture().run {
                    val tenant = tenant()
                    val user = tenant.userWithPassword()
                    tenant.wrongAttempts(user, 2)

                    tenant.acting { verify(user, PASSWORD) } shouldBe true

                    tenant.failedAttempts(user) shouldBe 0
                }
            }

            test("during the delay even the right password fails, and it passes after") {
                Fixture().run {
                    val tenant = tenant()
                    val user = tenant.userWithPassword()
                    tenant.wrongAttempts(user, 4)

                    tenant.acting { verify(user, PASSWORD) } shouldBe false
                    tenant.failedAttempts(user) shouldBe 4

                    clock.now = clock.now.plusSeconds(1)
                    tenant.acting { verify(user, PASSWORD) } shouldBe true
                }
            }

            test("a refused attempt during the delay costs a full hash, so it looks like a checked one") {
                Fixture(hasher = PasswordHasher()).run {
                    val tenant = tenant()
                    val user = tenant.userWithPassword()
                    tenant.wrongAttempts(user, 4)

                    allocatedBytes { tenant.acting { verify(user, PASSWORD) } } shouldBeGreaterThan ONE_HASH_BYTES
                }
            }

            test("parallel attempts cannot get past the delay") {
                val parallel = 16
                Fixture().run {
                    val tenant = tenant()
                    val user = tenant.userWithPassword()
                    val barrier = CyclicBarrier(parallel)
                    val threads = Executors.newFixedThreadPool(parallel)

                    val results =
                        (1..parallel)
                            .map {
                                CompletableFuture.supplyAsync(
                                    {
                                        barrier.await(WAIT_SECONDS, TimeUnit.SECONDS)
                                        tenant.acting { verify(user, WRONG) }
                                    },
                                    threads,
                                )
                            }.map { it.get(WAIT_SECONDS, TimeUnit.SECONDS) }
                    threads.shutdown()

                    results.distinct() shouldContainExactly listOf(false)
                    tenant.failedAttempts(user) shouldBeLessThanOrEqual 4
                }
            }

            test("the attempts of one tenant do not touch another tenant") {
                Fixture().run {
                    val first = tenant()
                    val second = tenant()
                    val inFirst = first.userWithPassword()
                    val inSecond = second.userWithPassword()

                    first.wrongAttempts(inFirst, 5)

                    second.acting { verify(inSecond, PASSWORD) } shouldBe true
                    second.failedAttempts(inSecond) shouldBe 0
                    first.hash(inFirst) shouldNotBe second.hash(inSecond)
                }
            }

            test("an unknown user fails at the cost of a real check") {
                Fixture(hasher = PasswordHasher()).run {
                    val tenant = tenant()

                    allocatedBytes { tenant.acting { verify(null, PASSWORD) } shouldBe false } shouldBeGreaterThan ONE_HASH_BYTES
                }
            }

            test("a user without a password fails at the cost of a real check") {
                Fixture(hasher = PasswordHasher()).run {
                    val tenant = tenant()
                    val user = tenant.user()

                    allocatedBytes { tenant.acting { verify(user, PASSWORD) } shouldBe false } shouldBeGreaterThan ONE_HASH_BYTES
                }
            }

            test("a password over the limit fails without a hash or a counted attempt") {
                Fixture(hasher = PasswordHasher()).run {
                    val tenant = tenant()
                    val user = tenant.userWithPassword()

                    allocatedBytes { tenant.acting { verify(user, "k".repeat(129)) } shouldBe false } shouldBeLessThan ONE_HASH_BYTES

                    tenant.failedAttempts(user) shouldBe 0
                }
            }
        }

        context("outdated parameters") {
            val weaker = HashCost(memoryKib = 8, iterations = 1, parallelism = 1)
            val stronger = HashCost(memoryKib = 16, iterations = 2, parallelism = 1)

            fun Fixture.storeWeakerHash(
                tenant: TenantContext,
                user: UUID,
            ) {
                sql.update(
                    "UPDATE ${tenant.run { table("password_credentials") }} SET hash = ? WHERE user_id = ?",
                    PasswordHasher(weaker).hash(PASSWORD),
                    user,
                )
            }

            test("a successful login recomputes the hash") {
                Fixture(hasher = PasswordHasher(stronger)).run {
                    val tenant = tenant()
                    val user = tenant.userWithPassword()
                    storeWeakerHash(tenant, user)

                    tenant.acting { verify(user, PASSWORD) } shouldBe true

                    tenant.hash(user) shouldStartWith "\$argon2id\$v=19\$m=16,t=2,p=1\$"
                    tenant.credential(user).shouldNotBeNull()["version"] shouldBe 1L
                    tenant.acting { verify(user, PASSWORD) } shouldBe true
                }
            }

            test("a failed login leaves the hash alone") {
                Fixture(hasher = PasswordHasher(stronger)).run {
                    val tenant = tenant()
                    val user = tenant.userWithPassword()
                    storeWeakerHash(tenant, user)
                    val before = tenant.hash(user)

                    tenant.acting { verify(user, WRONG) } shouldBe false

                    tenant.hash(user) shouldBe before
                }
            }

            test("a current hash stays as it is") {
                Fixture().run {
                    val tenant = tenant()
                    val user = tenant.userWithPassword()
                    val before = tenant.hash(user)

                    tenant.acting { verify(user, PASSWORD) } shouldBe true

                    tenant.hash(user) shouldBe before
                }
            }

            test("a recomputed hash loses to a password changed in the meantime") {
                Fixture().run {
                    val tenant = tenant()
                    val user = tenant.userWithPassword()
                    val passwords = Passwords()
                    val stale = tenant.inTransaction { dsl -> passwords.find(dsl, user) }.shouldNotBeNull()
                    tenant.acting { setPassword(user, "amber kettle meadow", emptySet(), ADMIN) }
                    val current = tenant.hash(user)

                    tenant.inTransaction { dsl -> passwords.upgradeHash(dsl, stale, "stale") }

                    tenant.hash(user) shouldBe current
                }
            }
        }

        context("overload") {
            test("a request over the limit leaves at once, holds no hash memory and counts no attempt") {
                val entered = CountDownLatch(1)
                val release = CountDownLatch(1)
                val limit = HashLimit(permits = 1, queue = 0, wait = Duration.ZERO)
                Fixture(limit = limit, hasher = PasswordHasher()).run {
                    val tenant = tenant()
                    val user = tenant.userWithPassword()
                    val holder =
                        CompletableFuture.runAsync {
                            limit.run {
                                entered.countDown()
                                release.await(WAIT_SECONDS, TimeUnit.SECONDS)
                            }
                        }
                    entered.await(WAIT_SECONDS, TimeUnit.SECONDS) shouldBe true

                    val allocated = allocatedBytes { shouldThrow<TooManyRequestsException> { tenant.acting { verify(user, PASSWORD) } } }
                    release.countDown()
                    holder.get(WAIT_SECONDS, TimeUnit.SECONDS)

                    allocated shouldBeLessThan ONE_HASH_BYTES
                    tenant.failedAttempts(user) shouldBe 0
                }
            }

            test("setting a password obeys the same limit") {
                val entered = CountDownLatch(1)
                val release = CountDownLatch(1)
                val limit = HashLimit(permits = 1, queue = 0, wait = Duration.ZERO)
                Fixture(limit = limit).run {
                    val tenant = tenant()
                    val user = tenant.user()
                    val holder =
                        CompletableFuture.runAsync {
                            limit.run {
                                entered.countDown()
                                release.await(WAIT_SECONDS, TimeUnit.SECONDS)
                            }
                        }
                    entered.await(WAIT_SECONDS, TimeUnit.SECONDS) shouldBe true

                    shouldThrow<TooManyRequestsException> { tenant.acting { setPassword(user, PASSWORD, emptySet(), ADMIN) } }
                    release.countDown()
                    holder.get(WAIT_SECONDS, TimeUnit.SECONDS)

                    tenant.credential(user).shouldBeNull()
                }
            }
        }

        test("the production service loads the real list") {
            Fixture().run {
                val production = PasswordService(transaction, identity, audit)
                val tenant = tenant()
                val user = tenant.user()

                shouldThrow<WeakPassword> { TenantContext.with(tenant) { production.setPassword(user, "qwertyuiop", emptySet(), ADMIN) } }
                    .reason shouldBe PasswordRejection.BANNED
            }
        }
    })
