package idp.app.saml

import idp.federation.adapters.saml.MetadataEndpoint
import idp.federation.application.FederationService
import idp.identity.application.IdentityService
import idp.sharedkernel.audit.AuditLog
import idp.sharedkernel.crypto.KeyHierarchy
import idp.sharedkernel.tenant.TenantTransaction
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
}
