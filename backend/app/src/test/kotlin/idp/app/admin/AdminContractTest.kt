package idp.app.admin

import idp.app.login.Browser
import idp.app.login.LoginEnvironment
import idp.app.login.Reply
import idp.app.login.text
import idp.sharedkernel.security.UserRole
import idp.sharedkernel.testing.OpenApiContract
import idp.sharedkernel.testing.asMap
import idp.sharedkernel.testing.mappedOperations
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
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
private const val ADMIN = " /api/admin/"
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
