package idp.app.admin

import idp.app.login.Browser
import idp.app.login.LoginEnvironment
import idp.app.login.MultipartForm
import idp.app.login.Reply
import idp.app.login.text
import idp.federation.TestSp
import idp.federation.application.FederationService
import idp.sharedkernel.audit.Actor
import idp.sharedkernel.security.UserRole
import idp.sharedkernel.testing.OpenApiContract
import idp.sharedkernel.testing.asMap
import idp.sharedkernel.testing.mappedOperations
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.io.File
import java.util.UUID

private const val USERS = "/api/admin/users"
private const val INVITATION = "/api/admin/users/{id}/invitation"
private const val ROLE = "/api/admin/users/{id}/role"
private const val BLOCK = "/api/admin/users/{id}/block"
private const val UNBLOCK = "/api/admin/users/{id}/unblock"
private const val MFA_RESET = "/api/admin/users/{id}/mfa-reset"
private const val USER = "/api/admin/users/{id}"
private const val USER_SESSIONS = "/api/admin/users/{id}/sessions"
private const val ALL_SESSIONS = "/api/admin/sessions"
private const val SEARCH = "/api/admin/users/search"
private const val POLICY = "/api/admin/policy"
private const val SPS = "/api/admin/service-providers"
private const val SP = "/api/admin/service-providers/{id}"
private const val SP_SETTINGS = "/api/admin/service-providers/{id}/settings"
private const val SP_USERS = "/api/admin/service-providers/{id}/users"
private const val SP_REMOVAL = "/api/admin/service-providers/{id}/users/removal"
private const val KEYS = "/api/admin/signing-keys"
private const val ROTATION = "/api/admin/signing-keys/rotation"
private const val COMPLETION = "/api/admin/signing-keys/rotation/completion"
private const val AUDIT = "/api/admin/audit"
private const val ADMIN = " /api/admin/"
private const val ETAG = "ETag"
private const val IF_MATCH = "If-Match"
private const val GET = "GET"
private const val POST = "POST"
private const val PUT = "PUT"
private const val DELETE = "DELETE"

private fun String.of(id: Any) = replace("{id}", "$id")

class AdminContractTest :
    FunSpec({
        val contract = OpenApiContract(File("../../contracts/openapi/admin.yaml"))
        val environment = LoginEnvironment()
        val application = environment.start()

        afterSpec { application.close() }

        /** A refusal may come without the body its status can carry: only the one for an aged login has it. */
        fun Reply.conforms(
            method: String,
            template: String,
        ): Reply {
            contract.statuses(method, template) shouldContain status
            if (status < 300 || body.isNotEmpty()) {
                contract.responseSchema(method, template, status)?.let { contract.shouldMatch(json(), it) }
            }
            return this
        }

        fun requestFields(
            method: String,
            template: String,
        ) = contract.requestSchema(method, template)["properties"].asMap().keys

        fun admin(name: String) = environment.signedIn(application, "$name@acme.test")

        fun member(name: String) = environment.signedIn(application, "$name@acme.test", role = UserRole.USER)

        test("the controller and the contract list the same operations") {
            mappedOperations(application).filter { ADMIN in it }.toSet() shouldBe contract.operations
        }

        test("the request bodies name what the controller reads") {
            requestFields(POST, USERS) shouldBe setOf("email", "role")
            requestFields(PUT, ROLE) shouldBe setOf("role")
            requestFields(POST, SEARCH) shouldBe setOf("email")
            requestFields(PUT, POLICY) shouldBe setOf("mfaForUsers", "passkeyRequired", "sessionIdleSeconds", "sessionAbsoluteSeconds")
            requestFields(PUT, SP_SETTINGS) shouldBe setOf("nameIdFormat", "signedPart", "signatureAlgorithm", "attributes")
            requestFields(POST, SP_USERS) shouldBe setOf("userIds")
            requestFields(POST, SP_REMOVAL) shouldBe setOf("userIds")
        }

        test("the query of the journal names what the controller reads") {
            contract.queryParameters(GET, AUDIT) shouldBe setOf("limit", "before", "beforeId", "targetType", "targetId")
        }

        test("the journal conforms with every record inside, also when refused") {
            val (browser, _) = admin("contract-journal-reader")
            val (_, erased) = member("contract-journal-erased")
            val (ordinary, _) = member("contract-journal-onlooker")
            browser.delete(USER.of(erased)).status shouldBe 204
            val entry = contract.schema("AuditEntry")

            val page = browser.get("$AUDIT?limit=5").conforms(GET, AUDIT)
            page.status shouldBe 200
            val entries = page.json().get("entries").values()
            entries.forEach { contract.shouldMatch(it, entry) }
            contract.shouldMatch(page.json().get("next"), contract.schema("AuditCursor"))
            val about = browser.get("$AUDIT?targetType=USER&targetId=$erased&limit=100").conforms(GET, AUDIT)
            val records = about.json().get("entries").values()
            records.forEach { contract.shouldMatch(it, entry) }
            records.map { it.get("ip").isNull to it.get("details").isNull } shouldContain (true to true)
            records.map { it.text("actorType") } shouldContain "SYSTEM"
            about.json().get("next").isNull shouldBe true
            browser.get("$AUDIT?limit=0").conforms(GET, AUDIT).status shouldBe 400
            browser.get("$AUDIT?targetType=USER").conforms(GET, AUDIT).status shouldBe 400
            ordinary.get(AUDIT).conforms(GET, AUDIT).status shouldBe 403
            Browser(application).get(AUDIT).conforms(GET, AUDIT).status shouldBe 401
        }

        test("the Service Providers conform, also when refused") {
            val (browser, admin) = admin("contract-sp-admin")
            val (ordinary, user) = member("contract-sp-member")
            val content = TestSp("https://contract-sp-${UUID.randomUUID()}.example/saml").metadata().toByteArray()

            val created = browser.multipart(SPS, metadataForm("Contract", content)).conforms(POST, SPS)
            created.status shouldBe 201
            val id = created.json().text("id")
            browser.multipart(SPS, metadataForm("Again", content)).conforms(POST, SPS).status shouldBe 409
            browser.multipart(SPS, metadataForm("Broken", "<x/>".toByteArray())).conforms(POST, SPS).status shouldBe 422
            browser.multipart(SPS, MultipartForm().field("name", "Fileless")).conforms(POST, SPS).status shouldBe 400
            browser.post(SPS, "{}").conforms(POST, SPS).status shouldBe 415
            browser.get(SPS).conforms(GET, SPS).status shouldBe 200
            val read = browser.get(SP.of(id)).conforms(GET, SP)
            read.status shouldBe 200
            val etag = read.header(ETAG).shouldNotBeNull()
            val body = spSettingsBody(attributes = listOf("EMAIL"))
            val saved = browser.put(SP_SETTINGS.of(id), body, IF_MATCH, etag).conforms(PUT, SP_SETTINGS)
            saved.status shouldBe 204
            contract.responseHeaders(PUT, SP_SETTINGS, 204).forEach { saved.header(it).shouldNotBeNull() }
            browser.put(SP_SETTINGS.of(id), body, IF_MATCH, etag).conforms(PUT, SP_SETTINGS).status shouldBe 412
            browser.put(SP_SETTINGS.of(id), body).conforms(PUT, SP_SETTINGS).status shouldBe 428
            browser.put(SP_SETTINGS.of(id), "{}", IF_MATCH, etag).conforms(PUT, SP_SETTINGS).status shouldBe 400
            browser.post(SP_USERS.of(id), usersBody(user)).conforms(POST, SP_USERS).status shouldBe 204
            browser.post(SP_USERS.of(id), usersBody(UUID.randomUUID())).conforms(POST, SP_USERS).status shouldBe 404
            browser.post(SP_USERS.of(id), usersBody()).conforms(POST, SP_USERS).status shouldBe 400
            val assigned = browser.get(SP_USERS.of(id)).conforms(GET, SP_USERS)
            assigned.status shouldBe 200
            assigned.json().get("users").size() shouldBe 1
            browser.post(SP_REMOVAL.of(id), usersBody(user)).conforms(POST, SP_REMOVAL).status shouldBe 204
            browser.post(SP_REMOVAL.of(UUID.randomUUID()), usersBody(user)).conforms(POST, SP_REMOVAL).status shouldBe 404
            ordinary.get(SPS).conforms(GET, SPS).status shouldBe 403
            ordinary.get(SP.of(id)).conforms(GET, SP).status shouldBe 403
            ordinary.delete(SP.of(id)).conforms(DELETE, SP).status shouldBe 403
            Browser(application).get(SP_USERS.of(id)).conforms(GET, SP_USERS).status shouldBe 401
            browser.get(SP.of(UUID.randomUUID())).conforms(GET, SP).status shouldBe 404
            environment.age(admin)
            browser.delete(SP.of(id)).conforms(DELETE, SP).status shouldBe 403
        }

        test("removing a Service Provider conforms") {
            val (browser, _) = admin("contract-sp-remover")
            val content = TestSp("https://contract-removed-${UUID.randomUUID()}.example/saml").metadata().toByteArray()
            val id = browser.multipart(SPS, metadataForm("Removed", content)).json().text("id")

            browser.delete(SP.of(id)).conforms(DELETE, SP).status shouldBe 204
            browser.delete(SP.of(id)).conforms(DELETE, SP).status shouldBe 404
            browser.delete(SP.of("nonsense")).conforms(DELETE, SP).status shouldBe 400
        }

        test("the signing keys conform, also when refused") {
            val (browser, admin) = admin("contract-key-rotator")
            val (ordinary, _) = member("contract-key-onlooker")
            environment.acme.acting { application.getBean(FederationService::class.java).createIdentity(environment.acme.host) }

            val keys = browser.get(KEYS).conforms(GET, KEYS)
            keys.status shouldBe 200
            keys.json().get("keys").size() shouldBe 1
            browser.post(COMPLETION).conforms(POST, COMPLETION).status shouldBe 409
            browser.post(ROTATION).conforms(POST, ROTATION).status shouldBe 201
            browser.post(ROTATION).conforms(POST, ROTATION).status shouldBe 409
            val rotating = browser.get(KEYS).conforms(GET, KEYS)
            rotating.json().get("keys").size() shouldBe 2
            ordinary.get(KEYS).conforms(GET, KEYS).status shouldBe 403
            ordinary.post(ROTATION).conforms(POST, ROTATION).status shouldBe 403
            ordinary.post(COMPLETION).conforms(POST, COMPLETION).status shouldBe 403
            Browser(application).post(ROTATION).conforms(POST, ROTATION).status shouldBe 401
            browser.post(COMPLETION).conforms(POST, COMPLETION).status shouldBe 204
            environment.age(admin)
            browser.post(ROTATION).conforms(POST, ROTATION).status shouldBe 201
            browser.post(COMPLETION).conforms(POST, COMPLETION).status shouldBe 403
        }

        test("searching conforms, also when refused") {
            val (browser, _) = admin("contract-searcher")
            val (ordinary, target) = member("contract-sought")

            val found = browser.post(SEARCH, searchBody("contract-sought@acme.test")).conforms(POST, SEARCH)
            found.status shouldBe 200
            found.json().get("users").values().single().run {
                propertyNames().toSet() shouldBe setOf("id", "role", "status")
                text("id") shouldBe "$target"
            }
            val none = browser.post(SEARCH, searchBody("nobody@acme.test")).conforms(POST, SEARCH)
            none.json().get("users").size() shouldBe 0
            browser.post(SEARCH, "{}").conforms(POST, SEARCH).status shouldBe 400
            browser.post(SEARCH, searchBody("x"), echoCsrf = false).conforms(POST, SEARCH).status shouldBe 403
            ordinary.post(SEARCH, searchBody("x")).conforms(POST, SEARCH).status shouldBe 403
            Browser(application).post(SEARCH, searchBody("x")).conforms(POST, SEARCH).status shouldBe 401
        }

        test("the policy conforms with its ETag, also when refused") {
            val (browser, admin) = admin("contract-policy")
            val (ordinary, _) = member("contract-policy-onlooker")

            val read = browser.get(POLICY).conforms(GET, POLICY)
            read.status shouldBe 200
            contract.responseHeaders(GET, POLICY, 200).forEach { read.header(it).shouldNotBeNull() }
            val etag = read.header(ETAG).shouldNotBeNull()
            val saved = browser.put(POLICY, policyBody(idleSeconds = 1200), IF_MATCH, etag).conforms(PUT, POLICY)
            saved.status shouldBe 204
            contract.responseHeaders(PUT, POLICY, 204).forEach { saved.header(it).shouldNotBeNull() }
            browser.put(POLICY, policyBody(), IF_MATCH, etag).conforms(PUT, POLICY).status shouldBe 412
            browser.put(POLICY, policyBody()).conforms(PUT, POLICY).status shouldBe 428
            browser.put(POLICY, "{}", IF_MATCH, etag).conforms(PUT, POLICY).status shouldBe 400
            val current = saved.header(ETAG).shouldNotBeNull()
            browser.put(POLICY, policyBody(idleSeconds = 7200), IF_MATCH, current).conforms(PUT, POLICY).status shouldBe 422
            ordinary.get(POLICY).conforms(GET, POLICY).status shouldBe 403
            ordinary.put(POLICY, policyBody(), IF_MATCH, current).conforms(PUT, POLICY).status shouldBe 403
            Browser(application).get(POLICY).conforms(GET, POLICY).status shouldBe 401
            browser.put(POLICY, policyBody(), IF_MATCH, current).conforms(PUT, POLICY).status shouldBe 204
            environment.age(admin)
            browser.put(POLICY, policyBody(idleSeconds = 1200), IF_MATCH, current).conforms(PUT, POLICY).status shouldBe 403
        }

        test("the card conforms, also when refused") {
            val (browser, _) = admin("contract-card-reader")
            val (ordinary, target) = member("contract-carded")
            environment.acme.acting {
                val federation = application.getBean(FederationService::class.java)
                val metadata = TestSp("https://contract-card.example/saml").metadata().toByteArray()
                federation.assign(federation.registerServiceProvider("Wiki", metadata, Actor.System), listOf(target), Actor.System)
            }

            val card = browser.get(USER.of(target)).conforms(GET, USER)
            card.status shouldBe 200
            card.json().get("serviceProviders").size() shouldBe 1
            card.json().get("events").size() shouldNotBe 0
            browser.get(USER.of(UUID.randomUUID())).conforms(GET, USER).status shouldBe 404
            browser.get(USER.of("nonsense")).conforms(GET, USER).status shouldBe 400
            ordinary.get(USER.of(target)).conforms(GET, USER).status shouldBe 403
            Browser(application).get(USER.of(target)).conforms(GET, USER).status shouldBe 401
        }

        test("inviting and resending conform, also when refused") {
            val (browser, _) = admin("contract-inviter")
            val body = inviteBody("contract-invited@acme.test", UserRole.USER)

            val invited = browser.post(USERS, body).conforms(POST, USERS)
            invited.status shouldBe 201
            val id = invited.json().text("id")
            browser.post(USERS, body).conforms(POST, USERS).status shouldBe 422
            browser.post(USERS, "{}").conforms(POST, USERS).status shouldBe 400
            browser.post(USERS, body, echoCsrf = false).conforms(POST, USERS).status shouldBe 403
            Browser(application).post(USERS, body).conforms(POST, USERS).status shouldBe 401
            browser.post(INVITATION.of(id)).conforms(POST, INVITATION).status shouldBe 204
            browser.post(INVITATION.of("nonsense")).conforms(POST, INVITATION).status shouldBe 400
            browser.post(INVITATION.of(UUID.randomUUID())).conforms(POST, INVITATION).status shouldBe 404
            val (_, active) = member("contract-active")
            browser.post(INVITATION.of(active)).conforms(POST, INVITATION).status shouldBe 422
        }

        test("the role, the block and the sessions conform, also when refused") {
            val (browser, _) = admin("contract-blocker")
            val (_, target) = member("contract-blocked")
            val (ordinary, _) = member("contract-ordinary")

            browser.put(ROLE.of(target), roleBody(UserRole.TENANT_ADMIN)).conforms(PUT, ROLE).status shouldBe 204
            browser.put(ROLE.of(target), "{}").conforms(PUT, ROLE).status shouldBe 400
            browser.put(ROLE.of(UUID.randomUUID()), roleBody(UserRole.USER)).conforms(PUT, ROLE).status shouldBe 404
            ordinary.put(ROLE.of(target), roleBody(UserRole.USER)).conforms(PUT, ROLE).status shouldBe 403
            browser.post(BLOCK.of(target)).conforms(POST, BLOCK).status shouldBe 204
            browser.post(BLOCK.of(target)).conforms(POST, BLOCK).status shouldBe 422
            browser.post(BLOCK.of(UUID.randomUUID())).conforms(POST, BLOCK).status shouldBe 404
            ordinary.post(BLOCK.of(target)).conforms(POST, BLOCK).status shouldBe 403
            browser.post(UNBLOCK.of(target)).conforms(POST, UNBLOCK).status shouldBe 204
            browser.post(UNBLOCK.of(target)).conforms(POST, UNBLOCK).status shouldBe 422
            browser.post(UNBLOCK.of(UUID.randomUUID())).conforms(POST, UNBLOCK).status shouldBe 404
            browser.delete(USER_SESSIONS.of(target)).conforms(DELETE, USER_SESSIONS).status shouldBe 204
            browser.delete(USER_SESSIONS.of(UUID.randomUUID())).conforms(DELETE, USER_SESSIONS).status shouldBe 404
            ordinary.delete(USER_SESSIONS.of(target)).conforms(DELETE, USER_SESSIONS).status shouldBe 403
            ordinary.delete(ALL_SESSIONS).conforms(DELETE, ALL_SESSIONS).status shouldBe 403
            Browser(application).delete(ALL_SESSIONS).conforms(DELETE, ALL_SESSIONS).status shouldBe 401
        }

        test("the reset and the erasure conform, also when refused for an aged login") {
            val (browser, admin) = admin("contract-eraser")
            val (_, target) = member("contract-erased")
            val (ordinary, _) = member("contract-onlooker")

            browser.post(MFA_RESET.of(target)).conforms(POST, MFA_RESET).status shouldBe 204
            browser.post(MFA_RESET.of(UUID.randomUUID())).conforms(POST, MFA_RESET).status shouldBe 404
            ordinary.post(MFA_RESET.of(target)).conforms(POST, MFA_RESET).status shouldBe 403
            browser.delete(USER.of(target)).conforms(DELETE, USER).status shouldBe 204
            browser.delete(USER.of(target)).conforms(DELETE, USER).status shouldBe 404
            ordinary.delete(USER.of(target)).conforms(DELETE, USER).status shouldBe 403
            environment.age(admin)
            browser.post(MFA_RESET.of(target)).conforms(POST, MFA_RESET).status shouldBe 403
            browser.delete(USER.of(target)).conforms(DELETE, USER).status shouldBe 403
        }

        test("ending every session of the tenant conforms") {
            val (browser, _) = admin("contract-panicker")

            browser.delete(ALL_SESSIONS).conforms(DELETE, ALL_SESSIONS).status shouldBe 204
            browser.delete(ALL_SESSIONS).conforms(DELETE, ALL_SESSIONS).status shouldBe 401
        }
    })
