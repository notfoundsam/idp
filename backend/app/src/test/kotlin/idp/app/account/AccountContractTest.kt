package idp.app.account

import idp.app.login.Browser
import idp.app.login.LoginEnvironment
import idp.app.login.PASSWORD
import idp.app.login.PasskeyDevice
import idp.app.login.Reply
import idp.app.login.WRONG_PASSWORD
import idp.app.login.codeBody
import idp.app.login.text
import idp.app.login.totpCode
import idp.sharedkernel.testing.OpenApiContract
import idp.sharedkernel.testing.asMap
import idp.sharedkernel.testing.mappedOperations
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.io.File
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID

private const val FACTORS = "/api/account/factors"
private const val PASSWORD_CHANGE = "/api/account/password"
private const val TOTP = "/api/account/totp"
private const val TOTP_CONFIRMATION = "/api/account/totp/confirmation"
private const val PASSKEY_OPTIONS = "/api/account/passkeys/options"
private const val PASSKEYS = "/api/account/passkeys"
private const val PASSKEY = "/api/account/passkeys/{id}"
private const val RECOVERY_CODES = "/api/account/recovery-codes"
private const val SESSIONS = "/api/account/sessions"
private const val SESSION = "/api/account/sessions/{id}"
private const val LOGOUT = "/api/account/logout"
private const val ACCOUNT = " /api/account/"
private const val NEW_PASSWORD = "amber-falcon-gathers-slowly"
private const val GET = "GET"
private const val POST = "POST"
private const val DELETE = "DELETE"

class AccountContractTest :
    FunSpec({
        val contract = OpenApiContract(File("../../contracts/openapi/account.yaml"))
        val environment = LoginEnvironment()
        val application = environment.start()

        afterSpec { application.close() }

        fun Reply.conforms(
            method: String,
            template: String,
        ): Reply {
            contract.statuses(method, template) shouldContain status
            contract.responseSchema(method, template, status)?.let { contract.shouldMatch(json(), it) }
            return this
        }

        fun requestFields(template: String) = contract.requestSchema(POST, template)["properties"].asMap().keys

        fun signedIn(name: String): Pair<Browser, UUID> {
            val email = "$name@acme.test"
            val user = with(environment) { application.user(email) }
            return Browser(application).loggedIn(email) to user
        }

        test("the controller and the contract list the same operations") {
            mappedOperations(application).filter { ACCOUNT in it }.toSet() shouldBe contract.operations
        }

        test("the request bodies name what the controller reads") {
            requestFields(PASSWORD_CHANGE) shouldBe setOf("currentPassword", "newPassword", "endOtherSessions")
            requestFields(TOTP_CONFIRMATION) shouldBe setOf("code")
            requestFields(PASSKEYS) shouldBe setOf("attestationObject", "clientDataJson")
        }

        test("a change of the password conforms, also when refused") {
            val (browser, _) = signedIn("contract-password")
            val change = passwordChangeBody(PASSWORD, NEW_PASSWORD)
            val wrong = passwordChangeBody(WRONG_PASSWORD, NEW_PASSWORD)

            Browser(application).post(PASSWORD_CHANGE, change).conforms(POST, PASSWORD_CHANGE).status shouldBe 401
            browser.post(PASSWORD_CHANGE, "{}").conforms(POST, PASSWORD_CHANGE).status shouldBe 400
            browser.post(PASSWORD_CHANGE, change, echoCsrf = false).conforms(POST, PASSWORD_CHANGE).status shouldBe 403
            browser.post(PASSWORD_CHANGE, wrong).conforms(POST, PASSWORD_CHANGE).status shouldBe 422
            browser.post(PASSWORD_CHANGE, change).conforms(POST, PASSWORD_CHANGE).status shouldBe 204
            val ending = passwordChangeBody(NEW_PASSWORD, PASSWORD, endOtherSessions = true)
            browser.post(PASSWORD_CHANGE, ending).conforms(POST, PASSWORD_CHANGE).status shouldBe 204
        }

        test("the sessions and their end conform, also when refused") {
            val (browser, _) = signedIn("contract-sessions")
            val other = Browser(application).loggedIn("contract-sessions@acme.test")
            val anonymous = Browser(application)
            val ended =
                other
                    .get(SESSIONS)
                    .conforms(GET, SESSIONS)
                    .json()
                    .get("sessions")
                    .values()
                    .single { it.get("current").asBoolean() }
                    .text("id")

            browser.get(SESSIONS).conforms(GET, SESSIONS).status shouldBe 200
            browser.delete(SESSION.replace("{id}", "nonsense")).conforms(DELETE, SESSION).status shouldBe 400
            browser.delete(SESSION.replace("{id}", "${UUID.randomUUID()}")).conforms(DELETE, SESSION).status shouldBe 404
            browser.delete(SESSION.replace("{id}", ended)).conforms(DELETE, SESSION).status shouldBe 204
            browser.delete(SESSIONS).conforms(DELETE, SESSIONS).status shouldBe 204
            anonymous.get(SESSIONS).conforms(GET, SESSIONS).status shouldBe 401
            anonymous.delete(SESSIONS).conforms(DELETE, SESSIONS).status shouldBe 401
            anonymous.delete(SESSION.replace("{id}", ended)).conforms(DELETE, SESSION).status shouldBe 401
        }

        test("logging out conforms, also when refused and without a session") {
            val (browser, _) = signedIn("contract-logout")

            browser.post(LOGOUT, echoCsrf = false).conforms(POST, LOGOUT).status shouldBe 403
            val reply = browser.post(LOGOUT).conforms(POST, LOGOUT)
            reply.status shouldBe 204
            contract.responseHeaders(POST, LOGOUT, reply.status) shouldBe setOf("Set-Cookie", "Clear-Site-Data")
            contract.responseHeaders(POST, LOGOUT, reply.status).forEach { reply.header(it).shouldNotBeNull() }
            browser.post(LOGOUT).conforms(POST, LOGOUT).status shouldBe 204
        }

        test("the whole path of a TOTP conforms, also when refused") {
            val (browser, _) = signedIn("contract-totp")

            browser.get(FACTORS).conforms(GET, FACTORS).status shouldBe 200
            browser.delete(TOTP).conforms(DELETE, TOTP).status shouldBe 404
            val secret =
                browser
                    .post(TOTP)
                    .conforms(POST, TOTP)
                    .also { it.status shouldBe 200 }
                    .json()
                    .text("secret")
            browser.post(TOTP_CONFIRMATION, "{}").conforms(POST, TOTP_CONFIRMATION).status shouldBe 400
            browser.post(TOTP_CONFIRMATION, codeBody(totpCode(secret, shift = 5))).conforms(POST, TOTP_CONFIRMATION).status shouldBe 422
            browser.post(TOTP_CONFIRMATION, codeBody(totpCode(secret))).conforms(POST, TOTP_CONFIRMATION).status shouldBe 200
            browser.post(TOTP).conforms(POST, TOTP).status shouldBe 422
            browser.get(FACTORS).conforms(GET, FACTORS).status shouldBe 200
            browser.post(RECOVERY_CODES).conforms(POST, RECOVERY_CODES).status shouldBe 200
            browser.delete(TOTP).conforms(DELETE, TOTP).status shouldBe 204
            browser.post(RECOVERY_CODES).conforms(POST, RECOVERY_CODES).status shouldBe 422
        }

        test("the whole path of a passkey conforms, also when refused") {
            val (browser, _) = signedIn("contract-passkey")
            val options = browser.post(PASSKEY_OPTIONS).conforms(POST, PASSKEY_OPTIONS).json()
            val answer = PasskeyDevice().attestation(options)

            browser.post(PASSKEYS, "{}").conforms(POST, PASSKEYS).status shouldBe 400
            browser.post(PASSKEYS, answer).conforms(POST, PASSKEYS).status shouldBe 200
            browser.post(PASSKEYS, answer).conforms(POST, PASSKEYS).status shouldBe 422
            val listed = browser.get(FACTORS).conforms(GET, FACTORS)
            val id =
                listed
                    .json()
                    .get("passkeys")
                    .values()
                    .single()
                    .text("id")
            browser.delete(PASSKEY.replace("{id}", "nonsense")).conforms(DELETE, PASSKEY).status shouldBe 400
            browser.delete(PASSKEY.replace("{id}", "${UUID.randomUUID()}")).conforms(DELETE, PASSKEY).status shouldBe 404
            browser.delete(PASSKEY.replace("{id}", id)).conforms(DELETE, PASSKEY).status shouldBe 204
        }

        test("every refusal for an aged login and for a missing session is in the contract") {
            val (browser, user) = signedIn("contract-aged")
            environment.sql.update(
                "UPDATE ${environment.acme.table("sso_sessions")} SET authenticated_at = ? WHERE user_id = ?",
                Timestamp.from(Instant.now().minus(Duration.ofMinutes(11))),
                user,
            )
            val anonymous = Browser(application)
            val passkey = PASSKEY.replace("{id}", "${UUID.randomUUID()}")

            listOf(
                Triple(POST, TOTP, browser.post(TOTP)),
                Triple(POST, TOTP_CONFIRMATION, browser.post(TOTP_CONFIRMATION, codeBody("000000"))),
                Triple(DELETE, TOTP, browser.delete(TOTP)),
                Triple(POST, PASSKEY_OPTIONS, browser.post(PASSKEY_OPTIONS)),
                Triple(POST, PASSKEYS, browser.post(PASSKEYS, """{"attestationObject":"AAAA","clientDataJson":"AAAA"}""")),
                Triple(DELETE, PASSKEY, browser.delete(passkey)),
                Triple(POST, RECOVERY_CODES, browser.post(RECOVERY_CODES)),
                Triple(DELETE, SESSIONS, browser.delete(SESSIONS)),
                Triple(DELETE, SESSION, browser.delete(SESSION.replace("{id}", "${UUID.randomUUID()}"))),
            ).forEach { (method, template, reply) -> reply.conforms(method, template).status shouldBe 403 }

            anonymous.get(FACTORS).conforms(GET, FACTORS).status shouldBe 401
            anonymous.post(TOTP).conforms(POST, TOTP).status shouldBe 401
            anonymous.post(TOTP_CONFIRMATION, codeBody("000000")).conforms(POST, TOTP_CONFIRMATION).status shouldBe 401
            anonymous.delete(TOTP).conforms(DELETE, TOTP).status shouldBe 401
            anonymous.post(PASSKEY_OPTIONS).conforms(POST, PASSKEY_OPTIONS).status shouldBe 401
            anonymous.delete(passkey).conforms(DELETE, PASSKEY).status shouldBe 401
            anonymous.post(RECOVERY_CODES).conforms(POST, RECOVERY_CODES).status shouldBe 401
        }
    })
