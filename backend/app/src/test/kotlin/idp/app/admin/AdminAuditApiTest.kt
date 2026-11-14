package idp.app.admin

import idp.app.login.Browser
import idp.app.login.LoginEnvironment
import idp.app.login.Reply
import idp.app.login.TestTenant
import idp.app.login.text
import idp.audit.application.AuditEntryView
import idp.audit.application.AuditService
import idp.federation.TestSp
import idp.sharedkernel.audit.Actor
import idp.sharedkernel.audit.AuditTarget
import idp.sharedkernel.security.UserRole
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import tools.jackson.databind.JsonNode
import java.net.URLEncoder
import java.util.UUID

private const val AUDIT = "/api/admin/audit"
private const val USERS = "/api/admin/users"
private const val SPS = "/api/admin/service-providers"
private const val ACCESS_DENIED = "access.denied"
private const val LOGIN = "login.succeeded"
private const val SERVICE_LIMIT = 500
private const val DEFAULT_PAGE = 50

private fun query(vararg parameters: Pair<String, Any>) =
    AUDIT + parameters.joinToString("&", prefix = "?") { (name, value) -> "$name=${URLEncoder.encode("$value", Charsets.UTF_8)}" }

private fun about(target: AuditTarget) = query("targetType" to target.type.uppercase(), "targetId" to target.id)

private fun JsonNode.nullable(name: String): String? = get(name).takeUnless { it.isNull }?.asString()

private fun JsonNode.details(): Map<String, String>? =
    get("details").takeUnless { it.isNull }?.let { details -> details.propertyNames().associateWith { details.text(it) } }

class AdminAuditApiTest :
    FunSpec({
        val environment = LoginEnvironment()
        val application = environment.start()
        val acme = environment.acme
        val globex = environment.globex
        val audit = application.getBean(AuditService::class.java)

        afterSpec { application.close() }

        fun email(
            name: String,
            tenant: TestTenant = acme,
        ) = "$name@${tenant.host}"

        fun admin(
            name: String,
            tenant: TestTenant = acme,
        ) = environment.signedIn(application, email(name, tenant), tenant)

        fun member(name: String) = environment.signedIn(application, email(name), acme, UserRole.USER)

        fun stored(
            target: AuditTarget? = null,
            tenant: TestTenant = acme,
        ): List<AuditEntryView> = tenant.acting { audit.read(SERVICE_LIMIT, target = target) }

        fun Reply.entries(): List<JsonNode> = json().get("entries").values().toList()

        fun Reply.ids(): List<UUID> = entries().map { UUID.fromString(it.text("id")) }

        fun Browser.all(path: String): List<JsonNode> {
            val entries = mutableListOf<JsonNode>()
            var page = get(path)
            while (true) {
                page.status shouldBe 200
                entries += page.entries()
                val next = page.json().get("next")
                if (next.isNull) return entries
                page = get("$path&${query("before" to next.text("before"), "beforeId" to next.text("beforeId")).substringAfter('?')}")
            }
        }

        context("who may read") {
            test("an ordinary user is refused, and the refusal is audited") {
                val (browser, user) = member("journal-ordinary")

                val reply = browser.get(AUDIT)

                reply.status shouldBe 403
                reply.body shouldBe ""
                val denial = stored().first { it.actor == Actor.User(user) && it.action == ACCESS_DENIED }
                denial.details.shouldNotBeNull()["message"] shouldContain "ReadJournal"
            }

            test("nobody without a session gets in, and an admin of another tenant has none here") {
                val (theirs, _) = admin("journal-foreign", globex)
                val foreign = Browser(application, acme.host).apply { cookies.putAll(theirs.cookies) }

                Browser(application).get(AUDIT).status shouldBe 401
                foreign.get(AUDIT).status shouldBe 401
            }

            test("an admin reads only the journal of their tenant") {
                val (browser, _) = admin("journal-isolated", globex)
                val (_, ours) = member("journal-not-theirs")

                val theirs = browser.get(query("limit" to 100)).ids()

                theirs.shouldNotBeEmpty()
                val ourIds = stored().map { it.id }.toSet()
                theirs.filter { it in ourIds }.shouldBeEmpty()
                browser.get(about(AuditTarget.user(ours))).entries().shouldBeEmpty()
            }
        }

        context("reading") {
            test("gives the newest records first with the IP and the details, from one query") {
                val (browser, admin) = admin("journal-reader")
                val (_, user) = member("journal-promoted")
                browser.put("$USERS/$user/role", roleBody(UserRole.TENANT_ADMIN)).status shouldBe 204

                val (reply, messages) = messagesDuring { browser.get(query("limit" to 5)) }

                reply.status shouldBe 200
                messages.single() shouldContain "message=idp.audience.admin.application.query.ReadJournal outcome=OK"
                val entries = reply.entries()
                entries shouldHaveSize 5
                reply.ids() shouldBe stored().take(5).map { it.id }
                val expected = stored().first()
                entries.first().run {
                    propertyNames().toSet() shouldBe
                        setOf("id", "occurredAt", "action", "actorType", "actorId", "targetType", "targetId", "ip", "details")
                    text("action") shouldBe "user.role_changed"
                    text("actorType") shouldBe "USER"
                    text("actorId") shouldBe "$admin"
                    text("targetType") shouldBe "USER"
                    text("targetId") shouldBe "$user"
                    nullable("ip").shouldNotBeNull() shouldBe expected.ip
                    details() shouldBe mapOf("from" to "USER", "to" to "TENANT_ADMIN")
                }
                reply.json().get("next").run {
                    text("before") shouldBe entries.last().text("occurredAt")
                    text("beforeId") shouldBe entries.last().text("id")
                }
            }

            test("a record of an erased actor keeps the fact and loses the IP and the details, the records of others stay whole") {
                val (browser, admin) = admin("journal-eraser")
                val (_, user) = member("journal-erased")
                val target = about(AuditTarget.user(user))
                val before = browser.get(target).entries().single { it.text("action") == LOGIN }
                before.nullable("ip").shouldNotBeNull()
                before.details().shouldNotBeNull()

                browser.delete("$USERS/$user").status shouldBe 204

                val entries = browser.get(target).entries()
                entries.single { it.text("id") == before.text("id") }.run {
                    text("action") shouldBe LOGIN
                    text("actorType") shouldBe "USER"
                    text("actorId") shouldBe "$user"
                    text("occurredAt") shouldBe before.text("occurredAt")
                    get("ip").isNull shouldBe true
                    get("details").isNull shouldBe true
                }
                entries.single { it.text("action") == "user.erased" }.run {
                    text("actorId") shouldBe "$admin"
                    nullable("ip").shouldNotBeNull()
                    details() shouldBe emptyMap()
                }
                entries.single { it.text("action") == "user.created" }.run {
                    text("actorType") shouldBe "SYSTEM"
                    get("actorId").isNull shouldBe true
                    get("ip").isNull shouldBe true
                    details() shouldBe mapOf("role" to "USER")
                }
                entries.map { it.text("id") } shouldBe stored(AuditTarget.user(user)).map { "${it.id}" }
            }

            test("a whole page of erased actors still reads") {
                val (browser, _) = admin("journal-mass-eraser")
                val users = (1..3).map { member("journal-gone-$it").user }
                users.forEach { browser.delete("$USERS/$it").status shouldBe 204 }
                val logins = stored().filter { it.action == LOGIN && it.actor in users.map(Actor::User) }

                val reply = browser.get(query("limit" to 100))

                reply.status shouldBe 200
                val read = reply.entries().filter { entry -> logins.any { "${it.id}" == entry.text("id") } }
                read shouldHaveSize 3
                read.forEach {
                    it.get("ip").isNull shouldBe true
                    it.get("details").isNull shouldBe true
                }
            }

            test("a target keeps only the records about it, and pages by the cursor without loss or repeat") {
                val (browser, _) = admin("journal-pager")
                val (_, user) = member("journal-paged")
                repeat(3) { browser.put("$USERS/$user/role", roleBody(if (it % 2 == 0) UserRole.TENANT_ADMIN else UserRole.USER)) }
                val expected = stored(AuditTarget.user(user)).map { "${it.id}" }

                val read = browser.all(query("limit" to 2, "targetType" to "USER", "targetId" to user))

                read.map { it.text("id") } shouldBe expected
                read.forEach {
                    it.text("targetType") shouldBe "USER"
                    it.text("targetId") shouldBe "$user"
                }
            }

            test("the last page has no next one") {
                val (browser, _) = admin("journal-last-page")
                val (_, user) = member("journal-short")
                val count = stored(AuditTarget.user(user)).size

                val reply = browser.get("${about(AuditTarget.user(user))}&limit=$count")

                reply.entries() shouldHaveSize count
                reply.json().get("next").isNull shouldBe true
            }

            test("a change of a Service Provider reads with its target and details") {
                val (browser, admin) = admin("journal-sp-importer")
                val entityId = "https://journal-${UUID.randomUUID()}.example/saml"
                val reply = browser.multipart(SPS, metadataForm("Journal", TestSp(entityId).metadata().toByteArray()))
                reply.status shouldBe 201
                val sp = UUID.fromString(reply.json().text("id"))

                browser.get(about(AuditTarget.serviceProvider(sp))).entries().single().run {
                    text("action") shouldBe "sp.registered"
                    text("actorId") shouldBe "$admin"
                    text("targetType") shouldBe "SERVICE_PROVIDER"
                    text("targetId") shouldBe "$sp"
                    details() shouldBe mapOf("entity_id" to entityId)
                }
            }

            test("a record without a target names none") {
                val (browser, _) = admin("journal-policy")
                val etag = browser.get("/api/admin/policy").header("ETag").shouldNotBeNull()
                browser.put("/api/admin/policy", policyBody(idleSeconds = 1200), "If-Match", etag).status shouldBe 204

                browser.get(query("limit" to 1)).entries().single().run {
                    text("action") shouldBe "policy.changed"
                    get("targetType").isNull shouldBe true
                    get("targetId").isNull shouldBe true
                }
                environment.resetPolicies()
            }
        }

        context("refusing a malformed query") {
            test("a limit out of range, past the integer type or not a whole number answers 400") {
                val (browser, _) = admin("journal-limits")

                listOf("0", "-1", "101", "1.5", "many", "4294967297").forEach { browser.get("$AUDIT?limit=$it").status shouldBe 400 }
                browser.get(query("limit" to 1)).entries() shouldHaveSize 1
                browser.get(query("limit" to 100)).ids() shouldBe stored().take(100).map { it.id }
                stored().size shouldBeGreaterThan DEFAULT_PAGE
                browser.get(AUDIT).ids() shouldBe stored().take(DEFAULT_PAGE).map { it.id }
            }

            test("a half of the cursor or of the target answers 400") {
                val (browser, _) = admin("journal-halves")
                val newest = stored().first()

                browser.get(query("before" to newest.occurredAt)).status shouldBe 400
                browser.get(query("beforeId" to newest.id)).status shouldBe 400
                browser.get(query("targetType" to "USER")).status shouldBe 400
                browser.get(query("targetId" to UUID.randomUUID())).status shouldBe 400
                browser.get(query("before" to newest.occurredAt, "beforeId" to newest.id)).ids() shouldContain stored()[1].id
            }

            test("a malformed time, ID or target type answers 400") {
                val (browser, _) = admin("journal-malformed")
                val id = UUID.randomUUID()

                browser.get(query("before" to "yesterday", "beforeId" to id)).status shouldBe 400
                browser.get(query("before" to "2026-11-04T00:00:00Z", "beforeId" to "nonsense")).status shouldBe 400
                browser.get(query("targetType" to "user", "targetId" to id)).status shouldBe 400
                browser.get(query("targetType" to "SESSION", "targetId" to id)).status shouldBe 400
                browser.get(query("targetType" to "USER", "targetId" to "nonsense")).status shouldBe 400
            }
        }
    })
