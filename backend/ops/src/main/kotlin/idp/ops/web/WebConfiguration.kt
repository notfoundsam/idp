package idp.ops.web

import idp.sharedkernel.web.ClientIpFilter
import idp.sharedkernel.web.ClientIpResolver
import idp.sharedkernel.web.SecurityHeadersFilter
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.core.env.Environment
import org.springframework.core.env.Profiles

const val LOCAL = "local"
const val LOCAL_OPERATOR = "local-operator"
private const val PROD = "prod"

@Configuration(proxyBeanMethods = false)
class WebConfiguration {
    @Bean
    fun securityHeadersFilter() = SecurityHeadersFilter()

    @Bean
    fun clientIpFilter(
        @Value("\${idp.client-ip.trusted-header}") trustedHeader: String,
    ) = ClientIpFilter(ClientIpResolver(trustedHeader.ifBlank { null }))
}

/** The CLI names its caller `local-operator` in this profile instead of asking IAM. */
@Configuration(proxyBeanMethods = false)
@Profile(LOCAL)
class LocalProfileGuard(
    environment: Environment,
) {
    init {
        check(!environment.acceptsProfiles(Profiles.of(PROD))) { "The $LOCAL profile is refused beside the $PROD one" }
    }
}
