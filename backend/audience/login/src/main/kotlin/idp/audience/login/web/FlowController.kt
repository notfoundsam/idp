package idp.audience.login.web

import idp.audience.login.application.command.BeginPasskeyEnrollment
import idp.audience.login.application.command.BeginPasskeyLogin
import idp.audience.login.application.command.BeginTotpEnrollment
import idp.audience.login.application.command.CompleteFlow
import idp.audience.login.application.command.ConfirmTotpEnrollment
import idp.audience.login.application.command.EnrolledFactor
import idp.audience.login.application.command.FinishPasskeyEnrollment
import idp.audience.login.application.command.StartFlow
import idp.audience.login.application.command.SubmitLogin
import idp.audience.login.application.command.SubmitPasskey
import idp.audience.login.application.command.SubmitPassword
import idp.audience.login.application.command.SubmitRecoveryCode
import idp.audience.login.application.command.SubmitTotp
import idp.audience.login.application.command.recovery.AcceptInvitation
import idp.audience.login.application.query.GetFlow
import idp.authentication.application.CsrfCookie
import idp.authentication.application.FlowAccess
import idp.authentication.application.FlowCookie
import idp.authentication.application.FlowView
import idp.authentication.application.LoginFactor
import idp.authentication.application.LoginStep
import idp.authentication.application.SessionCookie
import idp.identity.application.PasskeyAssertion
import idp.identity.application.PasskeyAttestation
import idp.identity.application.PasskeyCeremony
import idp.sharedkernel.bus.CommandBus
import idp.sharedkernel.bus.QueryBus
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.CookieValue
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.util.Base64
import java.util.UUID

class StartFlowRequest(
    val reauthenticate: Boolean = false,
)

/** Not a data class: `toString` must not print the login. */
class LoginRequest(
    val login: String,
)

/** Not a data class: `toString` must not print the password. */
class PasswordRequest(
    val password: String,
)

/** Not a data class: `toString` must not print the token or the password. */
class InvitationRequest(
    val token: String,
    val password: String,
)

/** Not a data class: `toString` must not print the code. */
class CodeRequest(
    val code: String,
)

class PasskeyLoginRequest(
    val credentialId: String,
    val userHandle: String?,
    val authenticatorData: String,
    val clientDataJson: String,
    val signature: String,
)

class PasskeyEnrollmentRequest(
    val attestationObject: String,
    val clientDataJson: String,
)

enum class FlowStep {
    IDENTIFY,
    PASSWORD,
    SECOND_FACTOR,
    ENROLL_MFA,
    COMPLETE,
}

enum class FlowFactor {
    PASSWORD,
    TOTP,
    PASSKEY,
    RECOVERY_CODE,
}

data class FlowResponse(
    val id: UUID,
    val step: FlowStep,
    val factors: List<FlowFactor>,
)

class EnrolledResponse(
    val id: UUID,
    val step: FlowStep,
    val factors: List<FlowFactor>,
    val recoveryCodes: List<String>,
)

private fun LoginStep.response() =
    when (this) {
        LoginStep.IDENTIFY -> FlowStep.IDENTIFY
        LoginStep.PASSWORD -> FlowStep.PASSWORD
        LoginStep.SECOND_FACTOR -> FlowStep.SECOND_FACTOR
        LoginStep.ENROLL_MFA -> FlowStep.ENROLL_MFA
        LoginStep.COMPLETE -> FlowStep.COMPLETE
    }

private fun Set<LoginFactor>.response() =
    map {
        when (it) {
            LoginFactor.PASSWORD -> FlowFactor.PASSWORD
            LoginFactor.TOTP -> FlowFactor.TOTP
            LoginFactor.PASSKEY -> FlowFactor.PASSKEY
            LoginFactor.RECOVERY_CODE -> FlowFactor.RECOVERY_CODE
        }
    }.sorted()

class CompletedResponse(
    val returnUrl: String?,
)

class TotpEnrollmentResponse(
    val secret: String,
)

data class PasskeyLoginOptionsResponse(
    val challenge: String,
    val rpId: String,
    val userVerification: String,
)

data class PasskeyEnrollmentOptionsResponse(
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

private fun FlowView.response() = FlowResponse(id, step.response(), factors.response())

private fun EnrolledFactor.response() = EnrolledResponse(view.id, view.step.response(), view.factors.response(), recoveryCodes)

/** The flow lives in the URL and its binding in a cookie. A browser without the cookie gets the same answer as for an unknown flow. */
@RestController
@RequestMapping("/api/flows")
class FlowController(
    private val commands: CommandBus,
    private val queries: QueryBus,
) {
    @PostMapping
    fun start(
        @RequestBody(required = false) request: StartFlowRequest?,
        @CookieValue(SessionCookie.NAME, required = false) session: String?,
        @CookieValue(FlowCookie.NAME, required = false) browser: String?,
    ): ResponseEntity<FlowResponse> {
        val started = commands.dispatch(StartFlow(session, browser, request?.reauthenticate == true))
        return ResponseEntity
            .status(HttpStatus.CREATED)
            .header(HttpHeaders.SET_COOKIE, FlowCookie.issue(started.access.browserSecret))
            .body(started.view.response())
    }

    @GetMapping("/{id}")
    fun flow(
        @PathVariable id: UUID,
        @CookieValue(FlowCookie.NAME, required = false) browser: String?,
    ): FlowResponse = queries.ask(GetFlow(access(id, browser))).response()

    @PostMapping("/{id}/login")
    fun login(
        @PathVariable id: UUID,
        @CookieValue(FlowCookie.NAME, required = false) browser: String?,
        @RequestBody request: LoginRequest,
    ): FlowResponse = commands.dispatch(SubmitLogin(access(id, browser), request.login)).response()

    @PostMapping("/{id}/password")
    fun password(
        @PathVariable id: UUID,
        @CookieValue(FlowCookie.NAME, required = false) browser: String?,
        @RequestBody request: PasswordRequest,
    ): FlowResponse = commands.dispatch(SubmitPassword(access(id, browser), request.password)).response()

    @PostMapping("/{id}/invitation")
    fun invitation(
        @PathVariable id: UUID,
        @CookieValue(FlowCookie.NAME, required = false) browser: String?,
        @RequestBody request: InvitationRequest,
        http: HttpServletRequest,
    ): FlowResponse = commands.dispatch(AcceptInvitation(access(id, browser), request.token, request.password, http.serverName)).response()

    @PostMapping("/{id}/totp")
    fun totp(
        @PathVariable id: UUID,
        @CookieValue(FlowCookie.NAME, required = false) browser: String?,
        @RequestBody request: CodeRequest,
    ): FlowResponse = commands.dispatch(SubmitTotp(access(id, browser), request.code)).response()

    @PostMapping("/{id}/recovery-code")
    fun recoveryCode(
        @PathVariable id: UUID,
        @CookieValue(FlowCookie.NAME, required = false) browser: String?,
        @RequestBody request: CodeRequest,
    ): FlowResponse = commands.dispatch(SubmitRecoveryCode(access(id, browser), request.code)).response()

    /** No `allowCredentials`: passkeys are discoverable, and listing them would tell who has an account. */
    @PostMapping("/{id}/passkey/options")
    fun passkeyOptions(
        @PathVariable id: UUID,
        @CookieValue(FlowCookie.NAME, required = false) browser: String?,
        http: HttpServletRequest,
    ): PasskeyLoginOptionsResponse {
        val options = commands.dispatch(BeginPasskeyLogin(access(id, browser)))
        return PasskeyLoginOptionsResponse(encoded(options.challenge), http.serverName, PasskeyCeremony.USER_VERIFICATION)
    }

    @PostMapping("/{id}/passkey")
    fun passkey(
        @PathVariable id: UUID,
        @CookieValue(FlowCookie.NAME, required = false) browser: String?,
        @RequestBody request: PasskeyLoginRequest,
        http: HttpServletRequest,
    ): FlowResponse {
        val assertion =
            PasskeyAssertion(
                decoded(request.credentialId),
                request.userHandle?.let(::decoded),
                decoded(request.authenticatorData),
                decoded(request.clientDataJson),
                decoded(request.signature),
            )
        return commands.dispatch(SubmitPasskey(access(id, browser), assertion, http.serverName)).response()
    }

    @PostMapping("/{id}/enrollment/totp")
    fun enrollTotp(
        @PathVariable id: UUID,
        @CookieValue(FlowCookie.NAME, required = false) browser: String?,
    ) = TotpEnrollmentResponse(commands.dispatch(BeginTotpEnrollment(access(id, browser))).secret)

    @PostMapping("/{id}/enrollment/totp/confirmation")
    fun confirmTotp(
        @PathVariable id: UUID,
        @CookieValue(FlowCookie.NAME, required = false) browser: String?,
        @RequestBody request: CodeRequest,
    ): EnrolledResponse = commands.dispatch(ConfirmTotpEnrollment(access(id, browser), request.code)).response()

    @PostMapping("/{id}/enrollment/passkey/options")
    fun enrollPasskeyOptions(
        @PathVariable id: UUID,
        @CookieValue(FlowCookie.NAME, required = false) browser: String?,
        http: HttpServletRequest,
    ): PasskeyEnrollmentOptionsResponse {
        val options = commands.dispatch(BeginPasskeyEnrollment(access(id, browser)))
        return PasskeyEnrollmentOptionsResponse(
            encoded(options.challenge),
            http.serverName,
            encoded(options.userHandle),
            options.algorithms,
            options.excludeCredentials.map(::encoded),
            residentKey = PasskeyCeremony.RESIDENT_KEY,
            userVerification = PasskeyCeremony.USER_VERIFICATION,
            attestation = PasskeyCeremony.ATTESTATION,
        )
    }

    @PostMapping("/{id}/enrollment/passkey")
    fun enrollPasskey(
        @PathVariable id: UUID,
        @CookieValue(FlowCookie.NAME, required = false) browser: String?,
        @RequestBody request: PasskeyEnrollmentRequest,
        http: HttpServletRequest,
    ): EnrolledResponse {
        val attestation = PasskeyAttestation(decoded(request.attestationObject), decoded(request.clientDataJson))
        return commands.dispatch(FinishPasskeyEnrollment(access(id, browser), attestation, http.serverName)).response()
    }

    /** Without a new session the browser keeps the cookies it has: nothing new was proved. */
    @PostMapping("/{id}/complete")
    fun complete(
        @PathVariable id: UUID,
        @CookieValue(FlowCookie.NAME, required = false) browser: String?,
        @RequestHeader(HttpHeaders.USER_AGENT, required = false) userAgent: String?,
    ): ResponseEntity<CompletedResponse> {
        val completed = commands.dispatch(CompleteFlow(access(id, browser), userAgent))
        val response = ResponseEntity.ok()
        completed.session?.let { response.header(HttpHeaders.SET_COOKIE, SessionCookie.issue(it.token), CsrfCookie.issue(it.token)) }
        return response.body(CompletedResponse(completed.returnUrl))
    }

    private fun access(
        id: UUID,
        browser: String?,
    ) = FlowAccess(id, browser.orEmpty())
}
