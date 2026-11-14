package idp.worker.observability

import idp.audit.application.AuditService
import idp.federation.application.FederationService
import idp.identity.application.IdentityService
import idp.sharedkernel.crypto.FieldCipher
import idp.sharedkernel.crypto.KeyHierarchy
import idp.sharedkernel.crypto.adapter.OpenBaoKeyManagementService
import idp.sharedkernel.migration.TenantSchema
import idp.sharedkernel.observability.Failures
import idp.sharedkernel.outbox.OutboxPublisher
import idp.sharedkernel.scheduling.JobLocks
import idp.sharedkernel.scheduling.JobScheduler
import idp.sharedkernel.tenant.PlatformTransaction
import idp.sharedkernel.tenant.TenantContext
import idp.sharedkernel.tenant.TenantTransaction
import idp.sharedkernel.testing.MovableClock
import idp.sharedkernel.testing.TestDatabase
import idp.sharedkernel.testing.TestOpenBao
import idp.tenancy.application.TenancyService
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.springframework.jdbc.core.JdbcTemplate
import java.security.cert.X509Certificate
import java.time.Duration
import java.time.Instant
import java.util.UUID

private class Certificates {
    val database = TestDatabase.migrated()
    val sql = JdbcTemplate(database.owner())
    private val worker = database.pooled("worker_runtime")
    val clock = MovableClock()
    private val kms = OpenBaoKeyManagementService(TestOpenBao.address, TestOpenBao.TOKEN)
    private val platform = PlatformTransaction(worker)
    private val transaction = TenantTransaction(worker)
    private val keys = KeyHierarchy(kms, transaction, platform, Duration.ofMinutes(1))
    private val audit = AuditService(transaction, keys)
    private val contacts = FieldCipher.platform(kms, kms.createKey("contacts-${UUID.randomUUID()}"))
    private val tenancy = TenancyService(platform, audit, OutboxPublisher(), contacts, "idp.test", clock)
    val federation = FederationService(transaction, keys, audit, IdentityService(transaction, keys, audit, OutboxPublisher()), clock)
    val registry = SimpleMeterRegistry()
    val job = CertificateExpiryJob(tenancy, federation, Failures(registry)).also { it.bindTo(registry) }

    fun tenant(identity: Boolean = true): TenantContext {
        val id = database.tenantSchema()
        val kek = kms.createKey("tenant-${UUID.randomUUID()}")
        sql.update("INSERT INTO platform.tenants (id, name, status, kek_uri) VALUES (?, 'Acme', 'ACTIVE', ?)", id, kek)
        val context = TenantContext(id, kek)
        TenantContext.with(context) {
            keys.createTenantKey()
            if (identity) federation.createIdentity("t-$id.idp.test")
        }
        return context
    }

    fun certificate(tenant: TenantContext): X509Certificate = TenantContext.with(tenant) { federation.signingKeys().single().certificate }

    fun run() {
        sql.update("DELETE FROM platform.scheduled_locks")
        JobScheduler(listOf(job), JobLocks(PlatformTransaction(worker), clock)).tick()
    }

    fun gauge(): Double = registry.get("idp.saml.certificate.expiry").gauge().value()

    fun failures(): Double =
        registry
            .find("idp.failures")
            .tags("source", "job")
            .counter()
            ?.count() ?: 0.0
}

class CertificateExpiryJobTest :
    FunSpec({
        test("the gauge holds the earliest end of a signing certificate across the tenants") {
            Certificates().run {
                clock.offset = Duration.ofDays(-400)
                val older = tenant()
                clock.offset = Duration.ZERO
                tenant()
                tenant(identity = false)

                run()

                val end: Instant = certificate(older).notAfter.toInstant()
                job.earliest() shouldBe end
                gauge() shouldBe end.epochSecond.toDouble()
            }
        }

        test("a tenant that fails is counted and skipped, and the others still count") {
            Certificates().run {
                val broken = tenant()
                val healthy = tenant()
                sql.update("""DROP TABLE "${TenantSchema(broken.tenantId).name}".saml_signing_keys CASCADE""")

                run()

                failures() shouldBe 1.0
                job.earliest().shouldNotBeNull() shouldBe certificate(healthy).notAfter.toInstant()
            }
        }

        test("a tenant that starts failing keeps the end read last time, so its certificate still counts") {
            Certificates().run {
                clock.offset = Duration.ofDays(-400)
                val failing = tenant()
                clock.offset = Duration.ZERO
                tenant()
                val end = certificate(failing).notAfter.toInstant()
                run()
                job.earliest() shouldBe end

                sql.update("""DROP TABLE "${TenantSchema(failing.tenantId).name}".saml_signing_keys CASCADE""")
                run()

                failures() shouldBe 1.0
                job.earliest() shouldBe end
                gauge() shouldBe end.epochSecond.toDouble()
            }
        }

        test("before a run, and without any certificate, the gauge has no value") {
            Certificates().run {
                gauge().isNaN() shouldBe true

                tenant(identity = false)
                run()

                job.earliest().shouldBeNull()
                gauge().isNaN() shouldBe true
            }
        }
    })
