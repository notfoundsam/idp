package idp.app.admin

import idp.app.login.Browser
import idp.app.login.LoginEnvironment
import idp.app.login.Reply
import idp.app.login.TestTenant
import idp.app.login.codeBody
import idp.app.login.text
import idp.app.login.totpCode
import idp.identity.application.IdentityService
import idp.identity.application.UserView
import idp.sharedkernel.audit.Actor
import idp.sharedkernel.audit.AuditTarget
import idp.sharedkernel.security.UserRole
import idp.sharedkernel.security.UserStatus
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldBeEmpty
import java.util.UUID

private const val USERS = "/api/admin/users"
private const val SEARCH = "/api/admin/users/search"
private const val POLICY = "/api/admin/policy"
private const val ALL_SESSIONS = "/api/admin/sessions"
private const val FACTORS = "/api/account/factors"
private const val ACCESS_DENIED = "access.denied"
private const val REAUTHENTICATION = "access.reauthentication_required"
private const val SESSIONS_REVOKED = "session.revoked_for_user"

private fun invitation(id: UUID) = "$USERS/$id/invitation"

private fun role(id: UUID) = "$USERS/$id/role"

private fun block(id: UUID) = "$USERS/$id/block"

private fun unblock(id: UUID) = "$USERS/$id/unblock"

private fun mfaReset(id: UUID) = "$USERS/$id/mfa-reset"

private fun userPath(id: UUID) = "$USERS/$id"

private fun sessions(id: UUID) = "$USERS/$id/sessions"

class AdminUsersApiTest :
    FunSpec({
        val environment = LoginEnvironment()
        val application = environment.start()
        val acme = environment.acme
        val globex = environment.globex

        afterSpec { application.close() }

        fun email(
            name: String,
            tenant: TestTenant = acme,
        ) = "$name@${tenant.host}"

        fun admin(
            name: String,
            tenant: TestTenant = acme,
        ) = environment.signedIn(application, email(name, tenant), tenant)

        fun member(
            name: String,
            tenant: TestTenant = acme,
        ) = environment.signedIn(application, email(name, tenant), tenant, UserRole.USER)

        fun stored(
            id: UUID,
            tenant: TestTenant = acme,
        ): UserView? = tenant.acting { application.getBean(IdentityService::class.java).user(id) }

        fun audit(
            actor: UUID,
            tenant: TestTenant = acme,
        ) = with(environment) { application.audit(tenant) }.filter { it.actor == Actor.User(actor) }

        fun liveSessions(user: UUID): Int =
            environment.sql.queryForObject(
                "SELECT count(*) FROM ${acme.table("sso_sessions")} WHERE user_id = ? AND revoked_at IS NULL",
                Int::class.java,
                user,
            )!!

        fun totps(user: UUID): Int =
            environment.sql.queryForObject(
                "SELECT count(*) FROM ${acme.table("totp_credentials")} WHERE user_id = ?",
                Int::class.java,
                user,
            )!!

        fun invitedEvents(user: UUID): Int =
            environment.sql.queryForObject(
                "SELECT count(*) FROM platform.outbox WHERE tenant_id = ? AND aggregate_id = ? AND event_type = 'user.invited'",
                Int::class.java,
                acme.context.tenantId,
                user,
            )!!

        fun mfaForUsers(): Boolean =
            environment.sql.queryForObject("SELECT mfa_for_users FROM ${acme.table("tenant_policies")}", Boolean::class.java)!!

        fun Browser.isSignedIn(): Boolean = get(FACTORS).status == 200

        fun Browser.addTotp() {
            val secret = post("/api/account/totp").json().text("secret")
            post("/api/account/totp/confirmation", codeBody(totpCode(secret))).status shouldBe 200
        }

        fun Browser.search(address: String) = post(SEARCH, searchBody(address))

        fun Reply.users() = json().get("users").values()

        fun Reply.found() = users().map { UUID.fromString(it.text("id")) }

        /** Every call of the audience, each with a body and the headers it would take. */
        fun Browser.everyCall(target: UUID): List<Reply> =
            listOf(
                search(email("ordinary-target")),
                get(POLICY),
                put(POLICY, policyBody(mfaForUsers = true), "If-Match", "\"0\""),
                post(USERS, inviteBody(email("never-${UUID.randomUUID()}"), UserRole.USER)),
                post(invitation(target)),
                put(role(target), roleBody(UserRole.TENANT_ADMIN)),
                post(block(target)),
                post(unblock(target)),
                post(mfaReset(target)),
                delete(userPath(target)),
                delete(sessions(target)),
                delete(ALL_SESSIONS),
            )

        /** Makes the end of the sessions of [user] fail, so a test can tell whether the change before it rolled back. */
        fun <T> revokingFails(
            user: UUID,
            action: () -> T,
        ): T {
            val function = "${acme.table("refuse_revoke")}()"
            environment.sql.execute(
                "CREATE FUNCTION $function RETURNS trigger LANGUAGE plpgsql AS 'BEGIN RAISE EXCEPTION ''revoking is down''; END'",
            )
            environment.sql.execute(
                "CREATE TRIGGER refuse_revoke BEFORE UPDATE ON ${acme.table("sso_sessions")} " +
                    "FOR EACH ROW WHEN (OLD.user_id = '$user') EXECUTE FUNCTION $function",
            )
            return try {
                action()
            } finally {
                environment.sql.execute("DROP TRIGGER refuse_revoke ON ${acme.table("sso_sessions")}")
                environment.sql.execute("DROP FUNCTION $function")
            }
        }

        context("who may call") {
            test("an ordinary user is refused every call, nothing changes and each refusal is audited") {
                val (browser, user) = member("ordinary")
                val (_, target) = member("ordinary-target")

                browser.everyCall(target).forEach { it.status shouldBe 403 }

                stored(target).shouldNotBeNull().status shouldBe UserStatus.ACTIVE
                stored(target).shouldNotBeNull().role shouldBe UserRole.USER
                liveSessions(target) shouldBe 1
                mfaForUsers() shouldBe false
                audit(user).filter { it.action == ACCESS_DENIED } shouldHaveSize 12
            }

            test("an admin of another tenant has no session here") {
                val (theirs, _) = admin("foreign", globex)
                val (_, target) = member("foreign-target")
                val browser = Browser(application, acme.host).apply { cookies.putAll(theirs.cookies) }

                browser.everyCall(target).forEach { it.status shouldBe 401 }

                stored(target).shouldNotBeNull().status shouldBe UserStatus.ACTIVE
                liveSessions(target) shouldBe 1
                mfaForUsers() shouldBe false
            }

            test("a user of another tenant reads as missing to an admin here") {
                val (browser, _) = admin("reaches-out")
                val (_, target) = member("out-of-reach", globex)

                listOf(browser.post(block(target)), browser.post(mfaReset(target)), browser.delete(userPath(target)))
                    .forEach { it.status shouldBe 404 }
                browser.put(role(target), roleBody(UserRole.TENANT_ADMIN)).status shouldBe 404
                browser.delete(sessions(target)).status shouldBe 404

                stored(target, globex).shouldNotBeNull().status shouldBe UserStatus.ACTIVE
                stored(target, globex).shouldNotBeNull().role shouldBe UserRole.USER
            }

            test("a request without a session gets 401 and one without the CSRF token gets 403") {
                val (browser, _) = admin("no-csrf")
                val (_, target) = member("no-csrf-target")

                Browser(application).everyCall(target).forEach { it.status shouldBe 401 }
                browser.post(block(target), echoCsrf = false).status shouldBe 403

                stored(target).shouldNotBeNull().status shouldBe UserStatus.ACTIVE
            }
        }

        context("searching") {
            test("finds a user by the whole address in any case, and by no part of it") {
                val (browser, _) = admin("searcher")
                val (_, target) = member("sought")

                val found = browser.search(email("sought"))

                found.status shouldBe 200
                found.users().single().run {
                    text("id") shouldBe "$target"
                    text("role") shouldBe "USER"
                    text("status") shouldBe "ACTIVE"
                }
                browser.search(" ${email("sought").uppercase()} ").found() shouldBe listOf(target)
                listOf("sought", "sought@", "sought@${acme.host.substringBefore('.')}", "@${acme.host}", "ought@${acme.host}", "", "%")
                    .forEach { browser.search(it).found().shouldBeEmpty() }
            }

            test("finds an invited user with the status, and nobody of another tenant") {
                val (browser, _) = admin("tenant-searcher")
                val invited = UUID.fromString(browser.post(USERS, inviteBody(email("waiting"), UserRole.USER)).json().text("id"))
                member("elsewhere", globex)

                val found = browser.search(email("waiting"))
                found.found() shouldBe listOf(invited)
                found.users().single().text("status") shouldBe "INVITED"
                browser.search(email("elsewhere", globex)).found().shouldBeEmpty()
            }

            test("a body without an address or with another field answers 400") {
                val (browser, _) = admin("sloppy-searcher")

                listOf("{}", """{"prefix":"sought"}""", """{"email":"${email("searcher")}","exact":false}""")
                    .forEach { browser.post(SEARCH, it).status shouldBe 400 }
            }
        }

        context("inviting") {
            test("creates a user who waits with the role, leaves an event for the mail and is audited") {
                val (browser, admin) = admin("inviter")

                val reply = browser.post(USERS, inviteBody(email("newcomer"), UserRole.TENANT_ADMIN))

                reply.status shouldBe 201
                val id = UUID.fromString(reply.json().text("id"))
                val created = stored(id).shouldNotBeNull()
                created.status shouldBe UserStatus.INVITED
                created.role shouldBe UserRole.TENANT_ADMIN
                invitedEvents(id) shouldBe 1
                val record = audit(admin).single { it.action == "user.created" }
                record.target shouldBe AuditTarget.user(id)
                record.details shouldBe mapOf("role" to "TENANT_ADMIN")
            }

            test("a taken address and one that is not valid answer 422 with no body") {
                val (browser, admin) = admin("refused-inviter")
                browser.post(USERS, inviteBody(email("taken"), UserRole.USER)).status shouldBe 201

                listOf(email("taken"), "not an address").forEach {
                    val reply = browser.post(USERS, inviteBody(it, UserRole.USER))
                    reply.status shouldBe 422
                    reply.body.shouldBeEmpty()
                }

                audit(admin).filter { it.action == "user.created" }.size shouldBe 1
            }

            test("a body without a role, with an unknown one or with an unknown field answers 400") {
                val (browser, _) = admin("sloppy-inviter")

                browser.post(USERS, """{"email":"${email("roleless")}"}""").status shouldBe 400
                browser.post(USERS, """{"email":"${email("roleless")}","role":"OWNER"}""").status shouldBe 400
                browser.post(USERS, """{"email":"${email("roleless")}","role":"USER","status":"ACTIVE"}""").status shouldBe 400
            }

            test("resending leaves a new event for the mail and is audited") {
                val (browser, admin) = admin("resender")
                val id = UUID.fromString(browser.post(USERS, inviteBody(email("forgetful"), UserRole.USER)).json().text("id"))

                browser.post(invitation(id)).status shouldBe 204

                invitedEvents(id) shouldBe 2
                audit(admin).single { it.action == "user.invitation_resent" }.target shouldBe AuditTarget.user(id)
            }

            test("resending is refused for a user who accepted and for one who is not there") {
                val (browser, admin) = admin("late-resender")
                val (_, active) = member("accepted")

                browser.post(invitation(active)).status shouldBe 422
                browser.post(invitation(UUID.randomUUID())).status shouldBe 404

                invitedEvents(active) shouldBe 1
                audit(admin).filter { it.action == "user.invitation_resent" }.shouldBeEmpty()
            }
        }

        context("roles") {
            test("a user made admin reaches the admin API on the next request, and the change is audited") {
                val (browser, admin) = admin("promoter")
                val (promoted, target) = member("promoted")
                promoted.post(invitation(UUID.randomUUID())).status shouldBe 403

                browser.put(role(target), roleBody(UserRole.TENANT_ADMIN)).status shouldBe 204

                promoted.post(invitation(UUID.randomUUID())).status shouldBe 404
                stored(target).shouldNotBeNull().role shouldBe UserRole.TENANT_ADMIN
                val record = audit(admin).single { it.action == "user.role_changed" }
                record.target shouldBe AuditTarget.user(target)
                record.details shouldBe mapOf("from" to "USER", "to" to "TENANT_ADMIN")
            }

            test("an admin made a user loses the admin API on the next request") {
                val (browser, _) = admin("demoter")
                val (demoted, target) = admin("demoted")

                browser.put(role(target), roleBody(UserRole.USER)).status shouldBe 204

                demoted.post(invitation(UUID.randomUUID())).status shouldBe 403
                demoted.isSignedIn() shouldBe true
            }

            test("an unknown role answers 400 and an unknown user 404") {
                val (browser, _) = admin("vague-promoter")
                val (_, target) = member("vague-target")

                browser.put(role(target), """{"role":"OWNER"}""").status shouldBe 400
                browser.put(role(UUID.randomUUID()), roleBody(UserRole.TENANT_ADMIN)).status shouldBe 404

                stored(target).shouldNotBeNull().role shouldBe UserRole.USER
            }
        }

        context("blocking") {
            test("ends every session of the user in the same step, and both are audited") {
                val (browser, admin) = admin("blocker")
                val (blocked, target) = member("blocked")
                val elsewhere = Browser(application).loggedIn(email("blocked"))

                browser.post(block(target)).status shouldBe 204

                stored(target).shouldNotBeNull().status shouldBe UserStatus.BLOCKED
                liveSessions(target) shouldBe 0
                blocked.isSignedIn() shouldBe false
                elsewhere.isSignedIn() shouldBe false
                val actions = audit(admin).filter { it.target == AuditTarget.user(target) }.map { it.action }
                actions shouldContainAll listOf("user.blocked", SESSIONS_REVOKED)
            }

            test("a block whose sessions could not end rolls back") {
                val (browser, admin) = admin("failing-blocker")
                val (blocked, target) = member("not-blocked")

                revokingFails(target) { browser.post(block(target)).status } shouldBe 500

                stored(target).shouldNotBeNull().status shouldBe UserStatus.ACTIVE
                blocked.isSignedIn() shouldBe true
                audit(admin).map { it.action } shouldNotContain "user.blocked"
            }

            test("unblocking lets the user log in again and is audited, while a user who is not blocked answers 422") {
                val (browser, admin) = admin("unblocker")
                val (_, target) = member("unblocked")
                browser.post(block(target)).status shouldBe 204

                browser.post(unblock(target)).status shouldBe 204
                browser.post(unblock(target)).status shouldBe 422

                Browser(application).loggedIn(email("unblocked")).isSignedIn() shouldBe true
                audit(admin).single { it.action == "user.unblocked" }.target shouldBe AuditTarget.user(target)
            }

            test("a blocked user cannot be blocked again, and an unknown one answers 404") {
                val (browser, _) = admin("reblocker")
                val (_, target) = member("reblocked")
                browser.post(block(target)).status shouldBe 204

                browser.post(block(target)).status shouldBe 422
                browser.post(block(UUID.randomUUID())).status shouldBe 404
            }
        }

        context("an MFA reset") {
            test("removes the factors and ends the sessions in the same step, and both are audited") {
                val (browser, admin) = admin("resetter")
                val (target, user) = member("lost-phone")
                target.addTotp()

                browser.post(mfaReset(user)).status shouldBe 204

                totps(user) shouldBe 0
                liveSessions(user) shouldBe 0
                target.isSignedIn() shouldBe false
                stored(user).shouldNotBeNull().status shouldBe UserStatus.ACTIVE
                val actions = audit(admin).filter { it.target == AuditTarget.user(user) }.map { it.action }
                actions shouldContainAll listOf("user.mfa_reset", SESSIONS_REVOKED)
            }

            test("needs a fresh login, and the refusal is audited") {
                val (browser, admin) = admin("aged-resetter")
                val (target, user) = member("keeps-phone")
                target.addTotp()
                environment.age(admin)

                val reply = browser.post(mfaReset(user))

                reply.status shouldBe 403
                reply.json().text("error") shouldBe "reauthentication_required"
                totps(user) shouldBe 1
                target.isSignedIn() shouldBe true
                audit(admin).map { it.action } shouldContain REAUTHENTICATION
            }

            test("a reset whose sessions could not end rolls back") {
                val (browser, admin) = admin("failing-resetter")
                val (target, user) = member("still-has-phone")
                target.addTotp()

                revokingFails(user) { browser.post(mfaReset(user)).status } shouldBe 500

                totps(user) shouldBe 1
                target.isSignedIn() shouldBe true
                audit(admin).map { it.action } shouldNotContain "user.mfa_reset"
            }

            test("an unknown user answers 404") {
                val (browser, _) = admin("vague-resetter")

                browser.post(mfaReset(UUID.randomUUID())).status shouldBe 404
            }
        }

        context("erasing") {
            test("removes the user and their sessions with them, and is audited") {
                val (browser, admin) = admin("eraser")
                val (target, user) = member("leaver")

                browser.delete(userPath(user)).status shouldBe 204

                stored(user).shouldBeNull()
                target.isSignedIn() shouldBe false
                audit(admin).single { it.action == "user.erased" }.target shouldBe AuditTarget.user(user)
                browser.delete(userPath(user)).status shouldBe 404
            }

            test("needs a fresh login, and the refusal is audited") {
                val (browser, admin) = admin("aged-eraser")
                val (_, user) = member("stayer")
                environment.age(admin)

                val reply = browser.delete(userPath(user))

                reply.status shouldBe 403
                reply.json().text("error") shouldBe "reauthentication_required"
                stored(user).shouldNotBeNull()
                audit(admin).map { it.action } shouldContain REAUTHENTICATION
            }
        }

        context("sessions") {
            test("an admin ends every session of one user, the others keep theirs, and it is audited") {
                val (browser, admin) = admin("ender")
                val (ended, target) = member("ended")
                val (kept, _) = member("kept")

                browser.delete(sessions(target)).status shouldBe 204

                ended.isSignedIn() shouldBe false
                kept.isSignedIn() shouldBe true
                stored(target).shouldNotBeNull().status shouldBe UserStatus.ACTIVE
                val record = audit(admin).single { it.action == SESSIONS_REVOKED }
                record.target shouldBe AuditTarget.user(target)
                record.details shouldBe mapOf("sessions" to "1")
            }

            test("an admin who ends their own keeps the session of the request") {
                val (browser, admin, secret) = admin("self-ender")
                val other = Browser(application).loggedInWithTotp(email("self-ender"), checkNotNull(secret))
                liveSessions(admin) shouldBe 2

                browser.delete(sessions(admin)).status shouldBe 204

                browser.isSignedIn() shouldBe true
                other.isSignedIn() shouldBe false
                liveSessions(admin) shouldBe 1
            }

            test("an unknown user answers 404 and nothing is recorded") {
                val (browser, admin) = admin("vague-ender")

                browser.delete(sessions(UUID.randomUUID())).status shouldBe 404

                audit(admin).filter { it.action == SESSIONS_REVOKED }.shouldBeEmpty()
            }
        }

        context("the last admin who can act") {
            test("cannot step down, be blocked or be erased, and keeps the session") {
                val (browser, admin) = admin("last")
                environment.sql.update(
                    "UPDATE ${acme.table("users")} SET status = 'BLOCKED' WHERE role = 'TENANT_ADMIN' AND status = 'ACTIVE' AND id <> ?",
                    admin,
                )

                browser.put(role(admin), roleBody(UserRole.USER)).status shouldBe 422
                browser.post(block(admin)).status shouldBe 422
                browser.delete(userPath(admin)).status shouldBe 422

                val stored = stored(admin).shouldNotBeNull()
                stored.role shouldBe UserRole.TENANT_ADMIN
                stored.status shouldBe UserStatus.ACTIVE
                browser.isSignedIn() shouldBe true
            }
        }

        context("every session of the tenant") {
            test("ends, the one of the admin too, leaves the other tenant alone, and is audited") {
                val (browser, admin) = admin("panicker")
                val (member, _) = member("panicked")
                val (foreign, _) = member("calm", globex)

                browser.delete(ALL_SESSIONS).status shouldBe 204

                browser.isSignedIn() shouldBe false
                member.isSignedIn() shouldBe false
                foreign.isSignedIn() shouldBe true
                val record = audit(admin).single { it.action == "session.revoked_for_tenant" }
                record.target shouldBe AuditTarget.tenant(acme.context.tenantId)
            }
        }
    })
