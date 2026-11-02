package idp.app.login

import idp.audience.login.LoginSecurityConfig
import idp.audience.login.LoginThrottleConfig
import idp.audience.login.application.command.BeginPasskeyEnrollmentHandler
import idp.audience.login.application.command.BeginPasskeyLoginHandler
import idp.audience.login.application.command.BeginTotpEnrollmentHandler
import idp.audience.login.application.command.CompleteFlowHandler
import idp.audience.login.application.command.ConfirmTotpEnrollmentHandler
import idp.audience.login.application.command.FinishPasskeyEnrollmentHandler
import idp.audience.login.application.command.LoginAttempts
import idp.audience.login.application.command.StartFlowHandler
import idp.audience.login.application.command.SubmitLoginHandler
import idp.audience.login.application.command.SubmitPasskeyHandler
import idp.audience.login.application.command.SubmitPasswordHandler
import idp.audience.login.application.command.SubmitRecoveryCodeHandler
import idp.audience.login.application.command.SubmitTotpHandler
import idp.audience.login.application.query.GetFlowHandler
import idp.audience.login.web.FlowController
import idp.authentication.application.AuthenticationService
import idp.identity.application.PasskeyService
import idp.identity.application.PasswordService
import idp.identity.application.TotpService
import idp.sharedkernel.audit.AuditLog
import idp.sharedkernel.bus.CommandBus
import idp.sharedkernel.bus.QueryBus
import idp.sharedkernel.security.SecurityConfig
import idp.sharedkernel.throttle.ThrottleConfig
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
class LoginConfiguration {
    @Bean
    fun loginSecurityConfig(): SecurityConfig = LoginSecurityConfig

    @Bean
    fun loginThrottleConfig(): ThrottleConfig = LoginThrottleConfig

    @Bean
    fun loginAttempts(
        authentication: AuthenticationService,
        audit: AuditLog,
    ) = LoginAttempts(authentication, audit)

    @Bean
    fun startFlowHandler(authentication: AuthenticationService) = StartFlowHandler(authentication)

    @Bean
    fun getFlowHandler(authentication: AuthenticationService) = GetFlowHandler(authentication)

    @Bean
    fun submitLoginHandler(authentication: AuthenticationService) = SubmitLoginHandler(authentication)

    @Bean
    fun submitPasswordHandler(
        authentication: AuthenticationService,
        passwords: PasswordService,
        attempts: LoginAttempts,
    ) = SubmitPasswordHandler(authentication, passwords, attempts)

    @Bean
    fun submitTotpHandler(
        authentication: AuthenticationService,
        totp: TotpService,
        attempts: LoginAttempts,
    ) = SubmitTotpHandler(authentication, totp, attempts)

    @Bean
    fun submitRecoveryCodeHandler(
        authentication: AuthenticationService,
        totp: TotpService,
        attempts: LoginAttempts,
    ) = SubmitRecoveryCodeHandler(authentication, totp, attempts)

    @Bean
    fun beginPasskeyLoginHandler(
        authentication: AuthenticationService,
        passkeys: PasskeyService,
    ) = BeginPasskeyLoginHandler(authentication, passkeys)

    @Bean
    fun submitPasskeyHandler(
        authentication: AuthenticationService,
        passkeys: PasskeyService,
        attempts: LoginAttempts,
    ) = SubmitPasskeyHandler(authentication, passkeys, attempts)

    @Bean
    fun beginTotpEnrollmentHandler(
        authentication: AuthenticationService,
        totp: TotpService,
    ) = BeginTotpEnrollmentHandler(authentication, totp)

    @Bean
    fun confirmTotpEnrollmentHandler(
        authentication: AuthenticationService,
        totp: TotpService,
        attempts: LoginAttempts,
    ) = ConfirmTotpEnrollmentHandler(authentication, totp, attempts)

    @Bean
    fun beginPasskeyEnrollmentHandler(
        authentication: AuthenticationService,
        passkeys: PasskeyService,
    ) = BeginPasskeyEnrollmentHandler(authentication, passkeys)

    @Bean
    fun finishPasskeyEnrollmentHandler(
        authentication: AuthenticationService,
        passkeys: PasskeyService,
        totp: TotpService,
        attempts: LoginAttempts,
    ) = FinishPasskeyEnrollmentHandler(authentication, passkeys, totp, attempts)

    @Bean
    fun completeFlowHandler(
        authentication: AuthenticationService,
        audit: AuditLog,
    ) = CompleteFlowHandler(authentication, audit)

    @Bean
    fun flowController(
        commands: CommandBus,
        queries: QueryBus,
    ) = FlowController(commands, queries)
}
