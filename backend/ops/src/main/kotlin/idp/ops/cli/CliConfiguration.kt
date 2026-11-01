package idp.ops.cli

import idp.ops.web.LOCAL
import idp.ops.web.LOCAL_OPERATOR
import idp.sharedkernel.bus.CommandBus
import idp.sharedkernel.bus.QueryBus
import idp.sharedkernel.security.CallerIdentity
import idp.sharedkernel.security.adapter.aws.AwsCallerIdentity
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnNotWebApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

/** Only in a one-off run, so the service never needs `sts:GetCallerIdentity`. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnNotWebApplication
class CliConfiguration {
    @Bean
    @Profile(LOCAL)
    fun localIdentity() = CallerIdentity { LOCAL_OPERATOR }

    @Bean
    @Profile("!$LOCAL")
    fun iamIdentity(
        @Value("\${idp.kms.aws.region}") region: String,
    ): CallerIdentity = AwsCallerIdentity.create(region)

    @Bean
    fun opsCli(
        commands: CommandBus,
        queries: QueryBus,
        identity: CallerIdentity,
    ) = OpsCli(commands, queries, identity)
}
