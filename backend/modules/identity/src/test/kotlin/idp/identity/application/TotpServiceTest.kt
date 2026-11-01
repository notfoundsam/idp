package idp.identity.application

import idp.identity.adapters.persistence.Totps
import idp.identity.domain.credential.RecoveryCode
import idp.identity.domain.credential.TotpSecret
import idp.sharedkernel.audit.Actor
import idp.sharedkernel.audit.AuditLog
import idp.sharedkernel.audit.AuditRecord
import idp.sharedkernel.audit.AuditTarget
import idp.sharedkernel.crypto.FieldContext
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
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.bouncycastle.util.encoders.Base32
import org.jooq.DSLContext
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private const val EMAIL = "ada.lovelace@acme.test"
private const val IP = "198.51.100.7"
private const val WAIT_SECONDS = 30L
private const val STEP_SECONDS = 30L
private const val SECRET_FIELD = "totp_credentials.secret_ciphertext"
private val ADMIN = Actor.User(UUID.randomUUID())
private val KEY_CACHE_TTL: Duration = Duration.ofMinutes(1)

private class TotpAudit : AuditLog {
    val changes = CopyOnWriteArrayList<AuditRecord>()

    @Volatile var failing = false

    override fun recordChange(record: AuditRecord) {
        check(!failing) { "audit is down" }
        changes += record
    }

    override fun recordDenial(record: AuditRecord) = throw UnsupportedOperationException()
}

private class TotpFixture {
    val clock = SteppedClock()
    val database = TestDatabase.create()
    val owner = database.owner().also { TenantMigrationRunner(it).migrateAll() }
    val sql = JdbcTemplate(owner)
    val kms = OpenBaoKeyManagementService(TestOpenBao.address, TestOpenBao.TOKEN)
    val transaction = TenantTransaction(database.pooled("worker_runtime"))
    val keys = KeyHierarchy(kms, transaction, KEY_CACHE_TTL)
    val audit = TotpAudit()
    val identity = IdentityService(transaction, keys, audit, OutboxPublisher(), clock)
    val service = TotpService(transaction, keys, audit, clock)

    fun tenant(): TenantContext {
        val id = UUID.randomUUID()
        sql.update("INSERT INTO platform.tenants (id, name, status) VALUES (?, 'tenant', 'ACTIVE')", id)
        TenantSchemaFactory(owner).create(id)
        TenantMigrationRunner(owner).migrateTenant(id)
        val context = TenantContext(id, kms.createKey("tenant-$id"))
        TenantContext.with(context) { keys.createTenantKey() }
        return context
    }

    fun <T> TenantContext.acting(block: TotpService.() -> T): T = TenantContext.with(this) { service.block() }

    fun TenantContext.user(email: String = EMAIL): UUID =
        TenantContext.with(this) { identity.invite(email, UserRole.USER, ADMIN, IP) }.also { audit.changes.clear() }

    fun TenantContext.enrolled(user: UUID): TotpSecret = secretOf(acting { enroll(user) })

    /** The confirmation spends the step it was made with, so the clock moves on from it. */
    fun TenantContext.confirmed(user: UUID): TotpSecret =
        enrolled(user).also { secret ->
            acting { confirm(user, secret.current(), ADMIN, IP) } shouldBe true
            clock.now = clock.now.plusSeconds(2 * STEP_SECONDS)
            audit.changes.clear()
        }

    fun secretOf(enrollment: TotpEnrollment) = TotpSecret(Base32.decode(enrollment.secret))

    fun TotpSecret.current(shift: Long = 0): String = code(TotpSecret.stepAt(clock.now) + shift)

    /** Never one of the codes the window accepts. */
    fun TotpSecret.wrong(): String {
        val accepted = (-1L..1L).map { current(it) }
        return listOf("000000", "000001", "000002", "000003").first { it !in accepted }
    }

    fun TenantContext.table(name: String) = "\"${TenantSchema(tenantId).name}\".$name"

    fun TenantContext.credential(user: UUID): Map<String, Any?>? =
        sql.queryForList("SELECT * FROM ${table("totp_credentials")} WHERE user_id = ?", user).firstOrNull()

    fun TenantContext.failedAttempts(user: UUID): Int = credential(user).shouldNotBeNull()["failed_attempts"] as Int

    fun TenantContext.userVersion(user: UUID): Long? =
        sql.queryForObject("SELECT version FROM ${table("users")} WHERE id = ?", Long::class.java, user)

    fun TenantContext.recoveryHashes(user: UUID): List<ByteArray> =
        sql.queryForList("SELECT code_hash FROM ${table("recovery_codes")} WHERE user_id = ?", ByteArray::class.java, user).filterNotNull()

    fun <T> TenantContext.inTransaction(block: (DSLContext) -> T): T = TenantContext.with(this) { transaction.execute(block) }

    fun TenantContext.wrongAttempts(
        user: UUID,
        secret: TotpSecret,
        count: Int,
    ) = repeat(count) { acting { verify(user, secret.wrong()) } shouldBe false }
}

class TotpServiceTest :
    FunSpec({
        context("enrolling") {
            test("only a ciphertext reaches the database") {
                TotpFixture().run {
                    val tenant = tenant()
                    val user = tenant.user()

                    val secret = tenant.enrolled(user)

                    val stored = tenant.credential(user).shouldNotBeNull()["secret_ciphertext"] as ByteArray
                    stored.toList().windowed(secret.bytes.size).shouldNotContain(secret.bytes.toList())
                    val opened =
                        TenantContext.with(tenant) { keys.userCipher(user).decrypt(stored, FieldContext(SECRET_FIELD, user)) }
                    opened shouldBe secret.bytes
                }
            }

            test("a secret that nobody confirmed proves nothing") {
                TotpFixture().run {
                    val tenant = tenant()
                    val user = tenant.user()

                    val secret = tenant.enrolled(user)

                    tenant.acting { verify(user, secret.current()) } shouldBe false
                    tenant.failedAttempts(user) shouldBe 0
                }
            }

            test("nothing is audited before the confirmation") {
                TotpFixture().run {
                    val tenant = tenant()
                    val user = tenant.user()

                    tenant.enrolled(user)

                    audit.changes.shouldBeEmpty()
                }
            }

            test("starting again replaces a secret nobody confirmed") {
                TotpFixture().run {
                    val tenant = tenant()
                    val user = tenant.user()
                    val abandoned = tenant.enrolled(user)

                    val secret = tenant.enrolled(user)

                    tenant.acting { confirm(user, abandoned.current(), ADMIN) } shouldBe false
                    tenant.acting { confirm(user, secret.current(), ADMIN) } shouldBe true
                }
            }

            test("a confirmed secret cannot be replaced by a new enrollment") {
                TotpFixture().run {
                    val tenant = tenant()
                    val user = tenant.user()
                    val secret = tenant.confirmed(user)

                    shouldThrow<TotpAlreadyEnrolled> { tenant.acting { enroll(user) } }

                    tenant.acting { verify(user, secret.current()) } shouldBe true
                }
            }

            test("an unknown user is refused") {
                TotpFixture().run {
                    val tenant = tenant()

                    shouldThrow<UserNotFound> { tenant.acting { enroll(UUID.randomUUID()) } }
                }
            }

            test("the secret of the enrollment stays out of toString") {
                TotpEnrollment("JBSWY3DPEHPK3PXP").toString() shouldBe "TotpEnrollment(***)"
            }
        }

        context("confirming") {
            test("the right code turns the secret on and is written to the audit log") {
                TotpFixture().run {
                    val tenant = tenant()
                    val user = tenant.user()
                    val secret = tenant.enrolled(user)

                    tenant.acting { confirm(user, secret.current(), ADMIN, IP) } shouldBe true

                    tenant.credential(user).shouldNotBeNull()["confirmed_at"].shouldNotBeNull()
                    val record = audit.changes.single()
                    record.action shouldBe "user.totp_enrolled"
                    record.actor shouldBe ADMIN
                    record.target shouldBe AuditTarget.user(user)
                    record.ip shouldBe IP
                }
            }

            test("the code that confirmed cannot log in") {
                TotpFixture().run {
                    val tenant = tenant()
                    val user = tenant.user()
                    val secret = tenant.enrolled(user)
                    val code = secret.current()

                    tenant.acting { confirm(user, code, ADMIN) } shouldBe true

                    tenant.acting { verify(user, code) } shouldBe false
                }
            }

            test("a wrong code leaves it off and writes nothing") {
                TotpFixture().run {
                    val tenant = tenant()
                    val user = tenant.user()
                    val secret = tenant.enrolled(user)

                    tenant.acting { confirm(user, secret.wrong(), ADMIN) } shouldBe false

                    tenant.credential(user).shouldNotBeNull()["confirmed_at"].shouldBeNull()
                    audit.changes.shouldBeEmpty()
                }
            }

            test("it stays off when the audit record fails") {
                TotpFixture().run {
                    val tenant = tenant()
                    val user = tenant.user()
                    val secret = tenant.enrolled(user)
                    audit.failing = true

                    shouldThrow<IllegalStateException> { tenant.acting { confirm(user, secret.current(), ADMIN) } }

                    tenant.credential(user).shouldNotBeNull()["confirmed_at"].shouldBeNull()
                }
            }

            test("confirming twice does nothing the second time") {
                TotpFixture().run {
                    val tenant = tenant()
                    val user = tenant.user()
                    val secret = tenant.confirmed(user)

                    tenant.acting { confirm(user, secret.current(), ADMIN) } shouldBe false

                    audit.changes.shouldBeEmpty()
                }
            }

            test("a user without an enrollment has nothing to confirm") {
                TotpFixture().run {
                    val tenant = tenant()

                    tenant.acting { confirm(tenant.user(), "000000", ADMIN) } shouldBe false
                }
            }

            test("a confirmation loses to an enrollment that replaced the secret") {
                TotpFixture().run {
                    val tenant = tenant()
                    val user = tenant.user()
                    val secret = tenant.enrolled(user)
                    val totps = Totps()
                    val stale = tenant.inTransaction { dsl -> totps.find(dsl, user) }.shouldNotBeNull()
                    tenant.enrolled(user)

                    tenant.inTransaction { dsl -> totps.confirm(dsl, stale, TotpSecret.stepAt(clock.now), clock.now) } shouldBe false

                    tenant.credential(user).shouldNotBeNull()["confirmed_at"].shouldBeNull()
                    tenant.acting { confirm(user, secret.current(), ADMIN) } shouldBe false
                }
            }
        }

        context("whether a user has TOTP") {
            test("false before the enrollment") {
                TotpFixture().run {
                    val tenant = tenant()
                    val user = tenant.user()

                    tenant.acting { hasConfirmedTotp(user) } shouldBe false
                }
            }

            test("false until a code confirms the secret") {
                TotpFixture().run {
                    val tenant = tenant()
                    val user = tenant.user()
                    tenant.enrolled(user)

                    tenant.acting { hasConfirmedTotp(user) } shouldBe false
                }
            }

            test("true after the confirmation") {
                TotpFixture().run {
                    val tenant = tenant()
                    val user = tenant.user()
                    tenant.confirmed(user)

                    tenant.acting { hasConfirmedTotp(user) } shouldBe true
                }
            }
        }

        context("verifying") {
            test("the current code passes") {
                TotpFixture().run {
                    val tenant = tenant()
                    val user = tenant.user()
                    val secret = tenant.confirmed(user)

                    tenant.acting { verify(user, secret.current()) } shouldBe true
                }
            }

            test("a code of the previous step passes") {
                TotpFixture().run {
                    val tenant = tenant()
                    val user = tenant.user()
                    val secret = tenant.confirmed(user)

                    tenant.acting { verify(user, secret.current(-1)) } shouldBe true
                }
            }

            test("a code of the next step passes") {
                TotpFixture().run {
                    val tenant = tenant()
                    val user = tenant.user()
                    val secret = tenant.confirmed(user)

                    tenant.acting { verify(user, secret.current(1)) } shouldBe true
                }
            }

            test("a code outside the tolerance fails") {
                TotpFixture().run {
                    val tenant = tenant()
                    val user = tenant.user()
                    val secret = tenant.confirmed(user)

                    tenant.acting { verify(user, secret.current(2)) } shouldBe false
                }
            }

            test("a wrong code fails") {
                TotpFixture().run {
                    val tenant = tenant()
                    val user = tenant.user()
                    val secret = tenant.confirmed(user)

                    tenant.acting { verify(user, secret.wrong()) } shouldBe false
                }
            }

            test("a user without TOTP fails") {
                TotpFixture().run {
                    val tenant = tenant()

                    tenant.acting { verify(tenant.user(), "000000") } shouldBe false
                }
            }

            test("an unknown user fails") {
                TotpFixture().run {
                    val tenant = tenant()

                    tenant.acting { verify(UUID.randomUUID(), "000000") } shouldBe false
                }
            }

            test("the attempts of one tenant do not touch another tenant") {
                TotpFixture().run {
                    val first = tenant()
                    val second = tenant()
                    val inFirst = first.user()
                    val inSecond = second.user()
                    val firstSecret = first.confirmed(inFirst)
                    val secondSecret = second.confirmed(inSecond)

                    first.wrongAttempts(inFirst, firstSecret, 5)

                    second.acting { verify(inSecond, secondSecret.current()) } shouldBe true
                    second.failedAttempts(inSecond) shouldBe 0
                }
            }
        }

        context("a repeated code") {
            test("fails in the same step") {
                TotpFixture().run {
                    val tenant = tenant()
                    val user = tenant.user()
                    val secret = tenant.confirmed(user)
                    val code = secret.current()

                    tenant.acting { verify(user, code) } shouldBe true

                    tenant.acting { verify(user, code) } shouldBe false
                }
            }

            test("fails while it is still inside the tolerance") {
                TotpFixture().run {
                    val tenant = tenant()
                    val user = tenant.user()
                    val secret = tenant.confirmed(user)
                    val code = secret.current()
                    tenant.acting { verify(user, code) } shouldBe true

                    clock.now = clock.now.plusSeconds(STEP_SECONDS)

                    tenant.acting { verify(user, code) } shouldBe false
                }
            }

            test("an older code fails after a newer one was used") {
                TotpFixture().run {
                    val tenant = tenant()
                    val user = tenant.user()
                    val secret = tenant.confirmed(user)
                    tenant.acting { verify(user, secret.current(1)) } shouldBe true

                    tenant.acting { verify(user, secret.current()) } shouldBe false
                }
            }

            test("the next step works again") {
                TotpFixture().run {
                    val tenant = tenant()
                    val user = tenant.user()
                    val secret = tenant.confirmed(user)
                    tenant.acting { verify(user, secret.current()) } shouldBe true

                    clock.now = clock.now.plusSeconds(STEP_SECONDS)

                    tenant.acting { verify(user, secret.current()) } shouldBe true
                }
            }

            test("the conditional update refuses a credential read before the step was spent") {
                TotpFixture().run {
                    val tenant = tenant()
                    val user = tenant.user()
                    val secret = tenant.confirmed(user)
                    val totps = Totps()
                    val step = TotpSecret.stepAt(clock.now)
                    val stale = tenant.inTransaction { dsl -> totps.find(dsl, user) }.shouldNotBeNull()
                    tenant.acting { verify(user, secret.current()) } shouldBe true

                    tenant.inTransaction { dsl -> totps.markUsed(dsl, stale, step) } shouldBe false
                }
            }

            test("of parallel requests with one code at most one passes") {
                val parallel = 16
                TotpFixture().run {
                    val tenant = tenant()
                    val user = tenant.user()
                    val secret = tenant.confirmed(user)
                    val code = secret.current()

                    val results = inParallel(parallel) { tenant.acting { verify(user, code) } }

                    results.count { it } shouldBeLessThanOrEqual 1
                    tenant.acting { verify(user, code) } shouldBe false
                }
            }
        }

        context("the limit of attempts") {
            test("a wrong code changes neither the version of the user nor of the credential") {
                TotpFixture().run {
                    val tenant = tenant()
                    val user = tenant.user()
                    val secret = tenant.confirmed(user)
                    val before = tenant.userVersion(user)
                    val version = tenant.credential(user).shouldNotBeNull()["version"]

                    tenant.wrongAttempts(user, secret, 2)

                    tenant.userVersion(user) shouldBe before
                    tenant.credential(user).shouldNotBeNull()["version"] shouldBe version
                    tenant.failedAttempts(user) shouldBe 2
                }
            }

            test("a repeated code counts as an attempt") {
                TotpFixture().run {
                    val tenant = tenant()
                    val user = tenant.user()
                    val secret = tenant.confirmed(user)
                    val code = secret.current()
                    tenant.acting { verify(user, code) } shouldBe true

                    tenant.acting { verify(user, code) } shouldBe false

                    tenant.failedAttempts(user) shouldBe 1
                }
            }

            test("a right code clears the attempts") {
                TotpFixture().run {
                    val tenant = tenant()
                    val user = tenant.user()
                    val secret = tenant.confirmed(user)
                    tenant.wrongAttempts(user, secret, 2)

                    tenant.acting { verify(user, secret.current()) } shouldBe true

                    tenant.failedAttempts(user) shouldBe 0
                }
            }

            test("during the delay even the right code fails, and it passes after") {
                TotpFixture().run {
                    val tenant = tenant()
                    val user = tenant.user()
                    val secret = tenant.confirmed(user)
                    tenant.wrongAttempts(user, secret, 4)

                    tenant.acting { verify(user, secret.current()) } shouldBe false
                    tenant.failedAttempts(user) shouldBe 4

                    clock.now = clock.now.plusSeconds(1)
                    tenant.acting { verify(user, secret.current()) } shouldBe true
                }
            }

            test("parallel attempts do not go over the limit") {
                val parallel = 16
                TotpFixture().run {
                    val tenant = tenant()
                    val user = tenant.user()
                    val secret = tenant.confirmed(user)
                    val wrong = secret.wrong()

                    val results = inParallel(parallel) { tenant.acting { verify(user, wrong) } }

                    results.distinct() shouldContainExactly listOf(false)
                    tenant.failedAttempts(user) shouldBeLessThanOrEqual 4
                }
            }

            test("a wrong confirmation counts as well") {
                TotpFixture().run {
                    val tenant = tenant()
                    val user = tenant.user()
                    val secret = tenant.enrolled(user)

                    repeat(4) { tenant.acting { confirm(user, secret.wrong(), ADMIN) } shouldBe false }

                    tenant.failedAttempts(user) shouldBe 4
                    tenant.acting { confirm(user, secret.current(), ADMIN) } shouldBe false
                }
            }
        }

        context("recovery codes") {
            test("ten are issued and only their hashes reach the database") {
                TotpFixture().run {
                    val tenant = tenant()
                    val user = tenant.user()

                    val codes = tenant.acting { issueRecoveryCodes(user, ADMIN) }

                    codes shouldHaveSize 10
                    val stored = tenant.recoveryHashes(user).map { it.toList() }.toSet()
                    stored shouldBe codes.map { RecoveryCode(it).hash().toList() }.toSet()
                    val row = sql.queryForList("SELECT * FROM ${tenant.table("recovery_codes")} WHERE user_id = ?", user).first()
                    row.keys shouldBe setOf("user_id", "code_hash")
                }
            }

            test("issuing is written to the audit log") {
                TotpFixture().run {
                    val tenant = tenant()
                    val user = tenant.user()

                    tenant.acting { issueRecoveryCodes(user, ADMIN, IP) }

                    val record = audit.changes.single()
                    record.action shouldBe "user.recovery_codes_issued"
                    record.actor shouldBe ADMIN
                    record.target shouldBe AuditTarget.user(user)
                    record.ip shouldBe IP
                }
            }

            test("nothing is stored when the audit record fails") {
                TotpFixture().run {
                    val tenant = tenant()
                    val user = tenant.user()
                    audit.failing = true

                    shouldThrow<IllegalStateException> { tenant.acting { issueRecoveryCodes(user, ADMIN) } }

                    tenant.recoveryHashes(user).shouldBeEmpty()
                }
            }

            test("an unknown user is refused") {
                TotpFixture().run {
                    val tenant = tenant()

                    shouldThrow<UserNotFound> { tenant.acting { issueRecoveryCodes(UUID.randomUUID(), ADMIN) } }
                }
            }

            test("a code works once") {
                TotpFixture().run {
                    val tenant = tenant()
                    val user = tenant.user()
                    val code = tenant.acting { issueRecoveryCodes(user, ADMIN) }.first()

                    tenant.acting { useRecoveryCode(user, code, ADMIN) } shouldBe true

                    tenant.acting { useRecoveryCode(user, code, ADMIN) } shouldBe false
                    tenant.recoveryHashes(user) shouldHaveSize 9
                }
            }

            test("of parallel requests with one code exactly one passes") {
                val parallel = 16
                TotpFixture().run {
                    val tenant = tenant()
                    val user = tenant.user()
                    val code = tenant.acting { issueRecoveryCodes(user, ADMIN) }.first()

                    val results = inParallel(parallel) { tenant.acting { useRecoveryCode(user, code, ADMIN) } }

                    results.count { it } shouldBe 1
                }
            }

            test("a code retyped in lower case without dashes works") {
                TotpFixture().run {
                    val tenant = tenant()
                    val user = tenant.user()
                    val code = tenant.acting { issueRecoveryCodes(user, ADMIN) }.first()

                    tenant.acting { useRecoveryCode(user, code.replace("-", "").lowercase(), ADMIN) } shouldBe true
                }
            }

            test("using one is written to the audit log, a failure is not") {
                TotpFixture().run {
                    val tenant = tenant()
                    val user = tenant.user()
                    val code = tenant.acting { issueRecoveryCodes(user, ADMIN) }.first()
                    audit.changes.clear()

                    tenant.acting { useRecoveryCode(user, "AAAA-AAAA", ADMIN, IP) } shouldBe false
                    audit.changes.shouldBeEmpty()

                    tenant.acting { useRecoveryCode(user, code, ADMIN, IP) } shouldBe true
                    val record = audit.changes.single()
                    record.action shouldBe "user.recovery_code_used"
                    record.actor shouldBe ADMIN
                    record.target shouldBe AuditTarget.user(user)
                    record.ip shouldBe IP
                }
            }

            test("a code survives when the audit record fails") {
                TotpFixture().run {
                    val tenant = tenant()
                    val user = tenant.user()
                    val code = tenant.acting { issueRecoveryCodes(user, ADMIN) }.first()
                    audit.failing = true

                    shouldThrow<IllegalStateException> { tenant.acting { useRecoveryCode(user, code, ADMIN) } }

                    audit.failing = false
                    tenant.acting { useRecoveryCode(user, code, ADMIN) } shouldBe true
                }
            }

            test("new codes replace the old ones") {
                TotpFixture().run {
                    val tenant = tenant()
                    val user = tenant.user()
                    val old = tenant.acting { issueRecoveryCodes(user, ADMIN) }.first()

                    val fresh = tenant.acting { issueRecoveryCodes(user, ADMIN) }.first()

                    tenant.acting { useRecoveryCode(user, old, ADMIN) } shouldBe false
                    tenant.acting { useRecoveryCode(user, fresh, ADMIN) } shouldBe true
                }
            }

            test("a code of another user is refused") {
                TotpFixture().run {
                    val tenant = tenant()
                    val user = tenant.user()
                    val other = tenant.user("grace.hopper@acme.test")
                    val code = tenant.acting { issueRecoveryCodes(user, ADMIN) }.first()

                    tenant.acting { useRecoveryCode(other, code, ADMIN) } shouldBe false
                    tenant.recoveryHashes(user) shouldHaveSize 10
                }
            }

            test("a running TOTP delay does not stop a recovery code") {
                TotpFixture().run {
                    val tenant = tenant()
                    val user = tenant.user()
                    val secret = tenant.confirmed(user)
                    val code = tenant.acting { issueRecoveryCodes(user, ADMIN) }.first()
                    tenant.wrongAttempts(user, secret, 4)

                    tenant.acting { useRecoveryCode(user, code, ADMIN) } shouldBe true
                }
            }
        }

        test("erasing the user removes the secret and the codes") {
            TotpFixture().run {
                val tenant = tenant()
                val user = tenant.user()
                tenant.confirmed(user)
                tenant.acting { issueRecoveryCodes(user, ADMIN) }

                TenantContext.with(tenant) { identity.eraseUser(user, ADMIN) }

                tenant.credential(user).shouldBeNull()
                tenant.recoveryHashes(user).shouldBeEmpty()
            }
        }
    })

private fun <T> inParallel(
    count: Int,
    action: () -> T,
): List<T> {
    val barrier = CyclicBarrier(count)
    val threads = Executors.newFixedThreadPool(count)
    return (1..count)
        .map {
            CompletableFuture.supplyAsync(
                {
                    barrier.await(WAIT_SECONDS, TimeUnit.SECONDS)
                    action()
                },
                threads,
            )
        }.map { it.get(WAIT_SECONDS, TimeUnit.SECONDS) }
        .also { threads.shutdown() }
}
