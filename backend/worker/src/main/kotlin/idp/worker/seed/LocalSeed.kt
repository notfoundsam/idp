package idp.worker.seed

import idp.audit.application.OperatorAuditService
import idp.sharedkernel.audit.Actor
import idp.sharedkernel.crypto.FieldCipher
import idp.sharedkernel.crypto.KeyManagementService
import idp.sharedkernel.domain.DomainRuleViolation
import idp.sharedkernel.outbox.OutboxPublisher
import idp.sharedkernel.tenant.PlatformTransaction
import idp.sharedkernel.tenant.TenantStatus
import idp.tenancy.application.NewApplication
import idp.tenancy.application.TenancyService
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.UUID

private val TENANTS = mapOf("acme" to "Acme", "globex" to "Globex")
private val OPERATOR = Actor.Operator("local-seed")
private val TIMEOUT: Duration = Duration.ofMinutes(2)
private val POLL_INTERVAL: Duration = Duration.ofMillis(100)

/**
 * Applies and approves as `ops` does, so a local tenant is provisioned from the same event as a real one.
 * The wait holds readiness back, which keeps `make up` from returning before the tenants exist.
 */
@Component
@Profile("local")
class LocalSeed(
    @Value("\${idp.provisioner.url}") url: String,
    @Value("\${idp.provisioner.username}") username: String,
    @Value("\${idp.provisioner.password}") password: String,
    @Value("\${idp.kms.platform-kek-uri}") platformKekUri: String,
    @Value("\${idp.tenants.base-domain}") baseDomain: String,
    kms: KeyManagementService,
    private val tenancy: TenancyService,
) : ApplicationRunner {
    private val platform = PlatformTransaction(DriverManagerDataSource(url, username, password))
    private val operator =
        TenancyService(
            platform,
            OperatorAuditService(platform),
            OutboxPublisher(),
            FieldCipher.platform(kms, platformKekUri),
            baseDomain,
        )

    override fun run(args: ApplicationArguments) {
        awaitActive(TENANTS.mapNotNull { (subdomain, name) -> approve(subdomain, name) })
    }

    /** Null when the subdomain is taken, which means an earlier start got here first. */
    private fun approve(
        subdomain: String,
        name: String,
    ): UUID? =
        try {
            platform.execute {
                val application = operator.submit(NewApplication(name, subdomain, "admin@$subdomain.test"), OPERATOR)
                operator.approve(application, OPERATOR)
                checkNotNull(operator.application(application)).tenantId
            }
        } catch (_: DomainRuleViolation) {
            null
        }

    private fun awaitActive(tenants: List<UUID>) {
        val deadline = Instant.now().plus(TIMEOUT)
        while (tenants.any { tenancy.tenant(it)?.status != TenantStatus.ACTIVE }) {
            check(Instant.now().isBefore(deadline)) { "Local tenants did not become active within $TIMEOUT" }
            Thread.sleep(POLL_INTERVAL)
        }
    }
}
