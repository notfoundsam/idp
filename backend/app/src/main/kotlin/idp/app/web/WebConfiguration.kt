package idp.app.web

import idp.sharedkernel.audit.AuditLog
import idp.sharedkernel.web.BusExceptionAdvice
import idp.sharedkernel.web.ClientIpFilter
import idp.sharedkernel.web.ClientIpResolver
import idp.sharedkernel.web.SecurityHeadersFilter
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.util.unit.DataSize

private const val API = "/api/*"

@Configuration(proxyBeanMethods = false)
class WebConfiguration {
    @Bean
    fun securityHeadersFilter() = SecurityHeadersFilter()

    @Bean
    fun clientIpFilter(
        @Value("\${idp.client-ip.trusted-header}") trustedHeader: String,
    ) = ClientIpFilter(ClientIpResolver(trustedHeader.ifBlank { null }))

    @Bean
    fun clientVersionFilter(
        @Value("\${idp.release.version}") appVersion: String,
        @Value("\${idp.release.min-client-version}") minClientVersion: String,
    ) = FilterRegistrationBean(ClientVersionFilter(appVersion, minClientVersion)).apply {
        order = ClientVersionFilter.ORDER
        addUrlPatterns(API)
    }

    @Bean
    fun requestSizeFilter(
        @Value("\${idp.login.max-request-size}") maxSize: DataSize,
    ) = FilterRegistrationBean(RequestSizeFilter(maxSize.toBytes())).apply {
        order = RequestSizeFilter.ORDER
        addUrlPatterns(API, "/saml/*")
    }

    @Bean
    fun csrfFilter(audit: AuditLog) =
        FilterRegistrationBean(CsrfFilter(audit)).apply {
            order = CsrfFilter.ORDER
            addUrlPatterns(API)
        }

    @Bean
    fun busExceptionAdvice() = BusExceptionAdvice()
}
