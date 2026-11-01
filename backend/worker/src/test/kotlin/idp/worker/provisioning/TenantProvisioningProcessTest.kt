package idp.worker.provisioning

import idp.audit.application.OperatorAuditService
import idp.sharedkernel.audit.Actor
import idp.sharedkernel.audit.AuditLog
import idp.sharedkernel.audit.AuditRecord
import idp.sharedkernel.crypto.FieldCipher
import idp.sharedkernel.crypto.KeyHierarchy
import idp.sharedkernel.crypto.KeyManagementService
import idp.sharedkernel.crypto.KeyManagementUnavailableException
import idp.sharedkernel.crypto.adapter.OpenBaoKeyManagementService
import idp.sharedkernel.migration.SchemaDrift
import idp.sharedkernel.migration.TenantMigrationRunner
import idp.sharedkernel.migration.TenantSchema
import idp.sharedkernel.migration.TenantSchemaFactory
import idp.sharedkernel.outbox.EventScope
import idp.sharedkernel.outbox.IdempotentHandler
import idp.sharedkernel.outbox.OutboxDispatcher
import idp.sharedkernel.outbox.OutboxEvent
import idp.sharedkernel.outbox.OutboxPublisher
import idp.sharedkernel.tenant.PlatformTransaction
import idp.sharedkernel.tenant.TenantStatus
import idp.sharedkernel.tenant.TenantTransaction
import idp.sharedkernel.testing.TestDatabase
import idp.sharedkernel.testing.TestOpenBao
import idp.tenancy.application.NewApplication
import idp.tenancy.application.TenancyEvents
import idp.tenancy.application.TenancyNotFound
import idp.tenancy.application.TenancyService
import idp.tenancy.application.TenantView
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.postgresql.util.PSQLException
import org.springframework.jdbc.core.JdbcTemplate
import java.io.PrintWriter
import java.nio.file.Path
import java.sql.Connection
import java.sql.SQLException
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import javax.sql.DataSource
import kotlin.io.path.listDirectoryEntries

private const val BASE_DOMAIN = "idp.test"
private const val NEXT_RELEASE = "classpath:fixtures/migrations/next"
private const val CURRENT_RELEASE = "classpath:db/tenant"
private val OPERATOR = Actor.Operator("alice")

private class SwitchableDataSource(
    private val delegate: DataSource,
) : DataSource {
    @Volatile var down = false

    override fun getConnection(): Connection = if (down) throw SQLException("database is down") else delegate.connection

    override fun getConnection(
        username: String?,
        password: String?,
    ): Connection = throw UnsupportedOperationException()

    override fun getLogWriter(): PrintWriter = delegate.logWriter

    override fun setLogWriter(out: PrintWriter?) = throw UnsupportedOperationException()

    override fun setLoginTimeout(seconds: Int) = throw UnsupportedOperationException()

    override fun getLoginTimeout(): Int = delegate.loginTimeout

    override fun getParentLogger(): java.util.logging.Logger = delegate.parentLogger

    override fun <T : Any?> unwrap(iface: Class<T>?): T = throw UnsupportedOperationException()

    override fun isWrapperFor(iface: Class<*>?): Boolean = false
}

private class ObservedKms(
    private val delegate: KeyManagementService,
) : KeyManagementService by delegate {
    val created = CopyOnWriteArrayList<String>()

    @Volatile var creationDown = false

    @Volatile var wrappingDown = false

    @Volatile var afterWrap: () -> Unit = {}

    override fun createKey(alias: String): String {
        if (creationDown) throw KeyManagementUnavailableException("down")
        return delegate.createKey(alias).also { created += it }
    }

    override fun wrap(
        kekUri: String,
        plaintext: ByteArray,
    ): ByteArray {
        if (wrappingDown) throw KeyManagementUnavailableException("down")
        return delegate.wrap(kekUri, plaintext).also { afterWrap() }
    }
}

private object UnusedAudit : AuditLog {
    override fun recordChange(record: AuditRecord) = throw UnsupportedOperationException()

    override fun recordDenial(record: AuditRecord) = throw UnsupportedOperationException()
}

private class Provisioning {
    val database = TestDatabase.create()
    val owner = database.owner().also { TenantMigrationRunner(it).migrateAll() }
    val sql = JdbcTemplate(owner)
    val worker = database.pooled("worker_runtime")
    val kms = ObservedKms(OpenBaoKeyManagementService(TestOpenBao.address, TestOpenBao.TOKEN))
    val provisioner = database.role("provisioner", options = "-c role=idp_owner")
    val schemas = SwitchableDataSource(provisioner)
    val migrations = SwitchableDataSource(provisioner)
    val platform = SwitchableDataSource(worker)
    private val contacts = FieldCipher.platform(kms, kms.createKey("contacts-${UUID.randomUUID()}").also { kms.created.clear() })
    private val publisher = OutboxPublisher()
    val tenancy = TenancyService(PlatformTransaction(platform), UnusedAudit, publisher, contacts, BASE_DOMAIN)
    private val opsTransaction = PlatformTransaction(database.pooled("ops_runtime"))
    val ops = TenancyService(opsTransaction, OperatorAuditService(opsTransaction), publisher, contacts, BASE_DOMAIN)
    private val keys = KeyHierarchy(kms, TenantTransaction(worker), Duration.ofMinutes(1))

    fun process(runner: TenantMigrationRunner = TenantMigrationRunner(migrations)) =
        TenantProvisioningProcess(tenancy, TenantSchemaFactory(schemas), runner, kms, keys)

    fun approved(subdomain: String = "acme"): UUID {
        val application = ops.submit(NewApplication("Acme", subdomain, "admin@acme.example"), OPERATOR)
        ops.approve(application, OPERATOR)
        return ops.application(application).shouldNotBeNull().tenantId
    }

    fun event(tenant: UUID) =
        OutboxEvent(
            UUID.randomUUID(),
            tenant,
            TenancyEvents.AGGREGATE,
            tenant,
            aggregateVersion = 1,
            TenancyEvents.APPROVED,
            correlationId = UUID.randomUUID(),
            causationId = null,
            attempts = 1,
            json = "{}",
        )

    fun deliver(tenant: UUID) = process().handle(event(tenant))

    fun view(tenant: UUID): TenantView = ops.tenant(tenant).shouldNotBeNull()

    fun heal() {
        listOf(schemas, migrations, platform).forEach { it.down = false }
        kms.creationDown = false
        kms.wrappingDown = false
        kms.afterWrap = {}
    }

    fun wrappedKeys(tenant: UUID): List<ByteArray> =
        sql.queryForList("""SELECT wrapped_key FROM "${TenantSchema(tenant).name}".tenant_keys""", ByteArray::class.java).filterNotNull()

    fun appliedMigrations(tenant: UUID): Int? =
        sql.queryForObject(
            """SELECT count(*) FROM "${TenantSchema(tenant).name}".flyway_schema_history WHERE success AND version IS NOT NULL""",
            Int::class.java,
        )

    fun processedEvents(tenant: UUID): Int? =
        sql.queryForObject("""SELECT count(*) FROM "${TenantSchema(tenant).name}".processed_events""", Int::class.java)

    fun enter(
        role: String,
        tenant: UUID,
    ) {
        database.role(role).connection.use { connection ->
            connection.createStatement().use { it.execute("""SET ROLE "${TenantSchema(tenant).name}"""") }
        }
    }
}

private class Failure(
    val place: String,
    val lastStep: String?,
    val inject: Provisioning.() -> Unit,
)

private val FAILURES =
    listOf(
        Failure("creating the schema", null) { schemas.down = true },
        Failure("running the migrations", "schema") { migrations.down = true },
        Failure("creating the KEK", "migrations") { kms.creationDown = true },
        Failure("creating the tenant key", "migrations") { kms.wrappingDown = true },
        Failure("recording the keys step", "migrations") { kms.afterWrap = { platform.down = true } },
    )

private fun tenantMigrationFiles(): Int = Path.of("../db/src/main/resources/db/tenant").listDirectoryEntries("V*.sql").size

class TenantProvisioningProcessTest :
    FunSpec({
        context("the steps") {
            test("the first step creates the schema and the role of the tenant") {
                Provisioning().run {
                    val tenant = approved()
                    val name = TenantSchema(tenant).name

                    deliver(tenant)

                    sql.queryForObject("SELECT count(*) FROM pg_namespace WHERE nspname = ?", Int::class.java, name) shouldBe 1
                    sql.queryForObject("SELECT count(*) FROM pg_roles WHERE rolname = ?", Int::class.java, name) shouldBe 1
                }
            }

            test("the second step brings the schema to the latest migration") {
                Provisioning().run {
                    val tenant = approved()

                    deliver(tenant)

                    appliedMigrations(tenant) shouldBe tenantMigrationFiles()
                }
            }

            test("the third step creates a KEK and a tenant key under it") {
                Provisioning().run {
                    val tenant = approved()

                    deliver(tenant)

                    val kekUri = view(tenant).kekUri.shouldNotBeNull()
                    kekUri shouldBe "hcvault://transit/tenant-$tenant"
                    kms.unwrap(kekUri, wrappedKeys(tenant).single()).size shouldBe 32
                }
            }

            test("the tenant becomes active after the last step, which is stored by name") {
                Provisioning().run {
                    val tenant = approved()

                    deliver(tenant)

                    view(tenant).status shouldBe TenantStatus.ACTIVE
                    view(tenant).provisioningStep shouldBe "keys"
                }
            }

            test("two tenants get their own KEK and role") {
                Provisioning().run {
                    val first = approved("acme")
                    val second = approved("globex")

                    deliver(first)
                    deliver(second)

                    view(first).kekUri shouldNotBe view(second).kekUri
                    TenantSchema(first).name shouldNotBe TenantSchema(second).name
                }
            }
        }

        context("resuming") {
            FAILURES.forEach { failure ->
                test("a failure while ${failure.place} leaves the tenant at the last finished step") {
                    Provisioning().run {
                        val tenant = approved()
                        failure.inject(this)

                        shouldThrowAny { deliver(tenant) }

                        heal()
                        view(tenant).status shouldBe TenantStatus.PROVISIONING
                        view(tenant).provisioningStep shouldBe failure.lastStep
                    }
                }

                test("a failure while ${failure.place} is finished by the next delivery") {
                    Provisioning().run {
                        val tenant = approved()
                        failure.inject(this)
                        shouldThrowAny { deliver(tenant) }
                        heal()

                        deliver(tenant)

                        view(tenant).status shouldBe TenantStatus.ACTIVE
                        appliedMigrations(tenant) shouldBe tenantMigrationFiles()
                        wrappedKeys(tenant) shouldHaveSize 1
                    }
                }
            }

            test("a finished step does not run again") {
                Provisioning().run {
                    val tenant = approved()
                    kms.creationDown = true
                    shouldThrowAny { deliver(tenant) }
                    heal()
                    schemas.down = true
                    migrations.down = true

                    deliver(tenant)

                    view(tenant).status shouldBe TenantStatus.ACTIVE
                }
            }

            test("a tenant stopped between the last step and activation only activates") {
                Provisioning().run {
                    val tenant = approved()
                    kms.creationDown = true
                    shouldThrowAny { deliver(tenant) }
                    heal()
                    val kekUri = "hcvault://transit/tenant-$tenant"
                    tenancy.recordProvisioningStep(tenant, "keys", kekUri)
                    kms.creationDown = true

                    deliver(tenant)

                    view(tenant).status shouldBe TenantStatus.ACTIVE
                }
            }

            test("a step written by a newer release stops the older one") {
                Provisioning().run {
                    val tenant = approved()
                    tenancy.startProvisioning(tenant)
                    tenancy.recordProvisioningStep(tenant, "saml-keys")

                    shouldThrow<IllegalStateException> { deliver(tenant) }.message shouldContain "saml-keys"

                    view(tenant).status shouldBe TenantStatus.PROVISIONING
                }
            }
        }

        context("repeating a step") {
            test("the KEK alias is deterministic, so a repeat finds the same KEK") {
                Provisioning().run {
                    val tenant = approved()
                    kms.afterWrap = { platform.down = true }
                    shouldThrowAny { deliver(tenant) }
                    heal()

                    deliver(tenant)

                    kms.created shouldContainExactly List(2) { "hcvault://transit/tenant-$tenant" }
                    view(tenant).kekUri shouldBe kms.created.first()
                }
            }

            test("a repeat keeps the first tenant key") {
                Provisioning().run {
                    val tenant = approved()
                    kms.afterWrap = { platform.down = true }
                    shouldThrowAny { deliver(tenant) }
                    heal()
                    val first = wrappedKeys(tenant).single()

                    deliver(tenant)

                    wrappedKeys(tenant).single() shouldBe first
                }
            }

            test("another delivery of a finished tenant changes nothing") {
                Provisioning().run {
                    val tenant = approved()
                    deliver(tenant)
                    val finished = view(tenant)

                    deliver(tenant)

                    view(tenant) shouldBe finished
                    kms.created shouldHaveSize 1
                }
            }

            test("a late delivery does not touch a tenant that moved on") {
                Provisioning().run {
                    val tenant = approved()
                    deliver(tenant)
                    ops.suspend(tenant, OPERATOR)

                    deliver(tenant)

                    view(tenant).status shouldBe TenantStatus.SUSPENDED
                }
            }

            test("an event of a missing tenant fails instead of finishing silently") {
                Provisioning().run {
                    shouldThrow<TenancyNotFound> { deliver(UUID.randomUUID()) }
                }
            }
        }

        context("database roles") {
            listOf("app_runtime", "worker_runtime", "idp_owner").forEach { role ->
                test("$role can enter the new tenant role") {
                    Provisioning().run {
                        val tenant = approved()

                        deliver(tenant)

                        enter(role, tenant)
                    }
                }
            }

            test("ops_runtime cannot enter the new tenant role") {
                Provisioning().run {
                    val tenant = approved()

                    deliver(tenant)

                    shouldThrow<PSQLException> { enter("ops_runtime", tenant) }.message shouldContain "permission denied"
                }
            }
        }

        context("delivery through the outbox") {
            fun Provisioning.dispatcher() =
                OutboxDispatcher(PlatformTransaction(worker), IdempotentHandler(TenantTransaction(worker)), listOf(process()))

            test("the handler declares the platform scope and listens to the approval") {
                Provisioning().run {
                    process().scope shouldBe EventScope.PLATFORM
                    process().eventType shouldBe TenancyEvents.APPROVED
                }
            }

            test("the event of an approval provisions the tenant") {
                Provisioning().run {
                    val tenant = approved()

                    dispatcher().dispatch()

                    view(tenant).status shouldBe TenantStatus.ACTIVE
                    sql.queryForObject("SELECT status FROM platform.outbox", String::class.java) shouldBe "DONE"
                }
            }

            test("idempotency comes from the step in platform.tenants and not from processed_events") {
                Provisioning().run {
                    val tenant = approved()
                    dispatcher().dispatch()
                    sql.update("UPDATE platform.outbox SET status = 'PENDING', attempts = 0, available_at = now()")

                    dispatcher().dispatch()

                    processedEvents(tenant) shouldBe 0
                    kms.created shouldHaveSize 1
                    sql.queryForObject("SELECT status FROM platform.outbox", String::class.java) shouldBe "DONE"
                }
            }
        }

        context("a release in progress") {
            test("a tenant created by the old jar is caught up by another run of the migrator") {
                Provisioning().run {
                    val tenant = approved()
                    val oldJar = TenantMigrationRunner(provisioner, tenantLocations = listOf(CURRENT_RELEASE))
                    val newMigrator = TenantMigrationRunner(owner, tenantLocations = listOf(CURRENT_RELEASE, NEXT_RELEASE))
                    process(oldJar).handle(event(tenant))
                    newMigrator.drift() shouldHaveSize 1

                    newMigrator.migrateAll()

                    newMigrator.drift().shouldBeEmpty()
                    newMigrator.verify()
                    appliedMigrations(tenant) shouldBe tenantMigrationFiles() + 1
                }
            }

            test("the drift names the tenant the old jar left behind") {
                Provisioning().run {
                    val tenant = approved()
                    val oldJar = TenantMigrationRunner(provisioner, tenantLocations = listOf(CURRENT_RELEASE))
                    val newMigrator = TenantMigrationRunner(owner, tenantLocations = listOf(CURRENT_RELEASE, NEXT_RELEASE))

                    process(oldJar).handle(event(tenant))

                    val drift: SchemaDrift = newMigrator.drift().single()
                    drift.schema shouldBe TenantSchema(tenant).name
                    drift.current.shouldNotBeNull() shouldNotBe drift.expected
                }
            }
        }

        test("the contact is gone once the tenant is active") {
            Provisioning().run {
                val tenant = approved()

                deliver(tenant)

                tenancy.contact(tenant).shouldBeNull()
            }
        }
    })
