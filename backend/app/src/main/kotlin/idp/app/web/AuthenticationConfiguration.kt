package idp.app.web

import idp.authentication.application.AuthenticationService
import idp.identity.application.IdentityService
import idp.identity.application.PasskeyService
import idp.identity.application.TotpService
import idp.sharedkernel.audit.AuditLog
import idp.sharedkernel.crypto.KeyHierarchy
import idp.sharedkernel.outbox.OutboxPublisher
import idp.sharedkernel.tenant.TenantTransaction
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
class AuthenticationConfiguration {
    @Bean
    fun identityService(
        transaction: TenantTransaction,
        keys: KeyHierarchy,
        audit: AuditLog,
    ) = IdentityService(transaction, keys, audit, OutboxPublisher())

    @Bean
    fun totpService(
        transaction: TenantTransaction,
        keys: KeyHierarchy,
        audit: AuditLog,
    ) = TotpService(transaction, keys, audit)

    @Bean
    fun passkeyService(
        transaction: TenantTransaction,
        audit: AuditLog,
    ) = PasskeyService(transaction, audit)

    @Bean
    fun authenticationService(
        transaction: TenantTransaction,
        keys: KeyHierarchy,
        audit: AuditLog,
        identity: IdentityService,
        totp: TotpService,
        passkeys: PasskeyService,
    ) = AuthenticationService(transaction, keys, audit, identity, totp, passkeys)

    @Bean
    fun sessionFilter(authentication: AuthenticationService) =
        FilterRegistrationBean(SessionFilter(authentication)).apply {
            order = SessionFilter.ORDER
            addUrlPatterns("/api/*", "/saml/*")
        }
}
