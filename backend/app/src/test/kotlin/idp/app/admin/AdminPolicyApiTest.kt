package idp.app.admin

import idp.app.login.Browser
import idp.app.login.LoginEnvironment
import idp.app.login.Reply
import idp.app.login.TestTenant
import idp.app.login.text
import idp.authentication.application.CsrfCookie
import idp.sharedkernel.audit.Actor
import idp.sharedkernel.security.UserRole
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.util.UUID

private const val POLICY = "/api/admin/policy"
private const val IF_MATCH = "If-Match"
private const val CHANGED = "policy.changed"

class AdminPolicyApiTest :
    FunSpec({
        val environment = LoginEnvironment()
        val application = environment.start()
        val acme = environment.acme
        val globex = environment.globex

        beforeTest { environment.resetPolicies() }

        afterSpec { application.close() }

        fun admin(
            name: String,
            tenant: TestTenant = acme,
        ) = environment.signedIn(application, "$name@${tenant.host}", tenant)

        fun member(
            name: String,
            tenant: TestTenant = acme,
        ) = environment.signedIn(application, "$name@${tenant.host}", tenant, UserRole.USER)

        fun audit(
            actor: UUID,
            tenant: TestTenant = acme,
        ) = with(environment) { application.audit(tenant) }.filter { it.actor == Actor.User(actor) }

        fun stored(tenant: TestTenant = acme): Map<String, Any?> =
            environment.sql.queryForMap(
                "SELECT mfa_for_users, passkey_required, session_idle_seconds, session_absolute_seconds " +
                    "FROM ${tenant.table("tenant_policies")}",
            )

        fun Reply.etag(): String = header("ETag").shouldNotBeNull()

        fun Browser.save(
            body: String,
            etag: String,
        ) = put(POLICY, body, IF_MATCH, etag)

        context("reading") {
            test("an admin gets the policy of the tenant with its version in ETag") {
                val (browser, _) = admin("reader")

                val reply = browser.get(POLICY)

                reply.status shouldBe 200
                reply.etag().matches(Regex("\"\\d+\"")) shouldBe true
                reply.json().run {
                    get("mfaForUsers").asBoolean() shouldBe false
                    get("passkeyRequired").asBoolean() shouldBe false
                    get("sessionIdleSeconds").asLong() shouldBe 1800
                    get("sessionAbsoluteSeconds").asLong() shouldBe 43200
                }
                val other = admin("other-reader", globex).browser.get(POLICY)
                other.json().get("mfaForUsers").asBoolean() shouldBe true
            }
        }

        context("saving") {
            test("replaces the policy at the version read, answers the new ETag and records what changed") {
                val (browser, admin) = admin("saver")
                val read = browser.get(POLICY).etag()

                val saved = browser.save(policyBody(passkeyRequired = true, idleSeconds = 900, absoluteSeconds = 86400), read)

                saved.status shouldBe 204
                saved.etag() shouldNotBe read
                browser.get(POLICY).etag() shouldBe saved.etag()
                stored() shouldBe
                    mapOf(
                        "mfa_for_users" to false,
                        "passkey_required" to true,
                        "session_idle_seconds" to 900L,
                        "session_absolute_seconds" to 86400L,
                    )
                audit(admin).single { it.action == CHANGED }.details shouldBe
                    mapOf("passkey_required" to "true", "session_idle_seconds" to "900", "session_absolute_seconds" to "86400")
            }

            test("the same values keep the ETag and record nothing") {
                val (browser, admin) = admin("idle-saver")
                val read = browser.get(POLICY).etag()

                val saved = browser.save(policyBody(), read)

                saved.status shouldBe 204
                saved.etag() shouldBe read
                audit(admin).filter { it.action == CHANGED }.shouldBeEmpty()
            }

            test("a save over a policy another admin saved meanwhile answers 412 and keeps theirs") {
                val (first, firstAdmin) = admin("slow-saver")
                val (second, _) = admin("quick-saver")
                val read = first.get(POLICY).etag()
                second.save(policyBody(idleSeconds = 600), second.get(POLICY).etag()).status shouldBe 204

                first.save(policyBody(idleSeconds = 3600), read).status shouldBe 412
                first.save(policyBody(idleSeconds = 600), read).status shouldBe 412

                stored()["session_idle_seconds"] shouldBe 600L
                audit(firstAdmin).filter { it.action == CHANGED }.shouldBeEmpty()
                first.save(policyBody(idleSeconds = 3600), first.get(POLICY).etag()).status shouldBe 204
                stored()["session_idle_seconds"] shouldBe 3600L
            }

            test("a save without If-Match answers 428, and one that names no version 412") {
                val (browser, admin) = admin("blind-saver")
                val current = browser.get(POLICY).etag()

                browser.put(POLICY, policyBody(idleSeconds = 600)).status shouldBe 428
                listOf("*", "W/$current", current.removeSurrounding("\""), "\"one\"", "\"\"").forEach {
                    browser.save(policyBody(idleSeconds = 600), it).status shouldBe 412
                }

                stored()["session_idle_seconds"] shouldBe 1800L
                audit(admin).filter { it.action == CHANGED }.shouldBeEmpty()
            }

            test("needs a fresh login, and the refusal is audited") {
                val (browser, admin) = admin("aged-saver")
                val read = browser.get(POLICY).etag()
                environment.age(admin)

                val reply = browser.save(policyBody(mfaForUsers = true), read)

                reply.status shouldBe 403
                reply.json().text("error") shouldBe "reauthentication_required"
                stored()["mfa_for_users"] shouldBe false
                audit(admin).map { it.action } shouldContain "access.reauthentication_required"
            }

            test("a body with a field missing or unknown answers 400") {
                val (browser, _) = admin("sloppy-saver")
                val read = browser.get(POLICY).etag()

                listOf(
                    "{}",
                    """{"mfaForUsers":false,"passkeyRequired":false,"sessionIdleSeconds":1800}""",
                    """{"mfaForUsers":"no","passkeyRequired":false,"sessionIdleSeconds":1800,"sessionAbsoluteSeconds":43200}""",
                    policyBody().dropLast(1) + ""","lockoutMinutes":15}""",
                ).forEach { browser.save(it, read).status shouldBe 400 }

                browser.get(POLICY).etag() shouldBe read
            }

            test("an ordinary user and a request without the CSRF token are refused, and nothing changes") {
                val (browser, _) = admin("csrf-saver")
                val (ordinary, user) = member("policy-onlooker")
                val read = browser.get(POLICY).etag()

                ordinary.get(POLICY).status shouldBe 403
                ordinary.save(policyBody(mfaForUsers = true), read).status shouldBe 403
                val forged = Browser(application, acme.host).apply { cookies.putAll(browser.cookies - CsrfCookie.NAME) }
                forged.save(policyBody(mfaForUsers = true), read).status shouldBe 403
                Browser(application).get(POLICY).status shouldBe 401

                stored()["mfa_for_users"] shouldBe false
                audit(user).count { it.action == "access.denied" } shouldBe 2
            }
        }

        context("the rules of the policy") {
            test("MFA for TENANT_ADMIN cannot be dropped: no field takes it, and an admin enrolls a factor under a relaxed policy") {
                val (browser, admin) = admin("relaxer", globex)
                val read = browser.get(POLICY).etag()
                listOf("mfaForAdmins", "mfaForTenantAdmins", "mfa").forEach {
                    browser.save(policyBody().dropLast(1) + ""","$it":false}""", read).status shouldBe 400
                }

                browser.save(policyBody(mfaForUsers = false), read).status shouldBe 204

                member("relaxed-user", globex).secret.shouldBeNull()
                admin("strict-admin", globex).secret.shouldNotBeNull()
                audit(admin, globex).single { it.action == CHANGED }.details shouldBe mapOf("mfa_for_users" to "false")
            }

            test("the policy has no expiry of the password: the answer names none and a body that sets one is refused") {
                val (browser, _) = admin("expirer")
                val read = browser.get(POLICY)

                read.json().propertyNames().toSet() shouldBe
                    setOf("mfaForUsers", "passkeyRequired", "sessionIdleSeconds", "sessionAbsoluteSeconds")
                listOf("passwordMaxAgeDays", "passwordExpiry", "passwordExpiresAfterDays").forEach {
                    browser.save(policyBody().dropLast(1) + ""","$it":90}""", read.etag()).status shouldBe 400
                }
            }

            test("timeouts outside the limits of AAL2, and an idle one longer than the absolute one, answer 422 and change nothing") {
                val (browser, admin) = admin("stretcher")
                val read = browser.get(POLICY).etag()

                listOf(
                    policyBody(idleSeconds = 3601, absoluteSeconds = 43200),
                    policyBody(idleSeconds = 299),
                    policyBody(idleSeconds = 0),
                    policyBody(idleSeconds = -1800),
                    policyBody(absoluteSeconds = 86401),
                    policyBody(idleSeconds = 3600, absoluteSeconds = 1800),
                ).forEach { browser.save(it, read).status shouldBe 422 }

                browser.get(POLICY).etag() shouldBe read
                audit(admin).filter { it.action == CHANGED }.shouldBeEmpty()
                browser.save(policyBody(idleSeconds = 3600, absoluteSeconds = 86400), read).status shouldBe 204
                browser.save(policyBody(idleSeconds = 300, absoluteSeconds = 300), browser.get(POLICY).etag()).status shouldBe 204
            }

            test("a tighter absolute timeout ends a session that is already older") {
                val (browser, admin) = admin("tightener")
                val (member, user) = member("outlived")
                environment.age(user)

                browser.save(policyBody(idleSeconds = 300, absoluteSeconds = 600), browser.get(POLICY).etag()).status shouldBe 204

                member.get("/api/account/factors").status shouldBe 401
                audit(admin).single { it.action == CHANGED }.details shouldBe
                    mapOf("session_idle_seconds" to "300", "session_absolute_seconds" to "600")
            }
        }
    })
