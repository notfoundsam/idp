package idp.federation

import idp.federation.application.FederationService
import idp.identity.application.IdentityService
import idp.sharedkernel.audit.Actor
import idp.sharedkernel.audit.AuditLog
import idp.sharedkernel.audit.AuditRecord
import idp.sharedkernel.crypto.KeyHierarchy
import idp.sharedkernel.crypto.adapter.OpenBaoKeyManagementService
import idp.sharedkernel.migration.TenantSchema
import idp.sharedkernel.outbox.OutboxPublisher
import idp.sharedkernel.security.UserRole
import idp.sharedkernel.tenant.PlatformTransaction
import idp.sharedkernel.tenant.TenantContext
import idp.sharedkernel.tenant.TenantTransaction
import idp.sharedkernel.testing.MovableClock
import idp.sharedkernel.testing.TestDatabase
import idp.sharedkernel.testing.TestOpenBao
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

val ADMIN = Actor.User(UUID.randomUUID())
const val IP = "198.51.100.7"

class RecordingAudit : AuditLog {
    val changes = CopyOnWriteArrayList<AuditRecord>()

    override fun recordChange(record: AuditRecord) {
        changes += record
    }

    override fun recordDenial(record: AuditRecord) = throw UnsupportedOperationException()

    fun of(action: String): List<AuditRecord> = changes.filter { it.action == action }
}

class Federation(
    maxOpenRequests: Int = 100_000,
) {
    val database = TestDatabase.migrated()
    val sql = JdbcTemplate(database.owner())
    private val kms = OpenBaoKeyManagementService(TestOpenBao.address, TestOpenBao.TOKEN)
    private val runtime = database.pooled("app_runtime")
    val transaction = TenantTransaction(runtime)
    private val keys = KeyHierarchy(kms, transaction, PlatformTransaction(runtime), Duration.ofMinutes(1))
    val audit = RecordingAudit()
    val clock = MovableClock()
    val identity = IdentityService(transaction, keys, audit, OutboxPublisher(), clock)
    val service = FederationService(transaction, keys, audit, identity, clock, maxOpenRequests = maxOpenRequests)

    /** [host] stays apart from the tenant id: pooled schemas repeat their ids across clones. */
    fun tenant(host: String? = "acme.idp.test"): TenantContext {
        val id = database.tenantSchema()
        sql.update("INSERT INTO platform.tenants (id, name, status) VALUES (?, 'tenant', 'ACTIVE')", id)
        val context = TenantContext(id, kms.createKey("tenant-${UUID.randomUUID()}"))
        TenantContext.with(context) {
            keys.createTenantKey()
            host?.let(service::createIdentity)
        }
        return context
    }

    fun <T> TenantContext.acting(block: FederationService.() -> T): T = TenantContext.with(this) { service.block() }

    fun TenantContext.user(
        email: String = "ada@acme.test",
        role: UserRole = UserRole.USER,
    ): UUID = TenantContext.with(this) { identity.invite(email, role, ADMIN) }

    fun TenantContext.register(
        metadata: String = spMetadata(),
        name: String = "Wiki",
    ): UUID = acting { registerServiceProvider(name, metadata.toByteArray(), ADMIN, IP) }

    fun TenantContext.table(name: String) = "\"${TenantSchema(tenantId).name}\".$name"

    fun TenantContext.count(name: String): Int? = sql.queryForObject("SELECT count(*) FROM ${table(name)}", Int::class.java)
}
