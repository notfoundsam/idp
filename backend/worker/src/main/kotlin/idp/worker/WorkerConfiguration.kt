package idp.worker

import idp.audit.application.AuditService
import idp.sharedkernel.audit.AuditLog
import idp.sharedkernel.crypto.FieldCipher
import idp.sharedkernel.crypto.KeyHierarchy
import idp.sharedkernel.crypto.KeyManagementService
import idp.sharedkernel.crypto.adapter.KeyManagementSettings
import idp.sharedkernel.migration.TenantMigrationRunner
import idp.sharedkernel.migration.TenantSchemaFactory
import idp.sharedkernel.outbox.OutboxPublisher
import idp.sharedkernel.tenant.PlatformTransaction
import idp.sharedkernel.tenant.TenantTransaction
import idp.tenancy.application.TenancyService
import idp.worker.provisioning.TenantProvisioningProcess
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.time.Duration
import javax.sql.DataSource

@Configuration(proxyBeanMethods = false)
class WorkerConfiguration {
    @Bean
    fun platformTransaction(dataSource: DataSource) = PlatformTransaction(dataSource)

    @Bean
    fun tenantTransaction(dataSource: DataSource) = TenantTransaction(dataSource)

    @Bean
    fun keyManagement(
        @Value("\${idp.kms.new-keys}") newKeys: String,
        @Value("\${idp.kms.openbao.address}") openBaoAddress: String,
        @Value("\${idp.kms.openbao.token}") openBaoToken: String,
        @Value("\${idp.kms.aws.region}") awsRegion: String,
        @Value("\${idp.kms.aws.key-policy}") awsKeyPolicy: String,
    ): KeyManagementService = KeyManagementSettings(newKeys, openBaoAddress, openBaoToken, awsRegion, awsKeyPolicy).service()

    @Bean
    fun keyHierarchy(
        kms: KeyManagementService,
        transaction: TenantTransaction,
        @Value("\${idp.kms.key-cache-ttl}") ttl: Duration,
    ) = KeyHierarchy(kms, transaction, ttl)

    @Bean
    fun auditLog(
        transaction: TenantTransaction,
        keys: KeyHierarchy,
    ): AuditLog = AuditService(transaction, keys)

    @Bean
    fun tenancyService(
        platform: PlatformTransaction,
        audit: AuditLog,
        kms: KeyManagementService,
        @Value("\${idp.kms.platform-kek-uri}") platformKekUri: String,
        @Value("\${idp.tenants.base-domain}") baseDomain: String,
    ) = TenancyService(platform, audit, OutboxPublisher(), FieldCipher.platform(kms, platformKekUri), baseDomain)

    /** No pool and no bean: provisioning is rare, and a second `DataSource` bean would switch off the runtime one. */
    @Bean
    fun tenantProvisioningProcess(
        tenancy: TenancyService,
        kms: KeyManagementService,
        keys: KeyHierarchy,
        @Value("\${idp.provisioner.url}") url: String,
        @Value("\${idp.provisioner.username}") username: String,
        @Value("\${idp.provisioner.password}") password: String,
    ): TenantProvisioningProcess {
        val provisioner = DriverManagerDataSource(url, username, password)
        return TenantProvisioningProcess(tenancy, TenantSchemaFactory(provisioner), TenantMigrationRunner(provisioner), kms, keys)
    }
}
