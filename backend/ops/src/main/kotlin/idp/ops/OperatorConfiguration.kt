package idp.ops

import idp.audience.operator.OperatorSecurityConfig
import idp.audience.operator.application.command.ApproveApplicationHandler
import idp.audience.operator.application.command.BootstrapServiceTenantHandler
import idp.audience.operator.application.command.ResumeTenantHandler
import idp.audience.operator.application.command.SubmitApplicationHandler
import idp.audience.operator.application.command.SuspendTenantHandler
import idp.audience.operator.application.query.GetApplicationHandler
import idp.audience.operator.application.query.GetTenantHandler
import idp.audience.operator.web.TenantOpsController
import idp.sharedkernel.audit.AuditLog
import idp.sharedkernel.bus.CommandBus
import idp.sharedkernel.bus.QueryBus
import idp.sharedkernel.crypto.FieldCipher
import idp.sharedkernel.crypto.KeyManagementService
import idp.sharedkernel.crypto.adapter.KeyManagementSettings
import idp.sharedkernel.outbox.OutboxPublisher
import idp.sharedkernel.security.SecurityConfig
import idp.sharedkernel.tenant.PlatformTransaction
import idp.tenancy.application.TenancyService
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
class OperatorConfiguration {
    /** The credentials allow `Encrypt` alone, so `FieldCipher` here can store a contact and never read one. */
    @Bean
    fun keyManagement(
        @Value("\${idp.kms.new-keys}") newKeys: String,
        @Value("\${idp.kms.openbao.address}") openBaoAddress: String,
        @Value("\${idp.kms.openbao.token}") openBaoToken: String,
        @Value("\${idp.kms.aws.region}") awsRegion: String,
        @Value("\${idp.kms.aws.key-policy}") awsKeyPolicy: String,
    ): KeyManagementService = KeyManagementSettings(newKeys, openBaoAddress, openBaoToken, awsRegion, awsKeyPolicy).service()

    @Bean
    fun tenancyService(
        platform: PlatformTransaction,
        audit: AuditLog,
        kms: KeyManagementService,
        @Value("\${idp.kms.platform-kek-uri}") platformKekUri: String,
        @Value("\${idp.tenants.base-domain}") baseDomain: String,
    ) = TenancyService(platform, audit, OutboxPublisher(), FieldCipher.platform(kms, platformKekUri), baseDomain)

    @Bean
    fun operatorSecurityConfig(): SecurityConfig = OperatorSecurityConfig

    @Bean
    fun submitApplicationHandler(tenancy: TenancyService) = SubmitApplicationHandler(tenancy)

    @Bean
    fun approveApplicationHandler(tenancy: TenancyService) = ApproveApplicationHandler(tenancy)

    @Bean
    fun suspendTenantHandler(tenancy: TenancyService) = SuspendTenantHandler(tenancy)

    @Bean
    fun resumeTenantHandler(tenancy: TenancyService) = ResumeTenantHandler(tenancy)

    @Bean
    fun bootstrapServiceTenantHandler(tenancy: TenancyService) = BootstrapServiceTenantHandler(tenancy)

    @Bean
    fun getTenantHandler(tenancy: TenancyService) = GetTenantHandler(tenancy)

    @Bean
    fun getApplicationHandler(tenancy: TenancyService) = GetApplicationHandler(tenancy)

    @Bean
    fun tenantOpsController(
        commands: CommandBus,
        queries: QueryBus,
    ) = TenantOpsController(commands, queries)
}
