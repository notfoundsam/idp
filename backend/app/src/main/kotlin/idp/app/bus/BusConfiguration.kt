package idp.app.bus

import idp.audit.application.AuditService
import idp.sharedkernel.audit.AuditLog
import idp.sharedkernel.bus.BusPipeline
import idp.sharedkernel.bus.CommandBus
import idp.sharedkernel.bus.MessageHandler
import idp.sharedkernel.bus.QueryBus
import idp.sharedkernel.bus.decorators.LoggerDecorator
import idp.sharedkernel.bus.decorators.SecurityDecorator
import idp.sharedkernel.bus.decorators.ThrottleDecorator
import idp.sharedkernel.bus.decorators.TransactionDecorator
import idp.sharedkernel.crypto.KeyHierarchy
import idp.sharedkernel.crypto.KeyManagementService
import idp.sharedkernel.crypto.adapter.KeyManagementSettings
import idp.sharedkernel.security.SecurityConfig
import idp.sharedkernel.tenant.PlatformTransaction
import idp.sharedkernel.tenant.TenantTransaction
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration

@Configuration(proxyBeanMethods = false)
class BusConfiguration {
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
    fun busPipeline(
        handlers: ObjectProvider<MessageHandler<*, *>>,
        configs: ObjectProvider<SecurityConfig>,
        throttle: ObjectProvider<ThrottleDecorator>,
        audit: AuditLog,
        tenant: TenantTransaction,
        platform: PlatformTransaction,
    ) = BusPipeline(
        handlers = handlers.orderedStream().toList(),
        security = SecurityDecorator(configs.orderedStream().toList(), audit),
        logger = LoggerDecorator(),
        transaction = TransactionDecorator(tenant, platform),
        throttle = throttle.ifAvailable,
    )

    @Bean
    fun commandBus(pipeline: BusPipeline) = CommandBus(pipeline)

    @Bean
    fun queryBus(pipeline: BusPipeline) = QueryBus(pipeline)
}
