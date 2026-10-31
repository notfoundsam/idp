package idp.app.web

import idp.sharedkernel.tenant.PlatformTransaction
import idp.sharedkernel.tenant.TenantResolver
import idp.sharedkernel.tenant.TenantTransaction
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration
import javax.sql.DataSource

@Configuration(proxyBeanMethods = false)
class TenantConfiguration {
    @Bean
    fun platformTransaction(dataSource: DataSource) = PlatformTransaction(dataSource)

    @Bean
    fun tenantTransaction(dataSource: DataSource) = TenantTransaction(dataSource)

    @Bean
    fun tenantResolver(
        platform: PlatformTransaction,
        @Value("\${idp.tenants.cache-ttl}") ttl: Duration,
    ) = TenantResolver(platform, ttl)

    @Bean
    fun tenantFilter(tenants: TenantResolver) = TenantFilter(tenants)
}
