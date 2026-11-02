package idp.worker.offboarding

import idp.audit.application.AuditService
import idp.audit.application.OperatorAuditService
import idp.identity.application.IdentityService
import idp.sharedkernel.audit.Actor
import idp.sharedkernel.audit.AuditLog
import idp.sharedkernel.audit.AuditRecord
import idp.sharedkernel.crypto.FieldCipher
import idp.sharedkernel.crypto.KeyHierarchy
import idp.sharedkernel.crypto.KeyManagementService
import idp.sharedkernel.crypto.KeyManagementUnavailableException
import idp.sharedkernel.crypto.KeyNotFoundException
import idp.sharedkernel.crypto.UserKeyNotFoundException
import idp.sharedkernel.crypto.adapter.OpenBaoKeyManagementService
import idp.sharedkernel.erasure.Erasure
import idp.sharedkernel.erasure.ErasureJournal
import idp.sharedkernel.erasure.ErasureJournalUnavailableException
import idp.sharedkernel.erasure.adapter.FileErasureJournal
import idp.sharedkernel.migration.TenantSchema
import idp.sharedkernel.migration.TenantSchemaFactory
import idp.sharedkernel.outbox.EventScope
import idp.sharedkernel.outbox.IdempotentHandler
import idp.sharedkernel.outbox.OutboxDispatcher
import idp.sharedkernel.outbox.OutboxEvent
import idp.sharedkernel.outbox.OutboxPublisher
import idp.sharedkernel.security.UserRole
import idp.sharedkernel.tenant.PlatformTransaction
import idp.sharedkernel.tenant.TenantContext
import idp.sharedkernel.tenant.TenantStatus
import idp.sharedkernel.tenant.TenantTransaction
import idp.sharedkernel.testing.MovableClock
import idp.sharedkernel.testing.TestDatabase
import idp.sharedkernel.testing.TestOpenBao
import idp.tenancy.application.TenancyEvents
import idp.tenancy.application.TenancyService
import idp.tenancy.application.TenantView
import idp.tenancy.domain.ForbiddenTenantChange
import idp.worker.SwitchableDataSource
import idp.worker.handlers.ErasureJournalHandler
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.springframework.jdbc.core.JdbcTemplate
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.readText

private const val BASE_DOMAIN = "idp.test"
private const val EMAIL = "ada@acme.test"
private val GRACE: Duration = Duration.ofDays(30)
private val OPERATOR = Actor.Operator("alice")
private val ADMIN = Actor.User(UUID.randomUUID())

/** Retries wait at most this long with the defaults of the dispatcher. */
private val RETRY: Duration = Duration.ofHours(2)

private class ObservedKms(
    private val delegate: KeyManagementService,
) : KeyManagementService by delegate {
    val destroyed = CopyOnWriteArrayList<String>()

    @Volatile var down = false

    override fun destroy(kekUri: String) {
        if (down) throw KeyManagementUnavailableException("down")
        delegate.destroy(kekUri)
        destroyed += kekUri
    }
}

private class SwitchableJournal(
    private val delegate: ErasureJournal,
) : ErasureJournal by delegate {
    @Volatile var down = false

    override fun record(erasure: Erasure) {
        if (down) throw ErasureJournalUnavailableException("down")
        delegate.record(erasure)
    }
}

private object UnusedAudit : AuditLog {
    override fun recordChange(record: AuditRecord) = throw UnsupportedOperationException()

    override fun recordDenial(record: AuditRecord) = throw UnsupportedOperationException()
}

private class Erasures {
    val database = TestDatabase.migrated()
    val owner = database.owner()
    val sql = JdbcTemplate(owner)
    val worker = database.pooled("worker_runtime")
    val clock = MovableClock()
    val kms = ObservedKms(OpenBaoKeyManagementService(TestOpenBao.address, TestOpenBao.TOKEN))
    val schemas = SwitchableDataSource(database.role("provisioner", options = "-c role=idp_owner"))
    val journalFile: Path = Files.createTempDirectory("erasures").resolve("journal")
    val journal = SwitchableJournal(FileErasureJournal(journalFile))
    private val publisher = OutboxPublisher()
    private val contacts = FieldCipher.platform(kms, kms.createKey("contacts-${UUID.randomUUID()}"))
    private val opsTransaction = PlatformTransaction(database.pooled("ops_runtime"))
    val ops = TenancyService(opsTransaction, OperatorAuditService(opsTransaction), publisher, contacts, BASE_DOMAIN, clock, GRACE)
    val tenancy = TenancyService(PlatformTransaction(worker), UnusedAudit, publisher, contacts, BASE_DOMAIN, clock)
    private val tenantTransaction = TenantTransaction(worker)
    val keys = KeyHierarchy(kms, tenantTransaction, PlatformTransaction(worker), Duration.ofMinutes(1))
    val identity = IdentityService(tenantTransaction, keys, AuditService(tenantTransaction, keys), publisher)

    fun process() = TenantOffboardingProcess(tenancy, TenantSchemaFactory(schemas), kms, journal)

    fun dispatcher() =
        OutboxDispatcher(
            PlatformTransaction(worker),
            IdempotentHandler(TenantTransaction(worker)),
            listOf(process(), ErasureJournalHandler(journal)),
            clock,
        )

    fun drain() {
        do {
            val ran = dispatcher().dispatch()
        } while (ran > 0)
    }

    /** A schema of its own, because offboarding drops the role, and pooled roles are shared by every clone. */
    fun tenant(): UUID {
        val id = database.ownTenantSchema()
        val kek = kms.createKey("tenant-${UUID.randomUUID()}")
        sql.update("INSERT INTO platform.tenants (id, name, status, kek_uri) VALUES (?, 'Acme', 'ACTIVE', ?)", id, kek)
        TenantContext.with(TenantContext(id, kek)) { keys.createTenantKey() }
        return id
    }

    fun offboarding(): UUID {
        val tenant = tenant()
        ops.suspend(tenant, OPERATOR)
        ops.startOffboarding(tenant, OPERATOR)
        return tenant
    }

    fun view(tenant: UUID): TenantView = ops.tenant(tenant).shouldNotBeNull()

    fun <T> inTenant(
        tenant: UUID,
        block: IdentityService.() -> T,
    ): T = TenantContext.with(TenantContext(tenant, view(tenant).kekUri)) { identity.block() }

    fun schemaExists(tenant: UUID): Boolean = TenantSchemaFactory(owner).exists(tenant)

    fun roleExists(tenant: UUID): Boolean =
        sql.queryForObject("SELECT count(*) FROM pg_roles WHERE rolname = ?", Int::class.java, TenantSchema(tenant).name) == 1

    fun outboxStatus(type: String): List<String?> =
        sql.queryForList("SELECT status FROM platform.outbox WHERE event_type = ?", String::class.java, type)

    fun heal() {
        schemas.down = false
        kms.down = false
        journal.down = false
    }

    fun afterRetry() {
        clock.offset += RETRY
    }
}

private class Failure(
    val place: String,
    val inject: Erasures.() -> Unit,
)

private val FAILURES =
    listOf(
        Failure("writing the journal") { journal.down = true },
        Failure("destroying the KEK") { kms.down = true },
        Failure("dropping the schema") { schemas.down = true },
    )

class ErasureTest :
    FunSpec({
        context("a user") {
            test("deleting the row in user_keys leaves the user's data unreadable") {
                Erasures().run {
                    val tenant = tenant()
                    val user = inTenant(tenant) { invite(EMAIL, UserRole.USER, ADMIN) }

                    sql.update("""DELETE FROM "${TenantSchema(tenant).name}".user_keys WHERE user_id = ?""", user)

                    shouldThrow<UserKeyNotFoundException> { inTenant(tenant) { emailOf(user) } }
                    sql.queryForObject(
                        """SELECT count(*) FROM "${TenantSchema(tenant).name}".users WHERE id = ?""",
                        Int::class.java,
                        user,
                    ) shouldBe 1
                }
            }

            test("worker writes the IDs alone to the journal on the event") {
                Erasures().run {
                    val tenant = tenant()
                    val user = inTenant(tenant) { invite(EMAIL, UserRole.USER, ADMIN) }

                    inTenant(tenant) { eraseUser(user, ADMIN) }
                    drain()

                    journal.entries().shouldContainExactly(Erasure.User(tenant, user))
                    journalFile.readText() shouldBe "users/$tenant/$user\n"
                    journalFile.readText() shouldNotContain EMAIL
                    outboxStatus("user.erased").shouldContainExactly("DONE")
                }
            }

            test("a journal that is down keeps the event for a retry") {
                Erasures().run {
                    val tenant = tenant()
                    val user = inTenant(tenant) { invite(EMAIL, UserRole.USER, ADMIN) }
                    inTenant(tenant) { eraseUser(user, ADMIN) }
                    journal.down = true
                    dispatcher().dispatch()
                    outboxStatus("user.erased").shouldContainExactly("PENDING")

                    heal()
                    afterRetry()
                    drain()

                    journal.entries().shouldContainExactly(Erasure.User(tenant, user))
                }
            }
        }

        context("offboarding") {
            test("runs in the platform scope after the waiting period") {
                Erasures().run {
                    process().scope shouldBe EventScope.PLATFORM
                    process().eventType shouldBe TenancyEvents.OFFBOARDING_STARTED
                }
            }

            test("waits for the end of the period and keeps everything until then") {
                Erasures().run {
                    val tenant = offboarding()

                    clock.offset = GRACE.minusMinutes(1)
                    drain()

                    view(tenant).status shouldBe TenantStatus.OFFBOARDING
                    schemaExists(tenant) shouldBe true
                    kms.destroyed.shouldBeEmpty()
                    journal.entries().shouldBeEmpty()
                }
            }

            test("destroys the KEK, drops the schema and the role, records the tenant and deletes it") {
                Erasures().run {
                    val tenant = offboarding()
                    val kek = view(tenant).kekUri.shouldNotBeNull()

                    clock.offset = GRACE
                    drain()

                    view(tenant).status shouldBe TenantStatus.DELETED
                    kms.destroyed.shouldContainExactly(kek)
                    shouldThrow<KeyNotFoundException> { kms.wrap(kek, ByteArray(32)) }
                    schemaExists(tenant) shouldBe false
                    roleExists(tenant) shouldBe false
                    journal.entries().shouldContainExactly(Erasure.Tenant(tenant))
                }
            }

            test("destroys the previous KEK of a tenant that moved, because backups still open with it") {
                Erasures().run {
                    val tenant = offboarding()
                    val kek = view(tenant).kekUri.shouldNotBeNull()
                    val previous = kms.createKey("tenant-${UUID.randomUUID()}")
                    sql.update("UPDATE platform.tenants SET previous_kek_uri = ? WHERE id = ?", previous, tenant)

                    clock.offset = GRACE
                    drain()

                    view(tenant).status shouldBe TenantStatus.DELETED
                    kms.destroyed.shouldContainExactly(kek, previous)
                    shouldThrow<KeyNotFoundException> { kms.wrap(previous, ByteArray(32)) }
                }
            }

            test("works without a tenant context and processed_events, whose schema it drops") {
                Erasures().run {
                    val tenant = offboarding()
                    val contexts = CopyOnWriteArrayList<TenantContext?>()
                    val observed =
                        object : ErasureJournal by journal {
                            override fun record(erasure: Erasure) {
                                contexts += TenantContext.currentOrNull()
                                journal.record(erasure)
                            }
                        }
                    val dispatcher =
                        OutboxDispatcher(
                            PlatformTransaction(worker),
                            IdempotentHandler(TenantTransaction(worker)),
                            listOf(TenantOffboardingProcess(tenancy, TenantSchemaFactory(schemas), kms, observed)),
                            clock,
                        )

                    clock.offset = GRACE
                    dispatcher.dispatch()

                    contexts.shouldContainExactly(null)
                    outboxStatus(TenancyEvents.OFFBOARDING_STARTED).shouldContainExactly("DONE")
                    view(tenant).status shouldBe TenantStatus.DELETED
                }
            }

            test("a cancelled offboarding destroys nothing when its time comes") {
                Erasures().run {
                    val tenant = offboarding()
                    ops.cancelOffboarding(tenant, OPERATOR)

                    clock.offset = GRACE
                    drain()

                    view(tenant).status shouldBe TenantStatus.SUSPENDED
                    schemaExists(tenant) shouldBe true
                    kms.destroyed.shouldBeEmpty()
                    journal.entries().shouldBeEmpty()
                    outboxStatus(TenancyEvents.OFFBOARDING_STARTED).shouldContainExactly("DONE")
                }
            }

            test("an offboarding started again runs at its own time, not at the time of the cancelled one") {
                Erasures().run {
                    val tenant = offboarding()
                    ops.cancelOffboarding(tenant, OPERATOR)
                    clock.offset = Duration.ofDays(10)
                    ops.startOffboarding(tenant, OPERATOR)

                    clock.offset = GRACE
                    drain()
                    view(tenant).status shouldBe TenantStatus.OFFBOARDING

                    clock.offset = GRACE.plusDays(10)
                    drain()
                    view(tenant).status shouldBe TenantStatus.DELETED
                }
            }

            test("a worker whose clock is behind the period refuses before it destroys anything") {
                Erasures().run {
                    val tenant = offboarding()
                    val event =
                        OutboxEvent(
                            UUID.randomUUID(),
                            tenant,
                            TenancyEvents.AGGREGATE,
                            tenant,
                            view(tenant).version,
                            TenancyEvents.OFFBOARDING_STARTED,
                            correlationId = UUID.randomUUID(),
                            causationId = null,
                            attempts = 1,
                            json = "{}",
                        )

                    shouldThrow<ForbiddenTenantChange> { process().handle(event) }

                    kms.destroyed.shouldBeEmpty()
                    journal.entries().shouldBeEmpty()
                    schemaExists(tenant) shouldBe true
                }
            }

            FAILURES.forEach { failure ->
                test("a failure while ${failure.place} is finished by the next delivery from the KEK URI in platform.tenants") {
                    Erasures().run {
                        val tenant = offboarding()
                        val kek = view(tenant).kekUri.shouldNotBeNull()
                        clock.offset = GRACE
                        failure.inject(this)

                        dispatcher().dispatch()
                        heal()
                        outboxStatus(TenancyEvents.OFFBOARDING_STARTED).shouldContainExactly("PENDING")
                        view(tenant).status shouldBe TenantStatus.OFFBOARDING
                        view(tenant).kekUri shouldBe kek

                        afterRetry()
                        drain()

                        view(tenant).status shouldBe TenantStatus.DELETED
                        kms.destroyed.toSet().shouldContainExactly(kek)
                        schemaExists(tenant) shouldBe false
                        roleExists(tenant) shouldBe false
                        journal.entries().shouldContainExactly(Erasure.Tenant(tenant))
                    }
                }
            }

            test("a crash after the schema is gone and before the status changed is finished by the next delivery") {
                Erasures().run {
                    val tenant = offboarding()
                    val kek = view(tenant).kekUri.shouldNotBeNull()
                    journal.record(Erasure.Tenant(tenant))
                    kms.destroy(kek)
                    TenantSchemaFactory(owner).drop(tenant)

                    clock.offset = GRACE
                    drain()

                    view(tenant).status shouldBe TenantStatus.DELETED
                    outboxStatus(TenancyEvents.OFFBOARDING_STARTED).shouldContainExactly("DONE")
                }
            }
        }
    })
