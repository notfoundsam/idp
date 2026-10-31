package idp.audit

import idp.audit.application.AuditService
import idp.sharedkernel.audit.Actor
import idp.sharedkernel.bus.BusPipeline
import idp.sharedkernel.bus.Command
import idp.sharedkernel.bus.CommandBus
import idp.sharedkernel.bus.Message
import idp.sharedkernel.bus.MessageHandler
import idp.sharedkernel.bus.decorators.LoggerDecorator
import idp.sharedkernel.bus.decorators.SecurityDecorator
import idp.sharedkernel.bus.decorators.TransactionDecorator
import idp.sharedkernel.crypto.KeyHierarchy
import idp.sharedkernel.crypto.adapter.OpenBaoKeyManagementService
import idp.sharedkernel.migration.TenantMigrationRunner
import idp.sharedkernel.migration.TenantSchemaFactory
import idp.sharedkernel.security.AccessDeniedException
import idp.sharedkernel.security.Permission
import idp.sharedkernel.security.Role
import idp.sharedkernel.security.SecurityConfig
import idp.sharedkernel.tenant.PlatformTransaction
import idp.sharedkernel.tenant.TenantContext
import idp.sharedkernel.tenant.TenantTransaction
import idp.sharedkernel.testing.TestDatabase
import idp.sharedkernel.testing.TestOpenBao
import idp.sharedkernel.web.ClientIp
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.reflect.KClass

private const val ATTACKER_IP = "203.0.113.7"

private class DeleteUser : Command<Unit>

class BusDenialTest :
    FunSpec({
        val database = TestDatabase.create()
        val owner = database.owner()
        TenantMigrationRunner(owner).migrateAll()
        val kms = OpenBaoKeyManagementService(TestOpenBao.address, TestOpenBao.TOKEN)
        val runtime = database.pooled("app_runtime")
        val transaction = TenantTransaction(runtime)
        val keys = KeyHierarchy(kms, transaction, Duration.ofMinutes(1))
        val audit = AuditService(transaction, keys)
        val id = UUID.randomUUID()
        JdbcTemplate(owner).update("INSERT INTO platform.tenants (id, name, status) VALUES (?, 'tenant', 'ACTIVE')", id)
        TenantSchemaFactory(owner).create(id)
        TenantMigrationRunner(owner).migrateTenant(id)
        val tenant = TenantContext(id, kms.createKey("tenant-$id"))
        TenantContext.with(tenant) { keys.createTenantKey() }
        val handled = AtomicInteger()
        val handler =
            object : MessageHandler<DeleteUser, Unit> {
                override val messageType = DeleteUser::class

                override fun handle(message: DeleteUser) {
                    handled.incrementAndGet()
                }
            }
        val security =
            object : SecurityConfig {
                override val permissions = mapOf<KClass<out Message<*>>, Permission?>(DeleteUser::class to Permission("users.delete"))
                override val roles = emptyMap<Role, Set<Permission>>()
            }
        val bus =
            CommandBus(
                BusPipeline(
                    handlers = listOf(handler),
                    security = SecurityDecorator(listOf(security), audit),
                    logger = LoggerDecorator(),
                    transaction = TransactionDecorator(transaction, PlatformTransaction(runtime)),
                ),
            )

        test("a denied message lands in the journal of its tenant and never reaches the handler") {
            TenantContext.with(tenant) {
                shouldThrow<AccessDeniedException> {
                    ClientIp.with(ClientIp(ATTACKER_IP)) { bus.dispatch(DeleteUser()) }
                }

                val entry = audit.read(limit = 10).single()
                entry.action shouldBe "access.denied"
                entry.actor shouldBe Actor.Anonymous
                entry.ip shouldBe ATTACKER_IP
                entry.details shouldBe mapOf("message" to DeleteUser::class.java.name)
            }

            handled.get() shouldBe 0
        }
    })
