package idp.app.admin

import idp.audience.admin.AdminSecurityConfig
import idp.audience.admin.application.command.BlockUserHandler
import idp.audience.admin.application.command.ChangeRoleHandler
import idp.audience.admin.application.command.EndAllSessionsHandler
import idp.audience.admin.application.command.EndUserSessionsHandler
import idp.audience.admin.application.command.EraseUserHandler
import idp.audience.admin.application.command.InviteUserHandler
import idp.audience.admin.application.command.ResendInvitationHandler
import idp.audience.admin.application.command.ResetMfaHandler
import idp.audience.admin.application.command.SavePolicyHandler
import idp.audience.admin.application.command.UnblockUserHandler
import idp.audience.admin.application.query.FindUserByEmailHandler
import idp.audience.admin.application.query.GetPolicyHandler
import idp.audience.admin.web.AdminPolicyController
import idp.audience.admin.web.AdminUsersController
import idp.authentication.application.AuthenticationService
import idp.identity.application.IdentityService
import idp.sharedkernel.bus.CommandBus
import idp.sharedkernel.bus.QueryBus
import idp.sharedkernel.security.SecurityConfig
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
class AdminConfiguration {
    @Bean
    fun adminSecurityConfig(): SecurityConfig = AdminSecurityConfig

    @Bean
    fun inviteUserHandler(identity: IdentityService) = InviteUserHandler(identity)

    @Bean
    fun resendInvitationHandler(identity: IdentityService) = ResendInvitationHandler(identity)

    @Bean
    fun changeRoleHandler(identity: IdentityService) = ChangeRoleHandler(identity)

    @Bean
    fun blockUserHandler(
        identity: IdentityService,
        authentication: AuthenticationService,
    ) = BlockUserHandler(identity, authentication)

    @Bean
    fun unblockUserHandler(identity: IdentityService) = UnblockUserHandler(identity)

    @Bean
    fun eraseUserHandler(identity: IdentityService) = EraseUserHandler(identity)

    @Bean
    fun resetMfaHandler(
        identity: IdentityService,
        authentication: AuthenticationService,
    ) = ResetMfaHandler(identity, authentication)

    @Bean
    fun endUserSessionsHandler(
        identity: IdentityService,
        authentication: AuthenticationService,
    ) = EndUserSessionsHandler(identity, authentication)

    @Bean
    fun endAllSessionsHandler(authentication: AuthenticationService) = EndAllSessionsHandler(authentication)

    @Bean
    fun savePolicyHandler(authentication: AuthenticationService) = SavePolicyHandler(authentication)

    @Bean
    fun getPolicyHandler(authentication: AuthenticationService) = GetPolicyHandler(authentication)

    @Bean
    fun findUserByEmailHandler(identity: IdentityService) = FindUserByEmailHandler(identity)

    @Bean
    fun adminUsersController(
        commands: CommandBus,
        queries: QueryBus,
    ) = AdminUsersController(commands, queries)

    @Bean
    fun adminPolicyController(
        commands: CommandBus,
        queries: QueryBus,
    ) = AdminPolicyController(commands, queries)
}
