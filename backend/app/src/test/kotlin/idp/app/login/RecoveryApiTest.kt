package idp.app.login

import idp.authentication.application.SessionCookie
import idp.identity.application.IdentityEvents
import idp.identity.application.IdentityService
import idp.sharedkernel.audit.Actor
import idp.sharedkernel.security.UserRole
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldBeEmpty
import io.kotest.matchers.string.shouldNotContain
import java.util.UUID

private const val PASSWORD_RESET = "/api/password-reset"
private const val CONFIRM_RESET = "/api/password-reset/confirmation"
private const val EMAIL_CHANGE = "/api/email-change"
private const val CONFIRM_EMAIL = "/api/email-change/confirmation"
private const val NEW_PASSWORD = "silver-heron-crosses-slowly"

private fun email(name: String) = "$name@acme.test"

class RecoveryApiTest :
    FunSpec({
        val environment = LoginEnvironment()
        val application = environment.start()
        val sql = environment.sql
        val identity = application.getBean(IdentityService::class.java)

        afterSpec { application.close() }

        fun browser(host: String = ACME) = Browser(application, host)

        fun user(
            name: String,
            tenant: TestTenant = environment.acme,
        ): UUID = with(environment) { application.user(email(name), tenant) }

        fun audit(tenant: TestTenant = environment.acme) = with(environment) { application.audit(tenant) }

        fun invited(
            name: String,
            tenant: TestTenant,
            role: UserRole = UserRole.USER,
        ): String =
            tenant.acting {
                val id = identity.invite(email(name), role, Actor.System)
                identity.issueInvitation(id, Actor.System).shouldNotBeNull()
            }

        fun resetToken(
            user: UUID,
            tenant: TestTenant = environment.acme,
        ): String = tenant.acting { identity.issuePasswordReset(user, Actor.System) }.shouldNotBeNull()

        fun outboxEvents(
            tenant: TestTenant,
            type: String,
        ): Int =
            sql.queryForObject(
                "SELECT count(*) FROM platform.outbox WHERE tenant_id = ? AND event_type = ?",
                Int::class.java,
                tenant.context.tenantId,
                type,
            )!!

        /** A user at `globex`, which requires MFA, who finished enrolling TOTP. */
        fun withTotp(name: String): Pair<UUID, String> {
            val id = user(name, environment.globex)
            val browser = browser(GLOBEX)
            val flow = browser.identified(email(name))
            browser.password(flow)
            val secret = browser.post("/api/flows/$flow/enrollment/totp").json().text("secret")
            browser.post("/api/flows/$flow/enrollment/totp/confirmation", codeBody(totpCode(secret))).status shouldBe 200
            browser.complete(flow).status shouldBe 200
            return id to secret
        }

        fun age(tenant: TestTenant = environment.acme) {
            sql.update("UPDATE ${tenant.table("sso_sessions")} SET authenticated_at = now() - interval '1 hour'")
        }

        context("accepting an invitation") {
            test("sets the password and walks on to the enrollment the policy asks for") {
                val token = invited("newcomer", environment.globex)
                val browser = browser(GLOBEX)
                val flow = browser.startFlow()

                val reply = browser.post("/api/flows/$flow/invitation", invitationBody(token, PASSWORD))

                reply.status shouldBe 200
                reply.json().text("step") shouldBe "ENROLL_MFA"
                reply.json().strings("factors").shouldContainExactlyInAnyOrder("TOTP", "PASSKEY")
                browser.cookies.keys shouldNotContain SessionCookie.NAME

                val secret = browser.post("/api/flows/$flow/enrollment/totp").json().text("secret")
                browser.post("/api/flows/$flow/enrollment/totp/confirmation", codeBody(totpCode(secret))).status shouldBe 200
                browser.complete(flow).status shouldBe 200
                browser.cookies.keys shouldContain SessionCookie.NAME
            }

            test("the password it set works on the next login") {
                val token = invited("returns", environment.acme)
                val browser = browser()
                browser.post("/api/flows/${browser.startFlow()}/invitation", invitationBody(token, PASSWORD)).status shouldBe 200

                val next = browser()
                val flow = next.identified(email("returns"))

                next.password(flow).status shouldBe 200
            }

            test("a browser with a live session of another user starts anew") {
                user("present")
                val browser = browser().loggedIn(email("present"))
                val token = invited("visitor", environment.acme)

                val continuing = browser.startFlow()
                browser.post("/api/flows/$continuing/invitation", invitationBody(token, PASSWORD)).status shouldBe 409

                val fresh = browser.startFlow("""{"reauthenticate":true}""")
                browser.post("/api/flows/$fresh/invitation", invitationBody(token, PASSWORD)).status shouldBe 200
            }

            test("a made-up token and a weak password get the same bare answer") {
                val token = invited("mistaken", environment.acme)
                val browser = browser()
                val flow = browser.startFlow()

                val madeUp = browser.post("/api/flows/$flow/invitation", invitationBody("made-up", PASSWORD))
                val weak = browser.post("/api/flows/$flow/invitation", invitationBody(token, "short"))

                listOf(madeUp, weak).forEach {
                    it.status shouldBe 422
                    it.body.shouldBeEmpty()
                }
                browser.post("/api/flows/$flow/invitation", invitationBody(token, PASSWORD)).status shouldBe 200
            }
        }

        context("a forgotten password") {
            test("answers the same for an active user, a blocked one and nobody at all") {
                user("known")
                val blocked = user("blocked")
                sql.update("UPDATE ${environment.acme.table("users")} SET status = 'BLOCKED' WHERE id = ?", blocked)

                val replies = listOf("known", "blocked", "stranger").map { browser().post(PASSWORD_RESET, loginBody(email(it))) }

                replies.forEach {
                    it.status shouldBe 202
                    it.body.shouldBeEmpty()
                }
                replies.map { it.cookies() }.toSet() shouldHaveSize 1
            }

            test("leaves the same event for a login that matched nobody, so the work is the same") {
                val tenant = environment.globex
                val before = outboxEvents(tenant, IdentityEvents.PASSWORD_RESET_REQUESTED)
                user("counted", tenant)

                browser(GLOBEX).post(PASSWORD_RESET, loginBody(email("counted")))
                browser(GLOBEX).post(PASSWORD_RESET, loginBody(email("uncounted")))

                outboxEvents(tenant, IdentityEvents.PASSWORD_RESET_REQUESTED) shouldBe before + 2
            }

            test("a reset opens no session") {
                val owner = user("resets")
                val browser = browser()

                val reply = browser.post(CONFIRM_RESET, invitationBody(resetToken(owner), NEW_PASSWORD))

                reply.status shouldBe 204
                reply.cookies().shouldBeEmpty()
                browser.cookies.keys shouldNotContain SessionCookie.NAME
            }

            test("the next login still asks for the second factor") {
                val (owner, secret) = withTotp("guarded")

                browser(GLOBEX).post(CONFIRM_RESET, invitationBody(resetToken(owner, environment.globex), NEW_PASSWORD)).status shouldBe 204

                val next = browser(GLOBEX)
                val flow = next.identified(email("guarded"))
                val after = next.password(flow, NEW_PASSWORD).json()
                after.text("step") shouldBe "SECOND_FACTOR"
                after.strings("factors").shouldContainExactlyInAnyOrder("TOTP", "RECOVERY_CODE")
                next.complete(flow).status shouldBe 409
                next.post("/api/flows/$flow/totp", codeBody(totpCode(secret, shift = 1))).json().text("step") shouldBe "COMPLETE"
            }

            test("the old password stops working and the session behind it ends") {
                val owner = user("replaced")
                val old = browser().loggedIn(email("replaced"))

                browser().post(CONFIRM_RESET, invitationBody(resetToken(owner), NEW_PASSWORD)).status shouldBe 204

                old.post("/api/flows").json().text("step") shouldBe "IDENTIFY"
                val next = browser()
                next.password(next.identified(email("replaced"))).status shouldBe 422
            }

            test("a login that proved the old password cannot finish after the reset") {
                val owner = user("raced")
                val thief = browser()
                val flow = thief.identified(email("raced"))
                thief.password(flow).json().text("step") shouldBe "COMPLETE"

                browser().post(CONFIRM_RESET, invitationBody(resetToken(owner), NEW_PASSWORD)).status shouldBe 204

                thief.complete(flow).status shouldBe 410
                thief.cookies.keys shouldNotContain SessionCookie.NAME
            }

            test("a made-up token and a weak password get the same bare answer") {
                val owner = user("refused")
                val token = resetToken(owner)

                val madeUp = browser().post(CONFIRM_RESET, invitationBody("made-up", NEW_PASSWORD))
                val weak = browser().post(CONFIRM_RESET, invitationBody(token, "short"))

                listOf(madeUp, weak).forEach {
                    it.status shouldBe 422
                    it.body.shouldBeEmpty()
                }
                browser().post(CONFIRM_RESET, invitationBody(token, NEW_PASSWORD)).status shouldBe 204
            }

            test("the token never needs the URL, and a token in the query does nothing") {
                val owner = user("queried")
                val token = resetToken(owner)

                browser().post("$CONFIRM_RESET?token=$token", """{"password":"$NEW_PASSWORD"}""").status shouldBe 400
            }

            test("a token of one tenant means nothing in another") {
                val owner = user("elsewhere")

                browser(GLOBEX).post(CONFIRM_RESET, invitationBody(resetToken(owner), NEW_PASSWORD)).status shouldBe 422
            }

            test("is in the journal, and the login is not") {
                user("journaled")

                browser().post(PASSWORD_RESET, loginBody("typed-by-mistake-as-a-password"))

                val records = audit().filter { it.action == "user.password_reset_requested" }
                records.shouldNotBeEmpty()
                records.forEach { it.toString() shouldNotContain "typed-by-mistake" }
            }
        }

        context("losing every factor") {
            test("nothing in the login API brings a factor back: the recovery routes leave it standing") {
                val (owner, _) = withTotp("lost")
                val tenant = environment.globex

                browser(GLOBEX).post(CONFIRM_RESET, invitationBody(resetToken(owner, tenant), NEW_PASSWORD)).status shouldBe 204

                sql.queryForObject(
                    "SELECT count(*) FROM ${tenant.table("totp_credentials")} WHERE user_id = ? AND confirmed_at IS NOT NULL",
                    Int::class.java,
                    owner,
                ) shouldBe 1
                val browser = browser(GLOBEX)
                val flow = browser.identified(email("lost"))
                browser.password(flow, NEW_PASSWORD).json().text("step") shouldBe "SECOND_FACTOR"
                browser.post("/api/flows/$flow/enrollment/totp").status shouldBe 409
                browser.post("/api/flows/$flow/enrollment/passkey/options").status shouldBe 409
                browser.complete(flow).status shouldBe 409
            }

            test("the tenant admin gives the way back, and the user enrolls again") {
                val (owner, _) = withTotp("restored")
                val tenant = environment.globex

                tenant.acting { identity.resetMfa(owner, Actor.User(UUID.randomUUID())) }

                val browser = browser(GLOBEX)
                val flow = browser.identified(email("restored"))
                browser.password(flow).json().text("step") shouldBe "ENROLL_MFA"
                val secret = browser.post("/api/flows/$flow/enrollment/totp").json().text("secret")
                browser.post("/api/flows/$flow/enrollment/totp/confirmation", codeBody(totpCode(secret))).status shouldBe 200
                browser.complete(flow).status shouldBe 200
            }
        }

        context("changing the email") {
            test("needs a session") {
                browser().post(EMAIL_CHANGE, emailBody("new@acme.test")).status shouldBe 401
            }

            test("needs the CSRF token of the session") {
                user("forged")
                val browser = browser().loggedIn(email("forged"))

                browser.post(EMAIL_CHANGE, emailBody("new@acme.test"), echoCsrf = false).status shouldBe 403
            }

            test("an old session is not enough, and the refusal says what would help") {
                val owner = user("stale")
                val browser = browser().loggedIn(email("stale"))
                age()

                val reply = browser.post(EMAIL_CHANGE, emailBody("stale.new@acme.test"))

                reply.status shouldBe 403
                reply.json().text("error") shouldBe "reauthentication_required"
                environment.acme.acting { identity.pendingEmailOf(owner) } shouldBe null
                audit().map { it.action } shouldContain "access.reauthentication_required"
            }

            test("a flow that only continues the session does not refresh it") {
                user("continues")
                val browser = browser().loggedIn(email("continues"))
                age()
                val flow = browser.startFlow()
                browser.get("/api/flows/$flow").json().text("step") shouldBe "COMPLETE"
                browser.complete(flow).status shouldBe 200

                browser.post(EMAIL_CHANGE, emailBody("continues.new@acme.test")).status shouldBe 403
            }

            test("a full authentication opens the way") {
                val owner = user("fresh")
                val browser = browser().loggedIn(email("fresh"))
                age()
                val flow = browser.startFlow("""{"reauthenticate":true}""")
                browser.post("/api/flows/$flow/login", loginBody(email("fresh")))
                browser.password(flow)
                browser.complete(flow).status shouldBe 200

                browser.post(EMAIL_CHANGE, emailBody("fresh.new@acme.test")).status shouldBe 202

                environment.acme.acting { identity.pendingEmailOf(owner) } shouldBe "fresh.new@acme.test"
            }

            test("a confirmation needs no session, and the login moves to the new address") {
                val owner = user("moved")
                val browser = browser().loggedIn(email("moved"))
                browser.post(EMAIL_CHANGE, emailBody("moved.new@acme.test")).status shouldBe 202
                val token = environment.acme.acting { identity.issueEmailChange(owner, Actor.System) }.shouldNotBeNull()

                browser().post(CONFIRM_EMAIL, tokenBody(token)).status shouldBe 204

                val next = browser()
                next.password(next.identified("moved.new@acme.test")).status shouldBe 200
                val old = browser()
                old.password(old.identified(email("moved"))).status shouldBe 422
                browser.post("/api/flows").json().text("step") shouldNotBe "IDENTIFY"
            }

            test("an address that belongs to someone else answers like any other until the very end") {
                val owner = user("collides")
                user("occupied")
                val browser = browser().loggedIn(email("collides"))

                val reply = browser.post(EMAIL_CHANGE, emailBody(email("occupied")))

                reply.status shouldBe 202
                val token = environment.acme.acting { identity.issueEmailChange(owner, Actor.System) }.shouldNotBeNull()
                browser().post(CONFIRM_EMAIL, tokenBody(token)).status shouldBe 422
                environment.acme.acting { identity.emailOf(owner) } shouldBe email("collides")
            }
        }
    })
