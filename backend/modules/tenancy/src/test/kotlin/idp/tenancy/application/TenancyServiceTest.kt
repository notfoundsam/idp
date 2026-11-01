package idp.tenancy.application

import idp.sharedkernel.audit.Actor
import idp.sharedkernel.audit.AuditLog
import idp.sharedkernel.audit.AuditRecord
import idp.sharedkernel.audit.AuditTarget
import idp.sharedkernel.crypto.FieldCipher
import idp.sharedkernel.crypto.FieldDecryptionException
import idp.sharedkernel.crypto.adapter.OpenBaoKeyManagementService
import idp.sharedkernel.migration.TenantMigrationRunner
import idp.sharedkernel.outbox.OutboxPublisher
import idp.sharedkernel.persistence.VersionConflictException
import idp.sharedkernel.tenant.PlatformTransaction
import idp.sharedkernel.tenant.TenantStatus
import idp.sharedkernel.testing.TestDatabase
import idp.sharedkernel.testing.TestOpenBao
import idp.tenancy.adapters.persistence.Tenants
import idp.tenancy.domain.ForbiddenTenantChange
import idp.tenancy.domain.InvalidApplication
import idp.tenancy.domain.InvalidSubdomain
import idp.tenancy.domain.SubdomainTaken
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.jooq.exception.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID

private const val BASE_DOMAIN = "idp.test"
private const val CONTACT = "admin@acme.example"
private const val MASK = "a***@acme.example"
private const val KEK = "hcvault://tenant-key"
private const val IP = "198.51.100.7"
private val OPERATOR = Actor.Operator("alice")

private class RecordingAudit : AuditLog {
    val changes = mutableListOf<AuditRecord>()
    var failing = false

    override fun recordChange(record: AuditRecord) {
        check(!failing) { "audit is down" }
        changes += record
    }

    override fun recordDenial(record: AuditRecord) = throw UnsupportedOperationException()
}

private class Platform {
    val database = TestDatabase.create()
    val owner = database.owner()
    val sql = JdbcTemplate(owner)
    val audit = RecordingAudit()

    init {
        TenantMigrationRunner(owner).migrateAll()
    }

    private val name = "contacts-${UUID.randomUUID()}"
    private val root = OpenBaoKeyManagementService(TestOpenBao.address, TestOpenBao.TOKEN)
    private val kekUri = root.createKey(name)

    private val encryptOnly =
        TestOpenBao.tokenWithPolicy(
            "encrypt-$name",
            """
            path "transit/keys/$name" { capabilities = ["read"] }
            path "transit/encrypt/$name" { capabilities = ["update"] }
            """,
        )
    private val decryptOnly =
        TestOpenBao.tokenWithPolicy("decrypt-$name", """path "transit/decrypt/$name" { capabilities = ["update"] }""")

    val opsTransaction = PlatformTransaction(database.pooled("ops_runtime"))
    val ops = service(opsTransaction, encryptOnly)
    val worker = service(PlatformTransaction(database.pooled("worker_runtime")), decryptOnly)

    private fun service(
        transaction: PlatformTransaction,
        token: String,
    ): TenancyService {
        val contacts = FieldCipher.platform(OpenBaoKeyManagementService(TestOpenBao.address, token), kekUri)
        return TenancyService(transaction, audit, OutboxPublisher(), contacts, BASE_DOMAIN)
    }

    fun submit(
        subdomain: String = "acme",
        contact: String = CONTACT,
    ): UUID = ops.submit(NewApplication("Acme", subdomain, contact), OPERATOR)

    fun tenantOf(application: UUID): UUID = ops.application(application).shouldNotBeNull().tenantId

    fun approved(subdomain: String = "acme"): UUID {
        val application = submit(subdomain)
        ops.approve(application, OPERATOR)
        return tenantOf(application)
    }

    fun active(): UUID {
        val tenant = approved()
        worker.startProvisioning(tenant)
        worker.recordProvisioningStep(tenant, "keys", KEK)
        worker.activate(tenant)
        return tenant
    }

    fun statusOf(tenant: UUID) = ops.tenant(tenant).shouldNotBeNull().status

    fun domainsOf(tenant: UUID): List<String?> =
        sql.queryForList("SELECT domain FROM platform.tenant_domains WHERE tenant_id = ?", String::class.java, tenant)

    fun events(tenant: UUID): List<String?> =
        sql.queryForList(
            "SELECT event_type FROM platform.outbox WHERE aggregate_id = ? ORDER BY aggregate_version",
            String::class.java,
            tenant,
        )

    fun actions(): List<String> = audit.changes.map { it.action }

    fun ciphertext(application: UUID): ByteArray? =
        sql.queryForObject("SELECT contact_ciphertext FROM platform.tenant_applications WHERE id = ?", ByteArray::class.java, application)
}

class TenancyServiceTest :
    FunSpec({
        context("application") {
            test("a submitted application holds a requested tenant") {
                Platform().run {
                    val application = submit()

                    val view = ops.application(application).shouldNotBeNull()
                    view.companyName shouldBe "Acme"
                    view.subdomain shouldBe "acme"
                    view.status shouldBe "SUBMITTED"
                    statusOf(view.tenantId) shouldBe TenantStatus.REQUESTED
                    ops.tenant(view.tenantId).shouldNotBeNull().name shouldBe "Acme"
                    domainsOf(view.tenantId).shouldBeEmpty()
                }
            }

            test("submitting is audited with the operator and the tenant") {
                Platform().run {
                    val tenant = tenantOf(submit())

                    val record = audit.changes.single()
                    record.actor shouldBe OPERATOR
                    record.action shouldBe "tenant.application.submitted"
                    record.target shouldBe AuditTarget.tenant(tenant)
                }
            }

            test("the audit record does not carry the contact") {
                Platform().run {
                    submit()

                    audit.changes
                        .single()
                        .details
                        .toString() shouldNotContain "admin"
                    audit.changes
                        .single()
                        .details
                        .toString() shouldNotContain "acme.example"
                }
            }

            test("a blank company name stores nothing") {
                Platform().run {
                    shouldThrow<InvalidApplication> { ops.submit(NewApplication(" ", "acme", CONTACT), OPERATOR) }

                    sql.queryForObject("SELECT count(*) FROM platform.tenants", Int::class.java) shouldBe 0
                    sql.queryForObject("SELECT count(*) FROM platform.tenant_applications", Int::class.java) shouldBe 0
                }
            }

            test("a reserved subdomain is rejected") {
                Platform().run {
                    shouldThrow<InvalidSubdomain> { submit("ops-sso") }

                    audit.changes.shouldBeEmpty()
                }
            }

            test("the service tenant takes its reserved subdomain and goes on to the same domain") {
                Platform().run {
                    val application = ops.submitServiceTenant("Platform", CONTACT, OPERATOR)

                    ops.approve(application, OPERATOR)

                    ops.application(application).shouldNotBeNull().subdomain shouldBe "ops-sso"
                    domainsOf(tenantOf(application)).shouldContainExactly("ops-sso.idp.test")
                }
            }

            test("the service tenant cannot be submitted twice") {
                Platform().run {
                    ops.submitServiceTenant("Platform", CONTACT, OPERATOR)

                    shouldThrow<SubdomainTaken> { ops.submitServiceTenant("Platform", CONTACT, OPERATOR) }
                }
            }

            test("a subdomain in an open application is taken") {
                Platform().run {
                    submit("acme")

                    shouldThrow<SubdomainTaken> { submit("acme") }
                }
            }

            test("a subdomain of an approved tenant is taken") {
                Platform().run {
                    approved("acme")

                    shouldThrow<SubdomainTaken> { submit("acme") }
                }
            }
        }

        context("contact") {
            test("is stored encrypted, and worker reads it with the platform key") {
                Platform().run {
                    val application = submit()

                    ciphertext(application).shouldNotBeNull().decodeToString() shouldNotContain CONTACT
                    worker.contact(tenantOf(application)) shouldBe CONTACT
                }
            }

            test("is masked at input, because ops cannot read it back") {
                Platform().run {
                    val application = submit()

                    ops.application(application).shouldNotBeNull().contactMask shouldBe MASK
                    shouldThrow<DataAccessException> { ops.contact(tenantOf(application)) }.message shouldContain "permission denied"
                }
            }

            test("survives an approval by ops, which cannot read the ciphertext it writes back") {
                Platform().run {
                    val application = submit()
                    val stored = ciphertext(application)

                    ops.approve(application, OPERATOR)

                    ciphertext(application) shouldBe stored
                    worker.contact(tenantOf(application)) shouldBe CONTACT
                }
            }

            test("keeps no plaintext beside the mask") {
                Platform().run {
                    val application = submit()

                    val columns = "company_name, requested_subdomain, contact_mask, status"
                    sql
                        .queryForMap("SELECT $columns FROM platform.tenant_applications WHERE id = ?", application)
                        .toString() shouldNotContain CONTACT
                }
            }

            test("the mask keeps one letter of the local part") {
                Platform().run {
                    ops.application(submit(contact = "x@acme.example")).shouldNotBeNull().contactMask shouldBe "x***@acme.example"
                }
            }

            test("the mask does not depend on the length of the local part") {
                Platform().run {
                    val short = ops.application(submit("short", "ab@acme.example")).shouldNotBeNull()
                    val long = ops.application(submit("long", "abcdefgh@acme.example")).shouldNotBeNull()

                    short.contactMask shouldBe long.contactMask
                }
            }

            listOf(
                "",
                "admin",
                "@acme.example",
                "admin@",
                "a@b@c",
                "ad min@acme.example",
                "a@acme.example\n",
                "a".repeat(255) + "@acme.example",
            ).forEach { contact ->
                test("'${contact.take(20).replace("\n", "\\n")}' is rejected") {
                    Platform().run {
                        shouldThrow<InvalidApplication> { submit(contact = contact) }
                    }
                }
            }

            test("a rejected contact does not appear in the error") {
                Platform().run {
                    shouldThrow<InvalidApplication> { submit(contact = "secret-person") }.message shouldNotContain "secret-person"
                }
            }

            test("a ciphertext moved to another application does not decrypt") {
                Platform().run {
                    val first = submit("first")
                    val second = submit("second")
                    sql.update(
                        "UPDATE platform.tenant_applications SET contact_ciphertext = ? WHERE id = ?",
                        ciphertext(first),
                        second,
                    )

                    shouldThrow<FieldDecryptionException> { worker.contact(tenantOf(second)) }
                }
            }

            test("is erased after activation, together with the mask") {
                Platform().run {
                    val application = submit()
                    val tenant = tenantOf(application)
                    ops.approve(application, OPERATOR)
                    worker.startProvisioning(tenant)
                    worker.recordProvisioningStep(tenant, "keys", KEK)

                    worker.activate(tenant)

                    sql
                        .queryForObject(
                            "SELECT contact_ciphertext IS NULL AND contact_mask IS NULL FROM platform.tenant_applications WHERE id = ?",
                            Boolean::class.java,
                            application,
                        ) shouldBe true
                    worker.contact(tenant).shouldBeNull()
                    ops
                        .application(application)
                        .shouldNotBeNull()
                        .contactMask
                        .shouldBeNull()
                }
            }

            test("stays until activation") {
                Platform().run {
                    val tenant = approved()
                    worker.startProvisioning(tenant)
                    worker.recordProvisioningStep(tenant, "keys", KEK)

                    worker.contact(tenant) shouldBe CONTACT
                }
            }

            test("stays when activation is refused") {
                Platform().run {
                    val tenant = approved()
                    worker.startProvisioning(tenant)

                    shouldThrow<ForbiddenTenantChange> { worker.activate(tenant) }

                    worker.contact(tenant) shouldBe CONTACT
                }
            }
        }

        context("approval") {
            test("approves the tenant and the application and registers the domain") {
                Platform().run {
                    val application = submit()
                    val tenant = tenantOf(application)

                    ops.approve(application, OPERATOR)

                    statusOf(tenant) shouldBe TenantStatus.APPROVED
                    ops.application(application).shouldNotBeNull().status shouldBe "APPROVED"
                    domainsOf(tenant).shouldContainExactly("acme.idp.test")
                }
            }

            test("publishes one event with the tenant and its version") {
                Platform().run {
                    val tenant = approved()

                    val event = sql.queryForMap("SELECT * FROM platform.outbox WHERE aggregate_id = ?", tenant)
                    event["event_type"] shouldBe TenancyEvents.APPROVED
                    event["aggregate_type"] shouldBe TenancyEvents.AGGREGATE
                    event["aggregate_version"] shouldBe 1L
                    event["tenant_id"] shouldBe tenant
                    event["payload"].toString() shouldBe "{}"
                }
            }

            test("is audited") {
                Platform().run {
                    val tenant = approved()

                    actions().shouldContainExactly("tenant.application.submitted", "tenant.approved")
                    audit.changes.last().actor shouldBe OPERATOR
                    audit.changes.last().target shouldBe AuditTarget.tenant(tenant)
                }
            }

            test("cannot repeat") {
                Platform().run {
                    val application = submit()
                    ops.approve(application, OPERATOR)

                    shouldThrow<ForbiddenTenantChange> { ops.approve(application, OPERATOR) }

                    events(tenantOf(application)).shouldHaveSize(1)
                }
            }

            test("of a missing application is an error") {
                Platform().run {
                    shouldThrow<TenancyNotFound> { ops.approve(UUID.randomUUID(), OPERATOR) }
                }
            }

            test("rolls back everything when the domain went to another tenant") {
                Platform().run {
                    val application = submit()
                    val tenant = tenantOf(application)
                    val other = UUID.randomUUID()
                    sql.update("INSERT INTO platform.tenants (id, name, status) VALUES (?, 'other', 'ACTIVE')", other)
                    sql.update("INSERT INTO platform.tenant_domains (domain, tenant_id) VALUES ('acme.idp.test', ?)", other)

                    shouldThrow<SubdomainTaken> { ops.approve(application, OPERATOR) }

                    statusOf(tenant) shouldBe TenantStatus.REQUESTED
                    ops.application(application).shouldNotBeNull().status shouldBe "SUBMITTED"
                    events(tenant).shouldBeEmpty()
                }
            }

            test("rolls back when the audit fails") {
                Platform().run {
                    val application = submit()
                    val tenant = tenantOf(application)
                    audit.failing = true

                    shouldThrow<IllegalStateException> { ops.approve(application, OPERATOR) }

                    statusOf(tenant) shouldBe TenantStatus.REQUESTED
                    domainsOf(tenant).shouldBeEmpty()
                    events(tenant).shouldBeEmpty()
                }
            }
        }

        context("provisioning") {
            test("runs under worker_runtime from approved to active") {
                Platform().run {
                    val tenant = approved()

                    worker.startProvisioning(tenant)
                    statusOf(tenant) shouldBe TenantStatus.PROVISIONING

                    worker.recordProvisioningStep(tenant, "schema")
                    worker.recordProvisioningStep(tenant, "keys", KEK)
                    worker.tenant(tenant).shouldNotBeNull().provisioningStep shouldBe "keys"
                    worker.tenant(tenant).shouldNotBeNull().kekUri shouldBe KEK

                    worker.activate(tenant)
                    statusOf(tenant) shouldBe TenantStatus.ACTIVE
                }
            }

            test("every change moves the version") {
                Platform().run {
                    val tenant = approved()

                    worker.startProvisioning(tenant)
                    worker.recordProvisioningStep(tenant, "keys", KEK)

                    worker.tenant(tenant).shouldNotBeNull().version shouldBe 3
                }
            }

            test("a tenant that is not approved cannot start") {
                Platform().run {
                    val tenant = tenantOf(submit())

                    shouldThrow<ForbiddenTenantChange> { worker.startProvisioning(tenant) }
                }
            }

            test("a missing tenant is an error") {
                Platform().run {
                    shouldThrow<TenancyNotFound> { worker.startProvisioning(UUID.randomUUID()) }
                }
            }
        }

        context("suspension") {
            test("suspends and resumes an active tenant") {
                Platform().run {
                    val tenant = active()

                    ops.suspend(tenant, OPERATOR)
                    statusOf(tenant) shouldBe TenantStatus.SUSPENDED

                    ops.resume(tenant, OPERATOR)
                    statusOf(tenant) shouldBe TenantStatus.ACTIVE
                }
            }

            test("is audited in both directions") {
                Platform().run {
                    val tenant = active()

                    ops.suspend(tenant, OPERATOR)
                    ops.resume(tenant, OPERATOR)

                    actions().takeLast(2).shouldContainExactly("tenant.suspended", "tenant.resumed")
                    audit.changes.takeLast(2).forEach {
                        it.actor shouldBe OPERATOR
                        it.target shouldBe AuditTarget.tenant(tenant)
                    }
                }
            }

            test("publishes an event with the tenant, because its sessions are revoked in the tenant schema") {
                Platform().run {
                    val tenant = active()

                    ops.suspend(tenant, OPERATOR)

                    events(tenant).shouldContainExactly(TenancyEvents.APPROVED, TenancyEvents.SUSPENDED)
                    sql.queryForObject(
                        "SELECT tenant_id FROM platform.outbox WHERE event_type = ?",
                        UUID::class.java,
                        TenancyEvents.SUSPENDED,
                    ) shouldBe tenant
                }
            }

            test("of a tenant in provisioning is forbidden and leaves no trace") {
                Platform().run {
                    val tenant = approved()
                    val before = actions()

                    shouldThrow<ForbiddenTenantChange> { ops.suspend(tenant, OPERATOR) }

                    actions() shouldBe before
                    events(tenant).shouldContainExactly(TenancyEvents.APPROVED)
                }
            }
        }

        context("operator address") {
            test("reaches the record of every action") {
                Platform().run {
                    val service = ops.submitServiceTenant("Platform", CONTACT, OPERATOR, IP)
                    val application = ops.submit(NewApplication("Acme", "acme", CONTACT), OPERATOR, IP)
                    ops.approve(application, OPERATOR, IP)
                    val tenant = tenantOf(application)
                    worker.startProvisioning(tenant)
                    worker.recordProvisioningStep(tenant, "keys", KEK)
                    worker.activate(tenant)
                    ops.suspend(tenant, OPERATOR, IP)
                    ops.resume(tenant, OPERATOR, IP)

                    tenantOf(service).shouldNotBeNull()
                    audit.changes.map { it.ip }.shouldContainExactly(List(5) { IP })
                }
            }

            test("is absent when the action came from no address") {
                Platform().run {
                    submit()

                    audit.changes
                        .single()
                        .ip
                        .shouldBeNull()
                }
            }
        }

        context("optimistic lock") {
            test("a tenant read before a concurrent change cannot overwrite it") {
                Platform().run {
                    val tenant = active()
                    val tenants = Tenants()
                    val stale = opsTransaction.execute { dsl -> tenants.find(dsl, tenant) }.shouldNotBeNull()
                    ops.suspend(tenant, OPERATOR)
                    stale.suspend()

                    shouldThrow<VersionConflictException> { opsTransaction.execute { dsl -> tenants.save(dsl, stale) } }

                    ops.tenant(tenant).shouldNotBeNull().version shouldBe stale.version + 1
                }
            }
        }

        test("reads return nothing for an unknown id") {
            Platform().run {
                ops.tenant(UUID.randomUUID()).shouldBeNull()
                ops.application(UUID.randomUUID()).shouldBeNull()
                worker.contact(UUID.randomUUID()).shouldBeNull()
            }
        }
    })
