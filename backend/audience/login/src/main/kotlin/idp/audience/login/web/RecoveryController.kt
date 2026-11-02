package idp.audience.login.web

import idp.audience.login.application.command.recovery.ConfirmEmailChange
import idp.audience.login.application.command.recovery.ConfirmPasswordReset
import idp.audience.login.application.command.recovery.RequestEmailChange
import idp.audience.login.application.command.recovery.RequestPasswordReset
import idp.sharedkernel.bus.CommandBus
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/** Not a data class: `toString` must not print the token or the password. */
class PasswordResetConfirmation(
    val token: String,
    val password: String,
)

/** Not a data class: `toString` must not print the address. */
class EmailChangeRequest(
    val email: String,
)

/** Not a data class: `toString` must not print the token. */
class TokenRequest(
    val token: String,
)

/** A token travels in the body, never in the URL: the mail keeps it in the fragment and the page moves it here. */
@RestController
@RequestMapping("/api")
class RecoveryController(
    private val commands: CommandBus,
) {
    /** Answers the same whether the login belongs to anyone or not. */
    @PostMapping("/password-reset")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun requestPasswordReset(
        @RequestBody request: LoginRequest,
    ) = commands.dispatch(RequestPasswordReset(request.login))

    @PostMapping("/password-reset/confirmation")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun confirmPasswordReset(
        @RequestBody request: PasswordResetConfirmation,
        http: HttpServletRequest,
    ) = commands.dispatch(ConfirmPasswordReset(request.token, request.password, http.serverName))

    @PostMapping("/email-change")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun requestEmailChange(
        @RequestBody request: EmailChangeRequest,
    ) = commands.dispatch(RequestEmailChange(request.email))

    @PostMapping("/email-change/confirmation")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun confirmEmailChange(
        @RequestBody request: TokenRequest,
    ) = commands.dispatch(ConfirmEmailChange(request.token))
}
