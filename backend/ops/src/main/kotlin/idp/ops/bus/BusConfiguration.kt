package idp.ops.bus

import idp.audit.application.OperatorAuditService
import idp.sharedkernel.audit.AuditLog
import idp.sharedkernel.bus.BusPipeline
import idp.sharedkernel.bus.CommandBus
import idp.sharedkernel.bus.MessageHandler
import idp.sharedkernel.bus.QueryBus
import idp.sharedkernel.bus.decorators.LoggerDecorator
import idp.sharedkernel.bus.decorators.SecurityDecorator
import idp.sharedkernel.bus.decorators.TransactionDecorator
import idp.sharedkernel.security.SecurityConfig
import idp.sharedkernel.tenant.PlatformTransaction
import idp.sharedkernel.tenant.TenantTransaction
import idp.sharedkernel.web.BusExceptionAdvice
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import javax.sql.DataSource

@Configuration(proxyBeanMethods = false)
class BusConfiguration {
    @Bean
    fun platformTransaction(dataSource: DataSource) = PlatformTransaction(dataSource)

    @Bean
    fun auditLog(platform: PlatformTransaction): AuditLog = OperatorAuditService(platform)

    /** The tenant slot is here for a message that forgets its scope: `ops_runtime` cannot take a tenant role. */
    @Bean
    fun busPipeline(
        handlers: ObjectProvider<MessageHandler<*, *>>,
        configs: ObjectProvider<SecurityConfig>,
        audit: AuditLog,
        platform: PlatformTransaction,
        dataSource: DataSource,
    ) = BusPipeline(
        handlers = handlers.orderedStream().toList(),
        security = SecurityDecorator(configs.orderedStream().toList(), audit),
        logger = LoggerDecorator(),
        transaction = TransactionDecorator(TenantTransaction(dataSource), platform),
    )

    @Bean
    fun commandBus(pipeline: BusPipeline) = CommandBus(pipeline)

    @Bean
    fun queryBus(pipeline: BusPipeline) = QueryBus(pipeline)

    @Bean
    fun busExceptionAdvice() = BusExceptionAdvice()
}
