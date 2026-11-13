package idp.worker.handlers

import idp.federation.application.FederationService
import idp.federation.application.NameIdFormat
import idp.federation.application.ServiceProviderManaged
import idp.identity.application.IdentityService
import idp.sharedkernel.audit.Actor
import idp.sharedkernel.crypto.KeyHierarchy
import idp.sharedkernel.migration.TenantSchema
import idp.sharedkernel.outbox.EventScope
import idp.sharedkernel.outbox.OutboxEvent
import idp.sharedkernel.tenant.TenantContext
import idp.sharedkernel.tenant.TenantStatus
import idp.sharedkernel.testing.TestMailpit
import idp.tenancy.application.TenancyEvents
import idp.tenancy.application.TenancyService
import idp.worker.WorkerApplication
import idp.worker.WorkerEnvironment
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.springframework.boot.runApplication
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

private const val OPERATOR_EMAIL = "first.operator@platform.example"
private const val OPS_ENTITY_ID = "https://ops.idp.test/saml2/metadata"

private fun TenantContext.table(name: String) = "\"${TenantSchema(tenantId).name}\".$name"

private fun bootstrapped(tenant: UUID) =
    OutboxEvent(
        UUID.randomUUID(),
        tenant,
        TenancyEvents.AGGREGATE,
        tenant,
        1,
        TenancyEvents.SERVICE_TENANT_BOOTSTRAPPED,
        UUID.randomUUID(),
        null,
        0,
        "{}",
    )

class OpsServiceProviderHandlerTest :
    FunSpec({
        val mailpit = TestMailpit.start()
        val environment = WorkerEnvironment()
        val context =
            runApplication<WorkerApplication>(
                *environment.arguments,
                "--spring.mail.host=${mailpit.host}",
                "--spring.mail.port=${mailpit.smtpPort}",
                "--spring.mail.properties.mail.smtp.auth=false",
                "--spring.mail.properties.mail.smtp.starttls.enable=false",
                "--spring.mail.properties.mail.smtp.starttls.required=false",
            )
        val tenancy = context.getBean(TenancyService::class.java)
        val federation = context.getBean(FederationService::class.java)
        val identity = context.getBean(IdentityService::class.java)
        val handler = context.getBean(OpsServiceProviderHandler::class.java)
        val sql = environment.jdbc

        afterSpec {
            context.close()
            mailpit.close()
        }

        val application = environment.operatorService().bootstrapServiceTenant("Platform", OPERATOR_EMAIL, Actor.Operator("alice"))
        val tenantId = checkNotNull(environment.operatorService().application(application)).tenantId

        suspend fun serviceTenant(): TenantContext {
            lateinit var tenant: TenantContext
            eventually(60.seconds) {
                val view = tenancy.tenant(tenantId).shouldNotBeNull()
                view.status shouldBe TenantStatus.ACTIVE
                tenant = TenantContext(tenantId, view.kekUri)
                TenantContext.with(tenant) { federation.serviceProviderByEntityId(OPS_ENTITY_ID) }.shouldNotBeNull()
            }
            return tenant
        }

        fun TenantContext.passkeyRequired(): Boolean =
            sql.queryForObject("SELECT passkey_required FROM ${table("tenant_policies")}", Boolean::class.java)!!

        fun TenantContext.journal(): List<String?> =
            sql.queryForList("SELECT action FROM ${table("audit_entries")} ORDER BY occurred_at, id", String::class.java)

        test("runs in the schema of the tenant, under its role") {
            handler.scope shouldBe EventScope.TENANT
            val tenant = serviceTenant()

            sql.queryForList("SELECT handler FROM ${tenant.table("processed_events")}", String::class.java) shouldContain
                "ops-service-provider"
        }

        test("bootstrap registers ops at the service tenant, requires passkeys and assigns the first operator") {
            val tenant = serviceTenant()

            TenantContext.with(tenant) {
                val sp = federation.serviceProviderByEntityId(OPS_ENTITY_ID).shouldNotBeNull()
                sp.name shouldBe "ops"
                sp.acs.map { it.url }.shouldContainExactly("https://ops.idp.test/login/saml2/sso/ops-sso")
                sp.authnRequestsSigned shouldBe false
                sp.settings.nameIdFormat shouldBe NameIdFormat.EMAIL
                federation.assignedUsers(sp.id).shouldContainExactly(identity.findByEmail(OPERATOR_EMAIL).shouldNotBeNull().id)
                val admin = Actor.User(identity.findByEmail(OPERATOR_EMAIL).shouldNotBeNull().id)
                shouldThrow<ServiceProviderManaged> { federation.removeServiceProvider(sp.id, admin) }
            }
            tenant.passkeyRequired() shouldBe true
            sql.queryForObject("SELECT passkeys_locked FROM ${tenant.table("tenant_policies")}", Boolean::class.java) shouldBe true
            sql.queryForList(
                "SELECT DISTINCT actor_type FROM ${tenant.table("audit_entries")} WHERE action IN " +
                    "('sp.registered', 'sp.settings_changed', 'policy.changed', 'sp.user_assigned')",
                String::class.java,
            ) shouldBe listOf("SYSTEM")
        }

        test("a repeated event changes nothing") {
            val tenant = serviceTenant()
            eventually(60.seconds) {
                sql.queryForObject(
                    "SELECT count(*) FROM platform.outbox WHERE tenant_id = ? AND status <> 'DONE'",
                    Int::class.java,
                    tenantId,
                ) shouldBe 0
            }
            val journal = tenant.journal()
            val policyVersion = sql.queryForObject("SELECT version FROM ${tenant.table("tenant_policies")}", Long::class.java)

            TenantContext.with(tenant) { handler.handle(bootstrapped(tenantId)) }

            TenantContext.with(tenant) {
                federation.serviceProviders().map { it.entityId }.shouldContainExactly(OPS_ENTITY_ID)
                federation.assignedUsers(federation.serviceProviders().single().id).size shouldBe 1
            }
            sql.queryForObject("SELECT version FROM ${tenant.table("tenant_policies")}", Long::class.java) shouldBe policyVersion
            tenant.journal() shouldBe journal
        }

        test("before provisioning invited the first admin it fails and changes nothing, so the outbox brings it back") {
            val id = environment.database.tenantSchema()
            val kekUri = environment.kms.createKey("tenant-${UUID.randomUUID()}")
            sql.update("INSERT INTO platform.tenants (id, name, status, kek_uri) VALUES (?, 'Platform', 'PROVISIONING', ?)", id, kekUri)
            val tenant = TenantContext(id, kekUri)
            TenantContext.with(tenant) { context.getBean(KeyHierarchy::class.java).createTenantKey() }

            shouldThrow<IllegalStateException> { TenantContext.with(tenant) { handler.handle(bootstrapped(id)) } }

            TenantContext.with(tenant) { federation.serviceProviderByEntityId(OPS_ENTITY_ID) }.shouldBeNull()
            tenant.passkeyRequired() shouldBe false
            tenant.journal().shouldBeEmpty()
        }
    })
