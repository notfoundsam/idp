package idp.worker.handlers

import idp.authentication.application.AuthenticationService
import idp.sharedkernel.audit.Actor
import idp.sharedkernel.crypto.KeyHierarchy
import idp.sharedkernel.migration.TenantMigrationRunner
import idp.sharedkernel.migration.TenantSchema
import idp.sharedkernel.migration.TenantSchemaFactory
import idp.sharedkernel.tenant.TenantContext
import idp.sharedkernel.tenant.TenantStatus
import idp.tenancy.application.TenancyService
import idp.worker.WorkerApplication
import idp.worker.WorkerEnvironment
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.springframework.boot.runApplication
import org.springframework.context.ConfigurableApplicationContext
import java.security.MessageDigest
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

private fun WorkerEnvironment.tenant(context: ConfigurableApplicationContext): TenantContext {
    val id = UUID.randomUUID()
    val kekUri = kms.createKey("tenant-$id")
    jdbc.update("INSERT INTO platform.tenants (id, name, status, kek_uri) VALUES (?, 'tenant', 'ACTIVE', ?)", id, kekUri)
    TenantSchemaFactory(owner).create(id)
    TenantMigrationRunner(owner).migrateTenant(id)
    return TenantContext(id, kekUri).also { tenant ->
        TenantContext.with(tenant) { context.getBean(KeyHierarchy::class.java).createTenantKey() }
    }
}

private fun TenantContext.table(name: String) = "\"${TenantSchema(tenantId).name}\".$name"

/** Written directly: `worker` opens no sessions, and a user through the service would start the invitation mail. */
private fun WorkerEnvironment.session(tenant: TenantContext): String {
    val user = UUID.randomUUID()
    val token = UUID.randomUUID().toString()
    val index = user.toString().toByteArray()
    jdbc.update(
        "INSERT INTO ${tenant.table("users")} (id, email_index, email_ciphertext, role, status) VALUES (?, ?, ?, 'USER', 'ACTIVE')",
        user,
        index,
        index,
    )
    jdbc.update(
        """
        INSERT INTO ${tenant.table("sso_sessions")} (id, user_id, token_hash, factors, authenticated_at, last_seen_at)
        VALUES (?, ?, ?, '{PASSWORD}', now(), now())
        """.trimIndent(),
        UUID.randomUUID(),
        user,
        MessageDigest.getInstance("SHA-256").digest(token.toByteArray()),
    )
    return token
}

class TenantSuspendedHandlerTest :
    StringSpec({
        val environment = WorkerEnvironment()

        "suspending a tenant closes it by status and revokes its sessions through the outbox" {
            runApplication<WorkerApplication>(*environment.arguments).use { context ->
                val authentication = context.getBean(AuthenticationService::class.java)
                val tenant = environment.tenant(context)
                val other = environment.tenant(context)
                val token = environment.session(tenant)
                val outside = environment.session(other)
                TenantContext.with(tenant) { authentication.resolveSession(token) }.shouldNotBeNull()

                environment.operatorService().suspend(tenant.tenantId, Actor.Operator("alice"))

                context.getBean(TenancyService::class.java).tenant(tenant.tenantId)?.status shouldBe TenantStatus.SUSPENDED
                eventually(30.seconds) { TenantContext.with(tenant) { authentication.resolveSession(token) }.shouldBeNull() }
                TenantContext.with(other) { authentication.resolveSession(outside) }.shouldNotBeNull()
            }
        }

        "the revocation is in the journal of the tenant as a system action" {
            runApplication<WorkerApplication>(*environment.arguments).use { context ->
                val tenant = environment.tenant(context)
                environment.session(tenant)

                environment.operatorService().suspend(tenant.tenantId, Actor.Operator("alice"))

                eventually(30.seconds) {
                    environment.jdbc
                        .queryForList(
                            "SELECT actor_type FROM ${tenant.table("audit_entries")} WHERE action = 'session.revoked_for_tenant'",
                            String::class.java,
                        ).shouldContainExactly("SYSTEM")
                }
            }
        }
    })
