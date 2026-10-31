package idp.app.web

import idp.sharedkernel.web.BusExceptionAdvice
import idp.sharedkernel.web.ClientIpResolver
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
class WebConfiguration {
    @Bean
    fun clientIpFilter(
        @Value("\${idp.client-ip.trusted-header}") trustedHeader: String,
    ) = ClientIpFilter(ClientIpResolver(trustedHeader.ifBlank { null }))

    @Bean
    fun busExceptionAdvice() = BusExceptionAdvice()
}
