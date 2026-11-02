package idp.worker.crypto

import idp.audit.application.AuditService
import idp.identity.application.IdentityService
import idp.sharedkernel.audit.Actor
import idp.sharedkernel.crypto.FieldCipher
import idp.sharedkernel.crypto.KeyHierarchy
import idp.sharedkernel.crypto.KeyManagementRouter
import idp.sharedkernel.crypto.KeyManagementService
import idp.sharedkernel.crypto.KeyManagementUnavailableException
import idp.sharedkernel.crypto.KeyNotFoundException
import idp.sharedkernel.crypto.adapter.OpenBaoKeyManagementService
import idp.sharedkernel.migration.TenantSchema
import idp.sharedkernel.outbox.OutboxPublisher
import idp.sharedkernel.scheduling.JobLocks
import idp.sharedkernel.scheduling.JobScheduler
import idp.sharedkernel.scheduling.ScheduledJob
import idp.sharedkernel.security.UserRole
import idp.sharedkernel.tenant.PlatformTransaction
import idp.sharedkernel.tenant.TenantContext
import idp.sharedkernel.tenant.TenantResolver
import idp.sharedkernel.tenant.TenantTransaction
import idp.sharedkernel.testing.MovableClock
import idp.sharedkernel.testing.TestDatabase
import idp.sharedkernel.testing.TestOpenBao
import idp.tenancy.application.TenancyService
import idp.tenancy.application.TenantView
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.date.shouldBeBetween
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldStartWith
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

private const val BASE_DOMAIN = "idp.test"
private const val EMAIL = "ada@acme.test"
private const val OPENBAO = "hcvault"
private const val OTHER = "other-kms"
private val RETENTION: Duration = Duration.ofDays(35)
private val ADMIN = Actor.User(UUID.randomUUID())

/** OpenBao under a scheme of its own, so a move crosses two adapters of the router with real keys. */
private class OtherKms(
    private val delegate: KeyManagementService,
) : KeyManagementService {
    override fun createKey(alias: String): String = "$OTHER://" + delegate.createKey(alias).removePrefix("$OPENBAO://")

    override fun wrap(
        kekUri: String,
        plaintext: ByteArray,
    ): ByteArray = delegate.wrap(inner(kekUri), plaintext)

    override fun unwrap(
        kekUri: String,
        wrapped: ByteArray,
    ): ByteArray = delegate.unwrap(inner(kekUri), wrapped)

    override fun destroy(kekUri: String) = delegate.destroy(inner(kekUri))

    private fun inner(kekUri: String): String {
        require(kekUri.startsWith("$OTHER://")) { "Not a key of $OTHER: $kekUri" }
        return "$OPENBAO://" + kekUri.removePrefix("$OTHER://")
    }
}

/** The router of a deployment, whose setting for new keys a test changes as an operator would. */
private class ObservedKms(
    private val adapters: Map<String, KeyManagementService>,
) : KeyManagementService {
    val created = CopyOnWriteArrayList<String>()
    val unwraps = AtomicInteger()

    @Volatile var newKeys = OTHER

    @Volatile var wrapsDown = false

    private val router get() = KeyManagementRouter(adapters.getValue(newKeys), adapters)

    override fun createKey(alias: String): String = router.createKey(alias).also { created += it }

    override fun wrap(
        kekUri: String,
        plaintext: ByteArray,
    ): ByteArray {
        if (wrapsDown) throw KeyManagementUnavailableException("down")
        return router.wrap(kekUri, plaintext)
    }

    override fun unwrap(
        kekUri: String,
        wrapped: ByteArray,
    ): ByteArray {
        unwraps.incrementAndGet()
        return router.unwrap(kekUri, wrapped)
    }

    override fun destroy(kekUri: String) = router.destroy(kekUri)
}

private class Rotation {
    val database = TestDatabase.migrated()
    val sql = JdbcTemplate(database.owner())
    val worker = database.pooled("worker_runtime")
    val clock = MovableClock()
    val openBao = OpenBaoKeyManagementService(TestOpenBao.address, TestOpenBao.TOKEN)
    val other = OtherKms(openBao)
    val kms = ObservedKms(mapOf(OPENBAO to openBao, OTHER to other))
    private val publisher = OutboxPublisher()
    private val platform = PlatformTransaction(worker)
    val transaction = TenantTransaction(worker)
    val keys = newKeys()
    val audit = AuditService(transaction, keys)
    private val contacts = FieldCipher.platform(openBao, openBao.createKey("contacts-${UUID.randomUUID()}"))
    val tenancy = TenancyService(platform, audit, publisher, contacts, BASE_DOMAIN, clock)

    fun newKeys() = KeyHierarchy(kms, transaction, platform, Duration.ofMinutes(1))

    fun moveJob(newKeys: String = OTHER): KekMoveJob {
        kms.newKeys = newKeys
        return KekMoveJob(tenancy, kms, keys, transaction, audit, newKeys, RETENTION)
    }

    fun previousJob() = PreviousKekJob(tenancy, kms, transaction, audit, clock)

    /** Without the lock of the last run, which would hold the job until its interval passed. */
    fun run(job: ScheduledJob) {
        sql.update("DELETE FROM platform.scheduled_locks")
        JobScheduler(listOf(job), JobLocks(platform, clock)).tick()
    }

    fun moved(tenant: UUID) {
        run(moveJob())
        view(tenant).previousKekDestroyAfter.shouldNotBeNull()
    }

    fun tenant(
        status: String = "ACTIVE",
        kms: KeyManagementService = openBao,
    ): UUID {
        val id = database.tenantSchema()
        val kek = kms.createKey("tenant-${UUID.randomUUID()}")
        sql.update("INSERT INTO platform.tenants (id, name, status, kek_uri) VALUES (?, 'Acme', ?, ?)", id, status, kek)
        TenantContext.with(TenantContext(id, kek)) { keys.createTenantKey() }
        return id
    }

    fun view(tenant: UUID): TenantView = tenancy.tenant(tenant).shouldNotBeNull()

    /** A fresh service reads the tenant key from the database, not from the cache of the one that wrote it. */
    fun <T> reader(
        context: TenantContext,
        block: IdentityService.() -> T,
    ): T {
        val keys = newKeys()
        return TenantContext.with(context) { IdentityService(transaction, keys, AuditService(transaction, keys), publisher).block() }
    }

    fun <T> inTenant(
        tenant: UUID,
        block: IdentityService.() -> T,
    ): T = reader(TenantContext(tenant, view(tenant).kekUri), block)

    fun invite(tenant: UUID): UUID = inTenant(tenant) { invite(EMAIL, UserRole.USER, ADMIN) }

    private fun table(
        tenant: UUID,
        name: String,
    ) = "\"${TenantSchema(tenant).name}\".$name"

    fun tenantKey(tenant: UUID): ByteArray =
        sql.queryForObject("SELECT wrapped_key FROM ${table(tenant, "tenant_keys")}", ByteArray::class.java)!!

    /** Everything under the tenant key: a move must leave it byte for byte. */
    fun dataUnderTenantKey(tenant: UUID): List<String?> =
        sql.queryForList(
            """
            SELECT encode(k.keyset, 'hex') || ':' || encode(u.email_ciphertext, 'hex')
            FROM ${table(tenant, "user_keys")} k JOIN ${table(tenant, "users")} u ON u.id = k.user_id ORDER BY k.user_id
            """,
            String::class.java,
        )

    fun systemAudit(tenant: UUID): List<String?> =
        sql.queryForList(
            "SELECT action FROM ${table(tenant, "audit_entries")} WHERE actor_type = 'SYSTEM' ORDER BY occurred_at",
            String::class.java,
        )

    fun addDomain(
        tenant: UUID,
        host: String,
    ) {
        sql.update("INSERT INTO platform.tenant_domains (domain, tenant_id) VALUES (?, ?)", host, tenant)
    }
}

class KeyRotationJobsTest :
    FunSpec({
        context("a KEK rotated inside its KMS") {
            test("still opens the tenant key, and nothing is wrapped or encrypted again") {
                Rotation().run {
                    val tenant = tenant()
                    val user = invite(tenant)
                    val kek = view(tenant).kekUri.shouldNotBeNull()
                    val tenantKey = tenantKey(tenant)
                    val data = dataUnderTenantKey(tenant)

                    TestOpenBao.rotate(kek)
                    run(moveJob(newKeys = OPENBAO))

                    TestOpenBao.keyField(kek, "latest_version") shouldBe "2"
                    inTenant(tenant) { emailOf(user) } shouldBe EMAIL
                    view(tenant).kekUri shouldBe kek
                    view(tenant).previousKekUri.shouldBeNull()
                    tenantKey(tenant) shouldBe tenantKey
                    dataUnderTenantKey(tenant) shouldBe data
                }
            }
        }

        context("a move to another KMS") {
            test("wraps only the tenant key with a KEK of the new KMS, and the data under it stays as it is") {
                Rotation().run {
                    val tenant = tenant()
                    val user = invite(tenant)
                    val previous = view(tenant).kekUri.shouldNotBeNull()
                    val tenantKey = tenantKey(tenant)
                    val data = dataUnderTenantKey(tenant)

                    run(moveJob())

                    val moved = view(tenant)
                    moved.kekUri.shouldNotBeNull() shouldStartWith "$OTHER://"
                    moved.previousKekUri shouldBe previous
                    moved.previousKekDestroyAfter.shouldNotBeNull().shouldBeBetween(
                        Instant.now().plus(RETENTION).minusSeconds(60),
                        Instant.now().plus(RETENTION).plusSeconds(60),
                    )
                    tenantKey(tenant) shouldNotBe tenantKey
                    dataUnderTenantKey(tenant) shouldBe data
                    inTenant(tenant) { emailOf(user) } shouldBe EMAIL
                    systemAudit(tenant).shouldContainExactly(KEK_REPLACED)
                }
            }

            test("keeps the previous KEK able to open a backup taken before it") {
                Rotation().run {
                    val tenant = tenant()
                    val previous = view(tenant).kekUri.shouldNotBeNull()
                    val backedUp = tenantKey(tenant)

                    moved(tenant)

                    kms.unwrap(previous, backedUp) shouldBe kms.unwrap(view(tenant).kekUri!!, tenantKey(tenant))
                }
            }

            test("leaves a tenant already in the KMS of new keys alone") {
                Rotation().run {
                    val tenant = tenant(kms = other)
                    val kek = view(tenant).kekUri
                    kms.created.clear()

                    run(moveJob())

                    view(tenant).kekUri shouldBe kek
                    view(tenant).previousKekUri.shouldBeNull()
                    kms.created.shouldBeEmpty()
                    systemAudit(tenant).shouldBeEmpty()
                }
            }

            test("moves a suspended tenant and leaves an offboarding one to its destruction") {
                Rotation().run {
                    val suspended = tenant(status = "SUSPENDED")
                    val offboarding = tenant(status = "OFFBOARDING")
                    val kek = view(offboarding).kekUri

                    run(moveJob())

                    view(suspended).kekUri.shouldNotBeNull() shouldStartWith "$OTHER://"
                    view(offboarding).kekUri shouldBe kek
                    view(offboarding).previousKekUri.shouldBeNull()
                }
            }

            test("cut short before the rewrap lets readers in through the previous KEK, and the next run finishes it") {
                Rotation().run {
                    val tenant = tenant()
                    val user = invite(tenant)
                    val previous = view(tenant).kekUri
                    val tenantKey = tenantKey(tenant)
                    kms.created.clear()
                    kms.wrapsDown = true

                    run(moveJob())

                    view(tenant).previousKekUri shouldBe previous
                    view(tenant).previousKekDestroyAfter.shouldBeNull()
                    tenantKey(tenant) shouldBe tenantKey
                    inTenant(tenant) { emailOf(user) } shouldBe EMAIL

                    kms.wrapsDown = false
                    run(moveJob())

                    view(tenant).previousKekDestroyAfter.shouldNotBeNull()
                    tenantKey(tenant) shouldNotBe tenantKey
                    kms.created shouldHaveSize 1
                    inTenant(tenant) { emailOf(user) } shouldBe EMAIL
                    systemAudit(tenant).shouldContainExactly(KEK_REPLACED)
                }
            }

            test("repeated after the rewrap and before its record wraps nothing again and records it once") {
                Rotation().run {
                    val tenant = tenant()
                    moved(tenant)
                    val tenantKey = tenantKey(tenant)
                    sql.update("UPDATE platform.tenants SET previous_kek_destroy_after = NULL WHERE id = ?", tenant)

                    run(moveJob())

                    tenantKey(tenant) shouldBe tenantKey
                    view(tenant).previousKekDestroyAfter.shouldNotBeNull()
                    systemAudit(tenant).shouldContainExactly(KEK_REPLACED)
                }
            }

            test("that fails for one tenant still moves the others") {
                Rotation().run {
                    val broken = tenant()
                    val healthy = tenant()
                    sql.update("DELETE FROM \"${TenantSchema(broken).name}\".tenant_keys")

                    run(moveJob())

                    view(broken).previousKekDestroyAfter.shouldBeNull()
                    view(healthy).previousKekDestroyAfter.shouldNotBeNull()
                }
            }
        }

        context("the previous KEK") {
            test("stays until the backups taken before the move have expired") {
                Rotation().run {
                    val tenant = tenant()
                    val previous = view(tenant).kekUri.shouldNotBeNull()
                    moved(tenant)

                    clock.offset = RETENTION.minusMinutes(5)
                    run(previousJob())

                    view(tenant).previousKekUri shouldBe previous
                    kms.wrap(previous, ByteArray(32))
                }
            }

            test("is destroyed after that, and the data still opens with the new KEK") {
                Rotation().run {
                    val tenant = tenant()
                    val user = invite(tenant)
                    val previous = view(tenant).kekUri.shouldNotBeNull()
                    moved(tenant)

                    clock.offset = RETENTION.plusMinutes(5)
                    run(previousJob())

                    shouldThrow<KeyNotFoundException> { kms.wrap(previous, ByteArray(32)) }
                    view(tenant).previousKekUri.shouldBeNull()
                    view(tenant).previousKekDestroyAfter.shouldBeNull()
                    inTenant(tenant) { emailOf(user) } shouldBe EMAIL
                    systemAudit(tenant).shouldContainExactly(KEK_REPLACED, PREVIOUS_KEK_DESTROYED)
                }
            }

            test("lets the next move begin once it is gone") {
                Rotation().run {
                    val tenant = tenant()
                    moved(tenant)
                    clock.offset = RETENTION.plusMinutes(5)
                    run(previousJob())
                    val second = view(tenant).kekUri.shouldNotBeNull()

                    run(moveJob(newKeys = OPENBAO))

                    view(tenant).kekUri.shouldNotBeNull() shouldStartWith "$OPENBAO://"
                    view(tenant).previousKekUri shouldBe second
                }
            }
        }

        context("a KEK URI cached before the move") {
            listOf(
                "while the previous KEK still exists" to Duration.ZERO,
                "after the previous KEK is destroyed" to RETENTION.plusMinutes(5),
            ).forEach { (moment, offset) ->
                test("is read again once $moment, and the data opens") {
                    Rotation().run {
                        val tenant = tenant()
                        val user = invite(tenant)
                        addDomain(tenant, "acme.$BASE_DOMAIN")
                        val resolver = TenantResolver(PlatformTransaction(worker), Duration.ofMinutes(5))
                        val stale = resolver.resolve("acme.$BASE_DOMAIN").shouldNotBeNull().context()
                        moved(tenant)
                        clock.offset = offset
                        run(previousJob())
                        resolver.resolve("acme.$BASE_DOMAIN")?.kekUri shouldBe stale.kekUri
                        kms.unwraps.set(0)

                        reader(stale) {
                            emailOf(user) shouldBe EMAIL
                            emailOf(user) shouldBe EMAIL
                        }

                        kms.unwraps.get() shouldBe 2
                    }
                }
            }
        }
    })
