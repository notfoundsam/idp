package idp.migrator

import idp.audit.application.AuditService
import idp.identity.application.IdentityService
import idp.sharedkernel.crypto.FieldCipher
import idp.sharedkernel.crypto.KeyHierarchy
import idp.sharedkernel.crypto.adapter.KeyManagementSettings
import idp.sharedkernel.erasure.adapter.ErasureJournalSettings
import idp.sharedkernel.migration.TenantSchemaFactory
import idp.sharedkernel.outbox.OutboxPublisher
import idp.sharedkernel.tenant.PlatformTransaction
import idp.sharedkernel.tenant.TenantTransaction
import idp.tenancy.application.TenancyService
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration
import javax.sql.DataSource

/**
 * Only after a restore. A release runs without it, so the migrator needs KMS and the journal only then: the audit record of an
 * erasure is sealed with a key derived from the tenant key.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBooleanProperty("idp.erasures.reapply")
class ReapplyErasuresConfiguration {
    @Bean
    fun reapplyErasuresJob(
        dataSource: DataSource,
        @Value("\${idp.erasures.s3.bucket}") bucket: String,
        @Value("\${idp.erasures.s3.region}") region: String,
        @Value("\${idp.erasures.file}") file: String,
        @Value("\${idp.kms.new-keys}") newKeys: String,
        @Value("\${idp.kms.platform-kek-uri}") platformKekUri: String,
        @Value("\${idp.kms.openbao.address}") openBaoAddress: String,
        @Value("\${idp.kms.openbao.token}") openBaoToken: String,
        @Value("\${idp.kms.aws.region}") awsRegion: String,
        @Value("\${idp.kms.aws.key-policy}") awsKeyPolicy: String,
        @Value("\${idp.tenants.base-domain}") baseDomain: String,
    ): ReapplyErasuresJob {
        val kms = KeyManagementSettings(newKeys, openBaoAddress, openBaoToken, awsRegion, awsKeyPolicy).service()
        val transaction = TenantTransaction(dataSource)
        val keys = KeyHierarchy(kms, transaction, Duration.ofMinutes(5))
        val audit = AuditService(transaction, keys)
        val tenancy =
            TenancyService(PlatformTransaction(dataSource), audit, OutboxPublisher(), FieldCipher.platform(kms, platformKekUri), baseDomain)
        return ReapplyErasuresJob(
            ErasureJournalSettings(bucket, region, file).journal(),
            tenancy,
            IdentityService(transaction, keys, audit, OutboxPublisher()),
            TenantSchemaFactory(dataSource),
            kms,
        )
    }
}
