package idp.worker.handlers

import idp.authentication.application.AuthenticationService
import idp.identity.application.IdentityService
import idp.sharedkernel.audit.Actor
import idp.sharedkernel.crypto.KeyHierarchy
import idp.sharedkernel.migration.TenantMigrationRunner
import idp.sharedkernel.migration.TenantSchema
import idp.sharedkernel.migration.TenantSchemaFactory
import idp.sharedkernel.outbox.OutboxEvent
import idp.sharedkernel.security.UserRole
import idp.sharedkernel.security.UserStatus
import idp.sharedkernel.tenant.TenantContext
import idp.sharedkernel.testing.ReceivedMail
import idp.sharedkernel.testing.TestMailpit
import idp.tenancy.application.TenancyEvents
import idp.worker.WorkerApplication
import idp.worker.WorkerEnvironment
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.springframework.boot.runApplication
import org.springframework.context.ConfigurableApplicationContext
import java.security.MessageDigest
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

private const val ADMIN = "new.admin@acme.test"
private val OPERATOR = Actor.Operator("alice")
private val TOKEN = Regex("""#token=([\w-]+)""")

private fun WorkerEnvironment.tenant(context: ConfigurableApplicationContext): TenantContext {
    val id = UUID.randomUUID()
    val kekUri = kms.createKey("tenant-$id")
    jdbc.update("INSERT INTO platform.tenants (id, name, status, kek_uri) VALUES (?, 'Acme', 'ACTIVE', ?)", id, kekUri)
    jdbc.update("INSERT INTO platform.tenant_domains (domain, tenant_id) VALUES (?, ?)", "${id.toString().take(8)}.idp.test", id)
    TenantSchemaFactory(owner).create(id)
    TenantMigrationRunner(owner).migrateTenant(id)
    return TenantContext(id, kekUri).also { tenant ->
        TenantContext.with(tenant) { context.getBean(KeyHierarchy::class.java).createTenantKey() }
    }
}

private fun TenantContext.table(name: String) = "\"${TenantSchema(tenantId).name}\".$name"

private fun tokenOf(mail: ReceivedMail): String = TOKEN.find(mail.text).shouldNotBeNull().groupValues[1]

class ReinviteAdminHandlerTest :
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
        val identity = context.getBean(IdentityService::class.java)
        val authentication = context.getBean(AuthenticationService::class.java)
        val operator = environment.operatorService()

        afterSpec {
            context.close()
            mailpit.close()
        }

        beforeTest { mailpit.clear() }

        suspend fun mails(count: Int): List<ReceivedMail> {
            lateinit var received: List<ReceivedMail>
            eventually(30.seconds) {
                received = mailpit.messages()
                received shouldHaveSize count
            }
            return received
        }

        suspend fun TenantContext.active(email: String): UUID {
            val user = TenantContext.with(this) { identity.invite(email, UserRole.TENANT_ADMIN, Actor.System) }
            val invitation = mails(1).single()
            TenantContext.with(this) { identity.acceptInvitation(tokenOf(invitation), Actor.Anonymous) }
            mailpit.clear()
            return user
        }

        fun reinvites(tenant: TenantContext): Int =
            environment.jdbc.queryForObject(
                "SELECT count(*) FROM platform.admin_reinvites WHERE tenant_id = ?",
                Int::class.java,
                tenant.tenantId,
            )!!

        test("a new address becomes an invited admin who gets the usual mail, and the contact is erased") {
            val tenant = environment.tenant(context)

            operator.requestAdminReinvite(tenant.tenantId, ADMIN, OPERATOR)

            val mail = mails(1).single()
            mail.to.shouldContainExactly(ADMIN)
            mail.text shouldContain "/invite#token="
            val user = TenantContext.with(tenant) { identity.findByEmail(ADMIN) }.shouldNotBeNull()
            user.role shouldBe UserRole.TENANT_ADMIN
            user.status shouldBe UserStatus.INVITED
            eventually(30.seconds) { reinvites(tenant) shouldBe 0 }
        }

        test("a first admin whose link expired gets a new one on the same account") {
            val tenant = environment.tenant(context)
            val first = TenantContext.with(tenant) { identity.inviteFirstAdmin(ADMIN) }
            val expired = tokenOf(mails(1).single())
            mailpit.clear()

            operator.requestAdminReinvite(tenant.tenantId, ADMIN, OPERATOR)

            val fresh = tokenOf(mails(1).single())
            TenantContext.with(tenant) { identity.invitationOwner(expired) }.shouldBeNull()
            TenantContext.with(tenant) { identity.invitationOwner(fresh) } shouldBe first
        }

        test("the only admin who lost everything starts over, and no session of the old life survives") {
            val tenant = environment.tenant(context)
            val admin = tenant.active(ADMIN)
            val session = UUID.randomUUID().toString()
            environment.jdbc.update(
                """
                INSERT INTO ${tenant.table("sso_sessions")} (id, user_id, token_hash, factors, authenticated_at, last_seen_at)
                VALUES (?, ?, ?, '{PASSWORD}', now(), now())
                """.trimIndent(),
                UUID.randomUUID(),
                admin,
                MessageDigest.getInstance("SHA-256").digest(session.toByteArray()),
            )
            TenantContext.with(tenant) { authentication.resolveSession(session) }.shouldNotBeNull()

            operator.requestAdminReinvite(tenant.tenantId, ADMIN, OPERATOR)

            val invitation = tokenOf(mails(1).single())
            TenantContext.with(tenant) { identity.user(admin) }.shouldNotBeNull().status shouldBe UserStatus.INVITED
            TenantContext.with(tenant) { identity.acceptInvitation(invitation, Actor.Anonymous) }
            TenantContext.with(tenant) { authentication.resolveSession(session) }.shouldBeNull()
        }

        test("the journal of the tenant shows a system action and never the operator") {
            val tenant = environment.tenant(context)
            tenant.active(ADMIN)

            operator.requestAdminReinvite(tenant.tenantId, ADMIN, OPERATOR)

            mails(1)
            val actors =
                environment.jdbc.queryForList(
                    "SELECT actor_type FROM ${tenant.table("audit_entries")} WHERE action = 'user.reinvited'",
                    String::class.java,
                )
            actors.shouldContainExactly("SYSTEM")
        }

        test("the address stays out of the outbox and the audit of the operator") {
            val tenant = environment.tenant(context)

            operator.requestAdminReinvite(tenant.tenantId, ADMIN, OPERATOR)

            environment.jdbc
                .queryForList("SELECT payload::text FROM platform.outbox WHERE tenant_id = ?", String::class.java, tenant.tenantId)
                .forEach { it.toString() shouldNotContain ADMIN }
            environment.jdbc
                .queryForList("SELECT * FROM platform.operator_audit WHERE tenant_id = ?", tenant.tenantId)
                .toString() shouldNotContain ADMIN
            mails(1)
        }

        test("a repeated event finds no contact and sends nothing") {
            val tenant = environment.tenant(context)
            operator.requestAdminReinvite(tenant.tenantId, ADMIN, OPERATOR)
            mails(1)
            eventually(30.seconds) { reinvites(tenant) shouldBe 0 }
            mailpit.clear()
            val handler = context.getBean(ReinviteAdminHandler::class.java)
            val event =
                OutboxEvent(
                    UUID.randomUUID(),
                    tenant.tenantId,
                    TenancyEvents.REINVITE_AGGREGATE,
                    UUID.randomUUID(),
                    0,
                    TenancyEvents.ADMIN_REINVITE_REQUESTED,
                    UUID.randomUUID(),
                    null,
                    0,
                    "{}",
                )

            handler.handle(event)

            mailpit.messages().shouldBeEmpty()
        }
    })
