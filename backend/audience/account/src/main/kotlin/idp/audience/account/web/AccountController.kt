package idp.audience.account.web

import idp.audience.account.application.command.BeginPasskeySetup
import idp.audience.account.application.command.BeginTotpSetup
import idp.audience.account.application.command.ChangePassword
import idp.audience.account.application.command.ConfirmTotpSetup
import idp.audience.account.application.command.EndOtherSessions
import idp.audience.account.application.command.EndSession
import idp.audience.account.application.command.FinishPasskeySetup
import idp.audience.account.application.command.IssuedCodes
import idp.audience.account.application.command.Logout
import idp.audience.account.application.command.RemovePasskey
import idp.audience.account.application.command.RemoveTotp
import idp.audience.account.application.command.RenewRecoveryCodes
import idp.audience.account.application.query.GetFactors
import idp.audience.account.application.query.GetSessions
import idp.authentication.application.CsrfCookie
import idp.authentication.application.SessionCookie
import idp.identity.application.PasskeyAttestation
import idp.sharedkernel.bus.CommandBus
import idp.sharedkernel.bus.QueryBus
import idp.sharedkernel.security.UserRole
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.Base64
import java.util.UUID

private const val USER_VERIFICATION = "required"
private const val CLEAR_SITE_DATA = "Clear-Site-Data"
private const val CLEARED_SITE_DATA = "\"cache\", \"storage\""

/** Not a data class: `toString` must not print the passwords. */
class PasswordChangeRequest(
    val currentPassword: String,
    val newPassword: String,
    val endOtherSessions: Boolean = false,
)

/** Not a data class: `toString` must not print the IP or the User-Agent. */
class SessionResponse(
    val id: UUID,
    val current: Boolean,
    val authenticatedAt: Instant,
    val lastSeenAt: Instant,
    val ip: String?,
    val userAgent: String?,
)

/** Not a data class: `toString` must not print the email. */
class SessionsResponse(
    val email: String,
    val role: UserRole,
    val sessions: List<SessionResponse>,
)

/** Not a data class: `toString` must not print the code. */
class TotpConfirmationRequest(
    val code: String,
)

class PasskeyRegistrationRequest(
    val attestationObject: String,
    val clientDataJson: String,
)

data class PasskeyResponse(
    val id: UUID,
    val createdAt: Instant,
    val lastUsedAt: Instant?,
    val backedUp: Boolean,
)

data class FactorsResponse(
    val totp: Boolean,
    val passkeys: List<PasskeyResponse>,
    val recoveryCodesLeft: Int,
)

class TotpSetupResponse(
    val secret: String,
)

/** Not a data class: `toString` must not print the codes. */
class RecoveryCodesResponse(
    val recoveryCodes: List<String>,
)

data class PasskeySetupOptionsResponse(
    val challenge: String,
    val rpId: String,
    val userHandle: String,
    val algorithms: List<Long>,
    val excludeCredentials: List<String>,
    val residentKey: String,
    val userVerification: String,
    val attestation: String,
)

private fun encoded(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

private fun decoded(value: String): ByteArray =
    try {
        Base64.getUrlDecoder().decode(value)
    } catch (_: IllegalArgumentException) {
        throw ResponseStatusException(HttpStatus.BAD_REQUEST)
    }

private fun IssuedCodes.response() = RecoveryCodesResponse(recoveryCodes)

/** Every path is about the user of the session, so none of them names a user. */
@RestController
@RequestMapping("/api/account")
class AccountController(
    private val commands: CommandBus,
    private val queries: QueryBus,
) {
    @GetMapping("/factors")
    fun factors(): FactorsResponse {
        val factors = queries.ask(GetFactors())
        return FactorsResponse(
            factors.totp,
            factors.passkeys.map { PasskeyResponse(it.id, it.createdAt, it.lastUsedAt, it.backedUp) },
            factors.recoveryCodesLeft,
        )
    }

    @PostMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun changePassword(
        @RequestBody request: PasswordChangeRequest,
        http: HttpServletRequest,
    ) = commands.dispatch(ChangePassword(request.currentPassword, request.newPassword, http.serverName, request.endOtherSessions))

    @PostMapping("/totp")
    fun beginTotp() = TotpSetupResponse(commands.dispatch(BeginTotpSetup()).secret)

    @PostMapping("/totp/confirmation")
    fun confirmTotp(
        @RequestBody request: TotpConfirmationRequest,
    ): RecoveryCodesResponse = commands.dispatch(ConfirmTotpSetup(request.code)).response()

    @DeleteMapping("/totp")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun removeTotp() = commands.dispatch(RemoveTotp())

    @PostMapping("/passkeys/options")
    fun passkeyOptions(http: HttpServletRequest): PasskeySetupOptionsResponse {
        val options = commands.dispatch(BeginPasskeySetup())
        return PasskeySetupOptionsResponse(
            encoded(options.challenge),
            http.serverName,
            encoded(options.userHandle),
            options.algorithms,
            options.excludeCredentials.map(::encoded),
            residentKey = "required",
            userVerification = USER_VERIFICATION,
            attestation = "none",
        )
    }

    @PostMapping("/passkeys")
    fun addPasskey(
        @RequestBody request: PasskeyRegistrationRequest,
        http: HttpServletRequest,
    ): RecoveryCodesResponse {
        val attestation = PasskeyAttestation(decoded(request.attestationObject), decoded(request.clientDataJson))
        return commands.dispatch(FinishPasskeySetup(attestation, http.serverName)).response()
    }

    @DeleteMapping("/passkeys/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun removePasskey(
        @PathVariable id: UUID,
    ) = commands.dispatch(RemovePasskey(id))

    @PostMapping("/recovery-codes")
    fun renewRecoveryCodes(): RecoveryCodesResponse = commands.dispatch(RenewRecoveryCodes()).response()

    @GetMapping("/sessions")
    fun sessions(): SessionsResponse {
        val listed = queries.ask(GetSessions())
        return SessionsResponse(
            listed.email,
            listed.role,
            listed.sessions.map { SessionResponse(it.id, it.id == listed.current, it.authenticatedAt, it.lastSeenAt, it.ip, it.userAgent) },
        )
    }

    @DeleteMapping("/sessions")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun endOtherSessions() = commands.dispatch(EndOtherSessions())

    @DeleteMapping("/sessions/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun endSession(
        @PathVariable id: UUID,
    ) = commands.dispatch(EndSession(id))

    /**
     * Cookies go by `Set-Cookie` alone: `Clear-Site-Data` would clear them for the whole registrable domain, and so log the browser
     * out of every other tenant too.
     */
    @PostMapping("/logout")
    fun logout(): ResponseEntity<Void> {
        commands.dispatch(Logout())
        return ResponseEntity
            .noContent()
            .header(HttpHeaders.SET_COOKIE, SessionCookie.clear(), CsrfCookie.clear())
            .header(CLEAR_SITE_DATA, CLEARED_SITE_DATA)
            .build()
    }
}
