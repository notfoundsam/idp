package idp.app.account

import idp.app.admin.put
import idp.app.admin.roleBody
import idp.app.admin.signedIn
import idp.app.login.ACME
import idp.app.login.Browser
import idp.app.login.LoginEnvironment
import idp.app.login.PASSWORD
import idp.app.login.PasskeyDevice
import idp.app.login.Reply
import idp.app.login.USER_AGENT
import idp.app.login.WRONG_PASSWORD
import idp.app.login.codeBody
import idp.app.login.loginBody
import idp.app.login.strings
import idp.app.login.text
import idp.app.login.totpCode
import idp.authentication.application.CsrfCookie
import idp.authentication.application.SessionCookie
import idp.sharedkernel.audit.Actor
import idp.sharedkernel.audit.AuditTarget
import idp.sharedkernel.security.UserRole
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.collections.shouldNotContainAnyOf
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldBeEmpty
import tools.jackson.databind.JsonNode
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
private const val RECOVERY_CODES = "/api/account/recovery-codes"
private const val SESSIONS = "/api/account/sessions"
private const val LOGOUT = "/api/account/logout"
private const val CLEARED_SITE_DATA = "\"cache\", \"storage\""
private const val NEW_PASSWORD = "amber-falcon-gathers-slowly"
private const val CODES = 10
private val AGED: Duration = Duration.ofMinutes(11)
private val FACTOR_CHANGES =
    setOf(
        "user.password_set",
        "user.totp_enrolled",
        "user.totp_removed",
        "user.passkey_registered",
        "user.passkey_removed",
        "user.recovery_codes_issued",
    )

fun JsonNode.list(name: String): List<JsonNode> = get(name).values().toList()

fun passwordChangeBody(
    current: String,
    new: String,
    endOtherSessions: Boolean? = null,
) = endOtherSessions?.let { """{"currentPassword":"$current","newPassword":"$new","endOtherSessions":$it}""" }
    ?: """{"currentPassword":"$current","newPassword":"$new"}"""

class AccountApiTest :
    FunSpec({
        val environment = LoginEnvironment()
        val application = environment.start()
        val sessions = environment.acme.table("sso_sessions")

        afterSpec { application.close() }

        fun email(name: String) = "$name@acme.test"

        fun signedIn(name: String): Pair<Browser, UUID> {
            val user = with(environment) { application.user(email(name)) }
            return Browser(application).loggedIn(email(name)) to user
        }

        fun audit(user: UUID) = with(environment) { application.audit() }.filter { it.actor == Actor.User(user) }

        fun actions(user: UUID) = audit(user).map { it.action }

        /** Set from the clock of the test, which the application reads too. */
        fun age(user: UUID) {
            environment.sql.update(
                "UPDATE $sessions SET authenticated_at = ? WHERE user_id = ?",
                Timestamp.from(Instant.now().minus(AGED)),
                user,
            )
        }

        fun Browser.factors(): JsonNode = get(FACTORS).also { it.status shouldBe 200 }.json()

        fun Browser.addTotp(): Pair<String, List<String>> {
            val secret = post(TOTP).also { it.status shouldBe 200 }.json().text("secret")
            val confirmed = post(TOTP_CONFIRMATION, codeBody(totpCode(secret)))
            confirmed.status shouldBe 200
            return secret to confirmed.json().strings("recoveryCodes")
        }

        fun Browser.addPasskey(device: PasskeyDevice): Reply {
            val options = post(PASSKEY_OPTIONS).also { it.status shouldBe 200 }.json()
            return post(PASSKEYS, device.attestation(options))
        }

        fun Browser.passkeyIds(): List<String> = factors().list("passkeys").map { it.text("id") }

        fun Browser.sessions(): List<JsonNode> = get(SESSIONS).also { it.status shouldBe 200 }.json().list("sessions")

        fun Browser.currentSession(): String = sessions().single { it.get("current").asBoolean() }.text("id")

        fun Browser.isSignedIn(): Boolean = get(FACTORS).status == 200

        fun reauthenticated(
            browser: Browser,
            name: String,
        ) {
            val flow = browser.startFlow("""{"reauthenticate":true}""")
            browser.post("/api/flows/$flow/login", loginBody(email(name)))
            browser.password(flow)
            browser.complete(flow).status shouldBe 200
        }

        fun cleared(reply: Reply) {
            reply.status shouldBe 204
            reply.header("Clear-Site-Data") shouldBe CLEARED_SITE_DATA
            reply.cookies().map { it.substringBefore(';') } shouldContainExactlyInAnyOrder
                listOf("${SessionCookie.NAME}=", "${CsrfCookie.NAME}=")
            reply.cookies().forEach { it.split("; ") shouldContain "Max-Age=0" }
        }

        fun refused(reply: Reply) {
            reply.status shouldBe 422
            reply.body.shouldBeEmpty()
        }

        /** Up to the second factor, so a test can tell what the next login asks for. */
        fun secondFactorOf(name: String): Pair<Browser, String> {
            val browser = Browser(application)
            val flow = browser.identified(email(name))
            browser.password(flow).json().text("step") shouldBe "SECOND_FACTOR"
            return browser to flow
        }

        context("the password") {
            test("changes with the current one, and only the new one logs in from then on") {
                val (browser, user) = signedIn("changes")

                browser.post(PASSWORD_CHANGE, passwordChangeBody(PASSWORD, NEW_PASSWORD)).status shouldBe 204

                val next = Browser(application)
                val flow = next.identified(email("changes"))
                next.password(flow, PASSWORD).status shouldBe 422
                next.password(flow, NEW_PASSWORD).json().text("step") shouldBe "COMPLETE"
                val record = audit(user).first { it.action == "user.password_set" }
                record.target shouldBe AuditTarget.user(user)
            }

            test("a wrong current password is refused, audited and counted like a guess at login") {
                val (browser, user) = signedIn("guesses")

                refused(browser.post(PASSWORD_CHANGE, passwordChangeBody(WRONG_PASSWORD, NEW_PASSWORD)))

                environment.failedAttempts(user) shouldBe 1
                val record = audit(user).first { it.action == "account.credential_refused" }
                record.target shouldBe AuditTarget.user(user)
                record.details shouldBe mapOf("factor" to "PASSWORD")
                actions(user).filter { it == "user.password_set" }.shouldBeEmpty()
                Browser(application).loggedIn(email("guesses"))
            }

            test("a new password that breaks the policy is refused and the old one stays") {
                val (browser, user) = signedIn("weak")

                refused(browser.post(PASSWORD_CHANGE, passwordChangeBody(PASSWORD, "short")))
                refused(browser.post(PASSWORD_CHANGE, passwordChangeBody(PASSWORD, "acme-${email("weak")}")))

                actions(user).filter { it == "user.password_set" }.shouldBeEmpty()
                Browser(application).loggedIn(email("weak"))
            }

            test("needs no fresh login, because the current password stands in for one") {
                val (browser, user) = signedIn("aged-password")
                age(user)

                browser.post(PASSWORD_CHANGE, passwordChangeBody(PASSWORD, NEW_PASSWORD)).status shouldBe 204
            }

            test("a body without the current password answers 400") {
                val (browser, _) = signedIn("half")

                browser.post(PASSWORD_CHANGE, """{"newPassword":"$NEW_PASSWORD"}""").status shouldBe 400
            }
        }

        context("TOTP") {
            test("is added with a code of its secret, brings recovery codes, and the next login asks for it") {
                val (browser, user) = signedIn("adds-totp")

                val (secret, codes) = browser.addTotp()

                codes shouldHaveSize CODES
                val factors = browser.factors()
                factors.get("totp").asBoolean() shouldBe true
                factors.get("recoveryCodesLeft").asInt() shouldBe CODES
                val (next, flow) = secondFactorOf("adds-totp")
                next.post("/api/flows/$flow/totp", codeBody(totpCode(secret, shift = 1))).json().text("step") shouldBe "COMPLETE"
                audit(user).filter { it.action in FACTOR_CHANGES }.forEach { it.target shouldBe AuditTarget.user(user) }
                actions(user) shouldContainAll listOf("user.totp_enrolled", "user.recovery_codes_issued")
            }

            test("a wrong code turns nothing on, and the refusal is audited") {
                val (browser, user) = signedIn("wrong-totp")
                val secret = browser.post(TOTP).json().text("secret")

                refused(browser.post(TOTP_CONFIRMATION, codeBody(totpCode(secret, shift = 5))))

                browser.factors().get("totp").asBoolean() shouldBe false
                audit(user).first { it.action == "account.credential_refused" }.details shouldBe mapOf("factor" to "TOTP")
                actions(user) shouldNotContainAnyOf listOf("user.totp_enrolled", "user.recovery_codes_issued")
            }

            test("a confirmed TOTP is replaced by removing it first, and its codes go with the last factor") {
                val (browser, user) = signedIn("replaces-totp")
                val (old, _) = browser.addTotp()

                refused(browser.post(TOTP))
                browser.delete(TOTP).status shouldBe 204

                val factors = browser.factors()
                factors.get("totp").asBoolean() shouldBe false
                factors.get("recoveryCodesLeft").asInt() shouldBe 0
                audit(user).first { it.action == "user.totp_removed" }.details shouldBe mapOf("recovery_codes" to "removed")
                val (renewed, codes) = browser.addTotp()
                (renewed == old) shouldBe false
                codes shouldHaveSize CODES
            }

            test("removing a TOTP that is not there answers 404") {
                val (browser, user) = signedIn("no-totp")
                browser.post(TOTP).status shouldBe 200

                browser.delete(TOTP).status shouldBe 404

                actions(user).filter { it == "user.totp_removed" }.shouldBeEmpty()
            }
        }

        context("passkeys") {
            test("a passkey is added, listed with its dates, and logs in") {
                val (browser, user) = signedIn("adds-passkey")
                val device = PasskeyDevice()

                val options = browser.post(PASSKEY_OPTIONS).json()
                options.text("rpId") shouldBe ACME
                options.text("residentKey") shouldBe "required"
                options.text("userVerification") shouldBe "required"
                options.text("attestation") shouldBe "none"
                val added = browser.post(PASSKEYS, device.attestation(options))

                added.status shouldBe 200
                added.json().strings("recoveryCodes") shouldHaveSize CODES
                val listed = browser.factors().list("passkeys").single()
                listed.get("lastUsedAt").isNull shouldBe true
                Instant.parse(listed.text("createdAt")).isAfter(Instant.now().minus(AGED)) shouldBe true
                val next = Browser(application)
                val flow = next.startFlow()
                val login = next.post("/api/flows/$flow/passkey/options").json()
                next.post("/api/flows/$flow/passkey", device.assertion(login)).json().text("step") shouldBe "COMPLETE"
                actions(user) shouldContainAll listOf("user.passkey_registered", "user.recovery_codes_issued")
                audit(user).filter { it.action in FACTOR_CHANGES }.forEach { it.target shouldBe AuditTarget.user(user) }
            }

            test("the options leave out the passkeys the user has") {
                val (browser, _) = signedIn("excludes")
                browser.addPasskey(PasskeyDevice()).status shouldBe 200

                browser.post(PASSKEY_OPTIONS).json().list("excludeCredentials") shouldHaveSize 1
            }

            test("a second factor keeps the codes the user already has") {
                val (browser, _) = signedIn("second-factor")
                val (_, codes) = browser.addTotp()

                val added = browser.addPasskey(PasskeyDevice())

                added.json().strings("recoveryCodes").shouldBeEmpty()
                browser.factors().get("recoveryCodesLeft").asInt() shouldBe codes.size
            }

            test("a replayed answer is refused and audited") {
                val (browser, user) = signedIn("replays-passkey")
                val options = browser.post(PASSKEY_OPTIONS).json()
                val answer = PasskeyDevice().attestation(options)
                browser.post(PASSKEYS, answer).status shouldBe 200

                refused(browser.post(PASSKEYS, answer))

                browser.passkeyIds() shouldHaveSize 1
                audit(user).first { it.action == "account.credential_refused" }.details shouldBe mapOf("factor" to "PASSKEY")
                actions(user).filter { it == "user.passkey_registered" } shouldHaveSize 1
            }

            test("a body that is no base64url answers 400") {
                val (browser, _) = signedIn("garbled-passkey")

                browser.post(PASSKEYS, """{"attestationObject":"***","clientDataJson":"***"}""").status shouldBe 400
            }

            test("removing deletes one's own, while a passkey of someone else reads as missing and stays") {
                val (mine, user) = signedIn("removes-passkey")
                val (theirs, _) = signedIn("keeps-passkey")
                mine.addPasskey(PasskeyDevice()).status shouldBe 200
                mine.addPasskey(PasskeyDevice()).status shouldBe 200
                theirs.addPasskey(PasskeyDevice()).status shouldBe 200
                val (first, second) = mine.passkeyIds()
                val other = theirs.passkeyIds().single()

                mine.delete("$PASSKEYS/$other").status shouldBe 404
                mine.delete("$PASSKEYS/${UUID.randomUUID()}").status shouldBe 404
                mine.delete("$PASSKEYS/$first").status shouldBe 204

                mine.passkeyIds() shouldContainExactly listOf(second)
                mine.factors().get("recoveryCodesLeft").asInt() shouldBe CODES
                theirs.passkeyIds() shouldContainExactly listOf(other)
                audit(user).filter { it.action == "user.passkey_removed" }.single().details shouldBe mapOf("passkey" to first)
            }

            test("an id that is no UUID answers 400") {
                val (browser, _) = signedIn("bad-id")

                browser.delete("$PASSKEYS/nonsense").status shouldBe 400
            }
        }

        context("recovery codes") {
            test("renewing replaces the old ones, which stop working at login") {
                val (browser, user) = signedIn("renews")
                val (_, old) = browser.addTotp()

                val renewed = browser.post(RECOVERY_CODES)

                renewed.status shouldBe 200
                val codes = renewed.json().strings("recoveryCodes")
                codes shouldHaveSize CODES
                codes shouldNotContainAnyOf old
                val (next, flow) = secondFactorOf("renews")
                refused(next.post("/api/flows/$flow/recovery-code", codeBody(old.first())))
                next.post("/api/flows/$flow/recovery-code", codeBody(codes.first())).json().text("step") shouldBe "COMPLETE"
                actions(user).filter { it == "user.recovery_codes_issued" } shouldHaveSize 2
            }

            test("without a second factor there is nothing to renew") {
                val (browser, user) = signedIn("no-factor")

                refused(browser.post(RECOVERY_CODES))

                actions(user).filter { it == "user.recovery_codes_issued" }.shouldBeEmpty()
            }
        }

        context("a change of a factor") {
            test("asks for a fresh login once the login has aged, changes nothing and records each refusal") {
                val (browser, user) = signedIn("aged-factors")
                browser.addPasskey(PasskeyDevice()).status shouldBe 200
                val passkey = browser.passkeyIds().single()
                age(user)

                val replies =
                    listOf(
                        browser.post(TOTP),
                        browser.post(TOTP_CONFIRMATION, codeBody("000000")),
                        browser.delete(TOTP),
                        browser.post(PASSKEY_OPTIONS),
                        browser.post(PASSKEYS, """{"attestationObject":"AAAA","clientDataJson":"AAAA"}"""),
                        browser.delete("$PASSKEYS/$passkey"),
                        browser.post(RECOVERY_CODES),
                    )

                replies.forEach { reply ->
                    reply.status shouldBe 403
                    reply.json().text("error") shouldBe "reauthentication_required"
                }
                browser.passkeyIds() shouldContainExactly listOf(passkey)
                actions(user).filter { it == "access.reauthentication_required" } shouldHaveSize replies.size
            }

            test("passes again after a login with reauthenticate") {
                val (browser, user) = signedIn("relogs")
                age(user)
                browser.post(TOTP).status shouldBe 403

                reauthenticated(browser, "relogs")

                browser.addTotp()
            }

            test("offers to end the other sessions: the login is still fresh, so they end without another (V7.4.3)") {
                val (browser, user) = signedIn("ends-after-factor")
                val other = Browser(application).loggedIn(email("ends-after-factor"))
                browser.addTotp()

                browser.delete(SESSIONS).status shouldBe 204

                other.isSignedIn() shouldBe false
                browser.isSignedIn() shouldBe true
                audit(user).first { it.action == "session.revoked_for_user" }.details shouldBe mapOf("sessions" to "1")
            }

            test("of the password ends the other sessions and their logins in progress when asked, with no fresh login (V7.4.3)") {
                val (browser, user) = signedIn("ends-after-password")
                val other = Browser(application).loggedIn(email("ends-after-password"))
                val late = Browser(application)
                val flow = late.identified(email("ends-after-password"))
                late.password(flow).json().text("step") shouldBe "COMPLETE"
                age(user)

                browser.post(PASSWORD_CHANGE, passwordChangeBody(PASSWORD, NEW_PASSWORD, endOtherSessions = true)).status shouldBe 204

                other.isSignedIn() shouldBe false
                late.complete(flow).status shouldNotBe 200
                late.isSignedIn() shouldBe false
                browser.isSignedIn() shouldBe true
                val record = audit(user).first { it.action == "session.revoked_for_user" }
                record.target shouldBe AuditTarget.user(user)
                record.details shouldBe mapOf("sessions" to "1")
            }

            test("of the password keeps the other sessions unless asked") {
                val (browser, user) = signedIn("keeps-after-password")
                val other = Browser(application).loggedIn(email("keeps-after-password"))

                browser.post(PASSWORD_CHANGE, passwordChangeBody(PASSWORD, NEW_PASSWORD, endOtherSessions = false)).status shouldBe 204

                other.isSignedIn() shouldBe true
                actions(user).filter { it == "session.revoked_for_user" }.shouldBeEmpty()
            }

            test("of the password with a wrong current one ends no session") {
                val (browser, _) = signedIn("wrong-before-ending")
                val other = Browser(application).loggedIn(email("wrong-before-ending"))

                refused(browser.post(PASSWORD_CHANGE, passwordChangeBody(WRONG_PASSWORD, NEW_PASSWORD, endOtherSessions = true)))

                other.isSignedIn() shouldBe true
            }
        }

        context("sessions") {
            test("the list joins the email of the user with their live sessions, and marks the current one") {
                val (browser, _) = signedIn("lists")
                Browser(application).loggedIn(email("lists"))

                val listed = browser.get(SESSIONS).json()

                listed.text("email") shouldBe email("lists")
                val sessions = listed.list("sessions")
                sessions shouldHaveSize 2
                sessions.count { it.get("current").asBoolean() } shouldBe 1
                sessions.forEach {
                    it.text("userAgent") shouldBe USER_AGENT
                    it.get("ip").isNull shouldBe false
                    Instant.parse(it.text("authenticatedAt")).isAfter(Instant.now().minus(AGED)) shouldBe true
                    Instant.parse(it.text("lastSeenAt")).isAfter(Instant.now().minus(AGED)) shouldBe true
                }
            }

            test("the list names the role of the user, which follows a change on the next request") {
                fun Browser.role(): String = get(SESSIONS).json().text("role")

                val (browser, _) = signedIn("lists-role")
                browser.role() shouldBe "USER"

                val admin = environment.signedIn(application, email("lists-role-admin"))
                val demoted = environment.signedIn(application, email("lists-role-demoted"))
                demoted.browser.role() shouldBe "TENANT_ADMIN"
                admin.browser.put("/api/admin/users/${demoted.user}/role", roleBody(UserRole.USER)).status shouldBe 204

                demoted.browser.role() shouldBe "USER"
            }

            test("each user sees only their own sessions, and a session of someone else reads as missing and lives on") {
                val (mine, user) = signedIn("sees-own-sessions")
                val (theirs, _) = signedIn("sees-other-sessions")
                val other = theirs.currentSession()

                mine.sessions().map { it.text("id") } shouldNotContain other
                mine.delete("$SESSIONS/$other").status shouldBe 404
                mine.delete("$SESSIONS/${UUID.randomUUID()}").status shouldBe 404

                theirs.isSignedIn() shouldBe true
                theirs.sessions().map { it.text("id") } shouldContainExactly listOf(other)
                actions(user).filter { it == "session.revoked" }.shouldBeEmpty()
            }

            test("one other session ends and is audited, while the current one ends only by logging out") {
                val (browser, user) = signedIn("ends-one")
                val other = Browser(application).loggedIn(email("ends-one"))
                val current = browser.currentSession()
                val ended = other.currentSession()

                browser.delete("$SESSIONS/$ended").status shouldBe 204
                browser.delete("$SESSIONS/$current").status shouldBe 404

                other.isSignedIn() shouldBe false
                browser.sessions().map { it.text("id") } shouldContainExactly listOf(current)
                val record = audit(user).single { it.action == "session.revoked" }
                record.target shouldBe AuditTarget.user(user)
                record.details shouldBe mapOf("session" to ended)
            }

            test("ending the other sessions keeps the current one") {
                val (browser, _) = signedIn("ends-others")
                val others = List(2) { Browser(application).loggedIn(email("ends-others")) }

                browser.delete(SESSIONS).status shouldBe 204

                others.forEach { it.isSignedIn() shouldBe false }
                browser.sessions() shouldHaveSize 1
            }

            test("are listed with an aged login, but end only after a fresh one, and each refusal is recorded (V7.5.2)") {
                val (browser, user) = signedIn("ends-after-relogin")
                val other = Browser(application).loggedIn(email("ends-after-relogin"))
                val ended = other.currentSession()
                age(user)

                browser.sessions() shouldHaveSize 2
                listOf(browser.delete(SESSIONS), browser.delete("$SESSIONS/$ended")).forEach { reply ->
                    reply.status shouldBe 403
                    reply.json().text("error") shouldBe "reauthentication_required"
                }
                other.isSignedIn() shouldBe true
                actions(user).filter { it == "access.reauthentication_required" } shouldHaveSize 2

                reauthenticated(browser, "ends-after-relogin")
                browser.delete("$SESSIONS/$ended").status shouldBe 204

                other.isSignedIn() shouldBe false
            }
        }

        context("logout") {
            test("ends the session on the server and clears the cookies and the site data (V7.4.1, V14.3.1)") {
                val (browser, user) = signedIn("logs-out")
                val other = Browser(application).loggedIn(email("logs-out"))
                val token = checkNotNull(browser.cookies[SessionCookie.NAME])
                val session = browser.currentSession()

                cleared(browser.post(LOGOUT))

                browser.isSignedIn() shouldBe false
                val replayed = Browser(application).apply { cookies[SessionCookie.NAME] = token }
                replayed.isSignedIn() shouldBe false
                replayed.get(SESSIONS).status shouldBe 401
                other.isSignedIn() shouldBe true
                val record = audit(user).single { it.action == "session.logged_out" }
                record.target shouldBe AuditTarget.user(user)
                record.details shouldBe mapOf("session" to session)
            }

            test("without a live session still clears the browser") {
                val (browser, _) = signedIn("logs-out-twice")
                browser.post(LOGOUT).status shouldBe 204

                cleared(Browser(application).post(LOGOUT))
                val dead = Browser(application).apply { cookies[SessionCookie.NAME] = "made-up" }
                cleared(dead.post(LOGOUT))
            }

            test("without the CSRF token is refused and the session lives on") {
                val (browser, user) = signedIn("forged-logout")

                val reply = browser.post(LOGOUT, echoCsrf = false)

                reply.status shouldBe 403
                reply.header("Clear-Site-Data") shouldBe null
                browser.isSignedIn() shouldBe true
                actions(user).filter { it == "session.logged_out" }.shouldBeEmpty()
            }
        }

        context("access") {
            test("without a session every call answers 401 and nothing changes") {
                val browser = Browser(application)

                browser.get(FACTORS).status shouldBe 401
                browser.post(PASSWORD_CHANGE, passwordChangeBody(PASSWORD, NEW_PASSWORD)).status shouldBe 401
                browser.post(TOTP).status shouldBe 401
                browser.delete("$PASSKEYS/${UUID.randomUUID()}").status shouldBe 401
                browser.get(SESSIONS).status shouldBe 401
                browser.delete(SESSIONS).status shouldBe 401
                browser.delete("$SESSIONS/${UUID.randomUUID()}").status shouldBe 401
            }

            test("a change without the CSRF token is refused before any handler runs") {
                val (browser, user) = signedIn("forged-change")

                val reply = browser.post(PASSWORD_CHANGE, passwordChangeBody(PASSWORD, NEW_PASSWORD), echoCsrf = false)

                reply.status shouldBe 403
                reply.body.shouldBeEmpty()
                browser.delete(TOTP, echoCsrf = false).status shouldBe 403
                browser.delete(SESSIONS, echoCsrf = false).status shouldBe 403
                actions(user).filter { it == "user.password_set" || it == "session.revoked_for_user" }.shouldBeEmpty()
            }

            test("each user reads only their own factors") {
                val (mine, _) = signedIn("reads-own")
                val (theirs, _) = signedIn("reads-other")
                mine.addTotp()
                mine.addPasskey(PasskeyDevice()).status shouldBe 200

                val factors = theirs.factors()

                factors.get("totp").asBoolean() shouldBe false
                factors.list("passkeys").shouldBeEmpty()
                factors.get("recoveryCodesLeft").asInt() shouldBe 0
            }
        }
    })
