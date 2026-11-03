package idp.ops.security

import idp.sharedkernel.audit.Actor
import idp.sharedkernel.audit.AuditLog
import idp.sharedkernel.audit.AuditRecord
import idp.sharedkernel.tenant.PlatformTransaction
import idp.sharedkernel.web.ClientIp
import idp.tenancy.application.ServiceTenant
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.opensaml.core.xml.util.XMLObjectSupport
import org.opensaml.saml.common.xml.SAMLConstants
import org.opensaml.saml.saml2.core.AuthnContextClassRef
import org.opensaml.saml.saml2.core.AuthnContextComparisonTypeEnumeration
import org.opensaml.saml.saml2.core.RequestedAuthnContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.authentication.ProviderManager
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.saml2.provider.service.authentication.OpenSaml5AuthenticationProvider
import org.springframework.security.saml2.provider.service.authentication.OpenSaml5AuthenticationProvider.AssertionValidator
import org.springframework.security.saml2.provider.service.authentication.OpenSaml5AuthenticationProvider.ResponseValidator
import org.springframework.security.saml2.provider.service.registration.InMemoryRelyingPartyRegistrationRepository
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistrationRepository
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistrations
import org.springframework.security.saml2.provider.service.registration.Saml2MessageBinding
import org.springframework.security.saml2.provider.service.web.authentication.OpenSaml5AuthenticationRequestResolver
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.access.intercept.AuthorizationFilter
import java.nio.file.Files
import java.nio.file.Path

private const val LOGIN_SUCCEEDED = "operator.logged_in"
private const val LOGIN_REFUSED = "operator.login_refused"
private val SAFE_METHODS = setOf("GET", "HEAD", "OPTIONS", "TRACE")

private val securityEvents = LoggerFactory.getLogger("idp.security")

/**
 * `ops` is a SAML SP of the service tenant. It trusts the keys of a metadata file it is given, never ones it fetches or finds in a
 * message, so the file is replaced after a key rotation at the IdP. Authorization stays with the bus: Spring Security only logs in.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication
class OpsSamlConfiguration(
    @Value("\${idp.tenants.base-domain}") private val baseDomain: String,
) {
    @Bean
    fun relyingPartyRegistrations(
        @Value("\${idp.saml.idp-metadata}") metadata: String,
    ): RelyingPartyRegistrationRepository {
        check(metadata.isNotBlank()) { "IDP_METADATA_FILE must name the SAML metadata of the service tenant" }
        val registration =
            Files
                .newInputStream(Path.of(metadata))
                .use { RelyingPartyRegistrations.fromMetadata(it) }
                .registrationId(ServiceTenant.REGISTRATION_ID)
                .entityId(ServiceTenant.spEntityId(baseDomain))
                .assertionConsumerServiceLocation(ServiceTenant.acsUrl(baseDomain))
                .assertionConsumerServiceBinding(Saml2MessageBinding.POST)
                .assertingPartyMetadata { it.wantAuthnRequestsSigned(false) }
                .build()
        val issuer = "https://${ServiceTenant.idpHost(baseDomain)}/saml/metadata"
        check(registration.assertingPartyMetadata.entityId == issuer) { "The metadata in $metadata is not that of $issuer" }
        return InMemoryRelyingPartyRegistrationRepository(registration)
    }

    @Bean
    fun assertionReplayCache() = AssertionReplayCache()

    @Bean
    fun operatorAssertions(replays: AssertionReplayCache) = OperatorAssertions(replays)

    @Bean
    fun opsHostFilter() = OpsHostFilter(ServiceTenant.opsHost(baseDomain))

    @Bean
    fun samlFilterChain(
        http: HttpSecurity,
        registrations: RelyingPartyRegistrationRepository,
        assertions: OperatorAssertions,
        audit: AuditLog,
        platform: PlatformTransaction,
    ): SecurityFilterChain {
        http
            .authorizeHttpRequests { it.anyRequest().permitAll() }
            .csrf { it.spa().requireCsrfProtectionMatcher(::needsCsrfToken) }
            .saml2Login { login ->
                login
                    .relyingPartyRegistrationRepository(registrations)
                    .authenticationManager(ProviderManager(provider(assertions)))
                    .authenticationRequestResolver(requestResolver(registrations))
                    .successHandler { _, response, authentication ->
                        val actor = (authentication as OperatorAuthentication).principal.actor
                        platform.execute { audit.recordChange(AuditRecord(actor, LOGIN_SUCCEEDED, ip = ClientIp.currentOrNull()?.address)) }
                        response.status = HttpServletResponse.SC_SEE_OTHER
                        response.setHeader("Location", "/")
                    }.failureHandler { _, response, failure ->
                        securityEvents.warn("event=ops.login_refused reason={}", failure.message)
                        audit.recordDenial(AuditRecord(Actor.Anonymous, LOGIN_REFUSED, ip = ClientIp.currentOrNull()?.address))
                        response.status = HttpServletResponse.SC_UNAUTHORIZED
                    }
            }.exceptionHandling { it.authenticationEntryPoint { _, response, _ -> response.status = HttpServletResponse.SC_UNAUTHORIZED } }
            .addFilterAfter(OperatorPrincipalFilter(), AuthorizationFilter::class.java)
        return http.build()
    }

    /**
     * As in `app`, only a live session has something to lose to a forged request. A login cannot be forced on a browser either: the
     * Response must answer an AuthnRequest kept in that browser's session.
     */
    private fun needsCsrfToken(request: HttpServletRequest): Boolean =
        request.method !in SAFE_METHODS && SecurityContextHolder.getContext().authentication is OperatorAuthentication

    private fun provider(assertions: OperatorAssertions) =
        OpenSaml5AuthenticationProvider().apply {
            setResponseValidator(ResponseValidator.withDefaults(assertions::validateResponse))
            val defaults = AssertionValidator.withDefaults()
            setAssertionValidator { token -> defaults.convert(token).concat(assertions.validateAssertion(token)) }
            setResponseAuthenticationConverter(assertions::authenticate)
        }

    /** Asks for a passkey in so many words, so the IdP refuses to answer for any weaker login. */
    private fun requestResolver(registrations: RelyingPartyRegistrationRepository) =
        OpenSaml5AuthenticationRequestResolver(registrations).apply {
            setAuthnRequestCustomizer { context ->
                context.authnRequest.protocolBinding = SAMLConstants.SAML2_POST_BINDING_URI
                context.authnRequest.requestedAuthnContext = passkeyContext()
            }
        }

    private fun passkeyContext(): RequestedAuthnContext {
        val classRef = XMLObjectSupport.buildXMLObject(AuthnContextClassRef.DEFAULT_ELEMENT_NAME) as AuthnContextClassRef
        classRef.uri = PASSKEY_CONTEXT
        val requested = XMLObjectSupport.buildXMLObject(RequestedAuthnContext.DEFAULT_ELEMENT_NAME) as RequestedAuthnContext
        requested.comparison = AuthnContextComparisonTypeEnumeration.EXACT
        requested.authnContextClassRefs.add(classRef)
        return requested
    }
}
