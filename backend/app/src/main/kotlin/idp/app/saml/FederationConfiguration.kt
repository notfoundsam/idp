package idp.app.saml

import idp.app.web.SamlThrottleFilter
import idp.authentication.application.AuthenticationService
import idp.federation.adapters.saml.MetadataEndpoint
import idp.federation.adapters.saml.SsoEndpoint
import idp.federation.application.FederationService
import idp.identity.application.IdentityService
import idp.sharedkernel.audit.AuditLog
import idp.sharedkernel.crypto.KeyHierarchy
import idp.sharedkernel.tenant.TenantTransaction
import idp.sharedkernel.throttle.RateLimiter
import idp.sharedkernel.throttle.ThrottleKeys
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
class FederationConfiguration {
    @Bean
    fun federationService(
        transaction: TenantTransaction,
        keys: KeyHierarchy,
        audit: AuditLog,
        identity: IdentityService,
    ) = FederationService(transaction, keys, audit, identity)

    @Bean
    fun metadataEndpoint(federation: FederationService) = MetadataEndpoint(federation)

    @Bean
    fun ssoEndpoint(
        federation: FederationService,
        authentication: AuthenticationService,
    ) = SsoEndpoint(federation, authentication)

    @Bean
    fun samlThrottleFilter(
        limiter: ObjectProvider<RateLimiter>,
        keys: ObjectProvider<ThrottleKeys>,
    ) = FilterRegistrationBean(SamlThrottleFilter(limiter, keys)).apply {
        order = SamlThrottleFilter.ORDER
        addUrlPatterns("/saml/*")
    }
}
