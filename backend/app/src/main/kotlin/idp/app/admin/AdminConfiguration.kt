package idp.app.admin

import idp.audience.admin.AdminSecurityConfig
import idp.audience.admin.application.command.AssignUsersHandler
import idp.audience.admin.application.command.BlockUserHandler
import idp.audience.admin.application.command.ChangeRoleHandler
import idp.audience.admin.application.command.ChangeSpSettingsHandler
import idp.audience.admin.application.command.CompleteKeyRotationHandler
import idp.audience.admin.application.command.EndAllSessionsHandler
import idp.audience.admin.application.command.EndUserSessionsHandler
import idp.audience.admin.application.command.EraseUserHandler
import idp.audience.admin.application.command.InviteUserHandler
import idp.audience.admin.application.command.RegisterServiceProviderHandler
import idp.audience.admin.application.command.RemoveServiceProviderHandler
import idp.audience.admin.application.command.ResendInvitationHandler
import idp.audience.admin.application.command.ResetMfaHandler
import idp.audience.admin.application.command.SavePolicyHandler
import idp.audience.admin.application.command.StartKeyRotationHandler
import idp.audience.admin.application.command.UnassignUsersHandler
import idp.audience.admin.application.command.UnblockUserHandler
import idp.audience.admin.application.query.FindUserByEmailHandler
import idp.audience.admin.application.query.GetAssignedUsersHandler
import idp.audience.admin.application.query.GetPolicyHandler
import idp.audience.admin.application.query.GetServiceProviderHandler
import idp.audience.admin.application.query.GetUserCardHandler
import idp.audience.admin.application.query.ListServiceProvidersHandler
import idp.audience.admin.application.query.ListSigningKeysHandler
import idp.audience.admin.web.AdminPolicyController
import idp.audience.admin.web.AdminServiceProvidersController
import idp.audience.admin.web.AdminUsersController
import idp.audit.application.AuditService
import idp.authentication.application.AuthenticationService
import idp.federation.application.FederationService
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
    fun getUserCardHandler(
        identity: IdentityService,
        federation: FederationService,
        audit: AuditService,
    ) = GetUserCardHandler(identity, federation, audit)

    @Bean
    fun listServiceProvidersHandler(federation: FederationService) = ListServiceProvidersHandler(federation)

    @Bean
    fun getServiceProviderHandler(federation: FederationService) = GetServiceProviderHandler(federation)

    @Bean
    fun registerServiceProviderHandler(federation: FederationService) = RegisterServiceProviderHandler(federation)

    @Bean
    fun changeSpSettingsHandler(federation: FederationService) = ChangeSpSettingsHandler(federation)

    @Bean
    fun removeServiceProviderHandler(federation: FederationService) = RemoveServiceProviderHandler(federation)

    @Bean
    fun getAssignedUsersHandler(
        federation: FederationService,
        identity: IdentityService,
    ) = GetAssignedUsersHandler(federation, identity)

    @Bean
    fun assignUsersHandler(federation: FederationService) = AssignUsersHandler(federation)

    @Bean
    fun unassignUsersHandler(federation: FederationService) = UnassignUsersHandler(federation)

    @Bean
    fun listSigningKeysHandler(federation: FederationService) = ListSigningKeysHandler(federation)

    @Bean
    fun startKeyRotationHandler(federation: FederationService) = StartKeyRotationHandler(federation)

    @Bean
    fun completeKeyRotationHandler(federation: FederationService) = CompleteKeyRotationHandler(federation)

    @Bean
    fun adminServiceProvidersController(
        commands: CommandBus,
        queries: QueryBus,
    ) = AdminServiceProvidersController(commands, queries)

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
