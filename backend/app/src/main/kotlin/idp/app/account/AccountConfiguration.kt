package idp.app.account

import idp.audience.account.AccountSecurityConfig
import idp.audience.account.AccountThrottleConfig
import idp.audience.account.application.command.BeginPasskeySetupHandler
import idp.audience.account.application.command.BeginTotpSetupHandler
import idp.audience.account.application.command.ChangePasswordHandler
import idp.audience.account.application.command.ConfirmTotpSetupHandler
import idp.audience.account.application.command.EndOtherSessionsHandler
import idp.audience.account.application.command.EndSessionHandler
import idp.audience.account.application.command.FinishPasskeySetupHandler
import idp.audience.account.application.command.LogoutHandler
import idp.audience.account.application.command.RemovePasskeyHandler
import idp.audience.account.application.command.RemoveTotpHandler
import idp.audience.account.application.command.RenewRecoveryCodesHandler
import idp.audience.account.application.query.GetFactorsHandler
import idp.audience.account.application.query.GetSessionsHandler
import idp.audience.account.web.AccountController
import idp.authentication.application.AuthenticationService
import idp.identity.application.IdentityService
import idp.identity.application.PasskeyService
import idp.identity.application.PasswordService
import idp.identity.application.TotpService
import idp.sharedkernel.audit.AuditLog
import idp.sharedkernel.bus.CommandBus
import idp.sharedkernel.bus.QueryBus
import idp.sharedkernel.security.SecurityConfig
import idp.sharedkernel.tenant.TenantResolver
import idp.sharedkernel.tenant.TenantTransaction
import idp.sharedkernel.throttle.ThrottleConfig
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
class AccountConfiguration {
    @Bean
    fun accountSecurityConfig(): SecurityConfig = AccountSecurityConfig

    @Bean
    fun accountThrottleConfig(): ThrottleConfig = AccountThrottleConfig

    @Bean
    fun getFactorsHandler(
        totp: TotpService,
        passkeys: PasskeyService,
    ) = GetFactorsHandler(totp, passkeys)

    @Bean
    fun getSessionsHandler(
        identity: IdentityService,
        authentication: AuthenticationService,
    ) = GetSessionsHandler(identity, authentication)

    @Bean
    fun changePasswordHandler(
        transaction: TenantTransaction,
        passwords: PasswordService,
        authentication: AuthenticationService,
        tenants: TenantResolver,
        audit: AuditLog,
    ) = ChangePasswordHandler(transaction, passwords, authentication, tenants, audit)

    @Bean
    fun endSessionHandler(authentication: AuthenticationService) = EndSessionHandler(authentication)

    @Bean
    fun endOtherSessionsHandler(authentication: AuthenticationService) = EndOtherSessionsHandler(authentication)

    @Bean
    fun logoutHandler(authentication: AuthenticationService) = LogoutHandler(authentication)

    @Bean
    fun beginTotpSetupHandler(totp: TotpService) = BeginTotpSetupHandler(totp)

    @Bean
    fun confirmTotpSetupHandler(
        totp: TotpService,
        audit: AuditLog,
    ) = ConfirmTotpSetupHandler(totp, audit)

    @Bean
    fun removeTotpHandler(
        totp: TotpService,
        authentication: AuthenticationService,
    ) = RemoveTotpHandler(totp, authentication)

    @Bean
    fun beginPasskeySetupHandler(passkeys: PasskeyService) = BeginPasskeySetupHandler(passkeys)

    @Bean
    fun finishPasskeySetupHandler(
        passkeys: PasskeyService,
        totp: TotpService,
        audit: AuditLog,
    ) = FinishPasskeySetupHandler(passkeys, totp, audit)

    @Bean
    fun removePasskeyHandler(
        passkeys: PasskeyService,
        authentication: AuthenticationService,
    ) = RemovePasskeyHandler(passkeys, authentication)

    @Bean
    fun renewRecoveryCodesHandler(
        totp: TotpService,
        passkeys: PasskeyService,
    ) = RenewRecoveryCodesHandler(totp, passkeys)

    @Bean
    fun accountController(
        commands: CommandBus,
        queries: QueryBus,
    ) = AccountController(commands, queries)
}
