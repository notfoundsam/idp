package idp.ops.web

import idp.sharedkernel.web.ClientIpFilter
import idp.sharedkernel.web.ClientIpResolver
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.core.env.Environment
import org.springframework.core.env.Profiles

const val LOCAL = "local"
private const val PROD = "prod"

@Configuration(proxyBeanMethods = false)
class WebConfiguration {
    @Bean
    fun clientIpFilter(
        @Value("\${idp.client-ip.trusted-header}") trustedHeader: String,
    ) = ClientIpFilter(ClientIpResolver(trustedHeader.ifBlank { null }))
}

@Configuration(proxyBeanMethods = false)
@Profile(LOCAL)
class LocalPrincipalConfiguration(
    environment: Environment,
) {
    init {
        check(!environment.acceptsProfiles(Profiles.of(PROD))) { "The fixed principal is refused in the $PROD profile" }
    }

    @Bean
    fun localPrincipalFilter() = LocalPrincipalFilter()
}
