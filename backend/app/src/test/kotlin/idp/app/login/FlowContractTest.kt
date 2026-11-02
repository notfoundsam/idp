package idp.app.login

import idp.sharedkernel.security.UserRole
import idp.sharedkernel.testing.OpenApiContract
import idp.sharedkernel.testing.asMap
import idp.sharedkernel.testing.mappedOperations
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import java.io.File
import java.util.UUID

private const val FLOWS = "/api/flows"
private const val FLOW = "/api/flows/{id}"
private const val LOGIN = "/api/flows/{id}/login"
private const val PASSWORD_STEP = "/api/flows/{id}/password"
private const val TOTP = "/api/flows/{id}/totp"
private const val RECOVERY_CODE = "/api/flows/{id}/recovery-code"
private const val PASSKEY_OPTIONS = "/api/flows/{id}/passkey/options"
private const val PASSKEY = "/api/flows/{id}/passkey"
private const val ENROLL_TOTP = "/api/flows/{id}/enrollment/totp"
private const val CONFIRM_TOTP = "/api/flows/{id}/enrollment/totp/confirmation"
private const val ENROLL_PASSKEY_OPTIONS = "/api/flows/{id}/enrollment/passkey/options"
private const val ENROLL_PASSKEY = "/api/flows/{id}/enrollment/passkey"
private const val COMPLETE = "/api/flows/{id}/complete"
private const val GET = "GET"
private const val POST = "POST"

/** The scan of `idp.app` finds the endpoints that tests of this module declare for themselves. */
private const val PROBES = "/api/probe"

class FlowContractTest :
    FunSpec({
        val contract = OpenApiContract(File("../../contracts/openapi/auth.yaml"))
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

        fun path(
            template: String,
            flow: Any,
        ) = template.replace("{id}", flow.toString())

        fun requestFields(template: String) = contract.requestSchema(POST, template)["properties"].asMap().keys

        fun user(
            name: String,
            tenant: TestTenant = environment.globex,
        ) = with(environment) { application.user("$name@acme.test", tenant, role = UserRole.USER) }

        test("the controller and the contract list the same operations") {
            mappedOperations(application).filterNot { PROBES in it }.toSet() shouldBe contract.operations
        }

        test("the request bodies name what the controller reads") {
            requestFields(FLOWS) shouldBe setOf("reauthenticate")
            requestFields(LOGIN) shouldBe setOf("login")
            requestFields(PASSWORD_STEP) shouldBe setOf("password")
            requestFields(TOTP) shouldBe setOf("code")
            requestFields(RECOVERY_CODE) shouldBe setOf("code")
            requestFields(CONFIRM_TOTP) shouldBe setOf("code")
            requestFields(PASSKEY) shouldBe setOf("credentialId", "userHandle", "authenticatorData", "clientDataJson", "signature")
            requestFields(ENROLL_PASSKEY) shouldBe setOf("attestationObject", "clientDataJson")
        }

        test("the whole path of a login with TOTP conforms") {
            user("totp")
            val browser = Browser(application, GLOBEX)

            val flow =
                browser
                    .post(FLOWS)
                    .conforms(POST, FLOWS)
                    .also { it.status shouldBe 201 }
                    .json()
                    .text("id")
            browser.get(path(FLOW, flow)).conforms(GET, FLOW).status shouldBe 200
            browser.post(path(LOGIN, flow), loginBody("totp@acme.test")).conforms(POST, LOGIN).status shouldBe 200
            browser.password(flow).conforms(POST, PASSWORD_STEP).status shouldBe 200
            val secret =
                browser
                    .post(path(ENROLL_TOTP, flow))
                    .conforms(POST, ENROLL_TOTP)
                    .json()
                    .text("secret")
            browser
                .post(path(CONFIRM_TOTP, flow), codeBody(totpCode(secret)))
                .conforms(POST, CONFIRM_TOTP)
                .status shouldBe 200
            browser.complete(flow).conforms(POST, COMPLETE).status shouldBe 200

            val next = Browser(application, GLOBEX)
            val second = next.identified("totp@acme.test")
            next.password(second)
            next.post(path(TOTP, second), codeBody(totpCode(secret, shift = 1))).conforms(POST, TOTP).status shouldBe 200
        }

        test("the whole path of a passkey conforms") {
            user("passkey")
            val device = PasskeyDevice(GLOBEX)
            val browser = Browser(application, GLOBEX)
            val flow = browser.identified("passkey@acme.test")
            browser.password(flow)
            val registration = browser.post(path(ENROLL_PASSKEY_OPTIONS, flow)).conforms(POST, ENROLL_PASSKEY_OPTIONS)
            browser
                .post(path(ENROLL_PASSKEY, flow), device.attestation(registration.json()))
                .conforms(POST, ENROLL_PASSKEY)
                .status shouldBe 200

            val next = Browser(application, GLOBEX)
            val second = next.startFlow()
            val options = next.post(path(PASSKEY_OPTIONS, second)).conforms(POST, PASSKEY_OPTIONS)
            next.post(path(PASSKEY, second), device.assertion(options.json())).conforms(POST, PASSKEY).status shouldBe 200
        }

        test("a recovery code conforms, also when refused") {
            user("recovery")
            val first = Browser(application, GLOBEX)
            val flow = first.identified("recovery@acme.test")
            first.password(flow)
            val secret = first.post(path(ENROLL_TOTP, flow)).json().text("secret")
            val code =
                first
                    .post(path(CONFIRM_TOTP, flow), codeBody(totpCode(secret)))
                    .json()
                    .strings("recoveryCodes")
                    .first()
            first.complete(flow)

            val next = Browser(application, GLOBEX)
            val second = next.identified("recovery@acme.test")
            next.password(second)
            next.post(path(RECOVERY_CODE, second), codeBody("wrong")).conforms(POST, RECOVERY_CODE).status shouldBe 422
            next.post(path(RECOVERY_CODE, second), codeBody(code)).conforms(POST, RECOVERY_CODE).status shouldBe 200
        }

        test("every failure a client can cause is in the contract") {
            val browser = Browser(application)
            val unknown = UUID.randomUUID()
            val flow = browser.startFlow()

            browser.get(path(FLOW, unknown)).conforms(GET, FLOW).status shouldBe 410
            browser.get(path(FLOW, "nonsense")).conforms(GET, FLOW).status shouldBe 400
            browser.post(path(LOGIN, flow), "{}").conforms(POST, LOGIN).status shouldBe 400
            browser.post(path(LOGIN, unknown), loginBody(EMAIL)).conforms(POST, LOGIN).status shouldBe 410
            browser.password(flow).conforms(POST, PASSWORD_STEP).status shouldBe 409
            browser.complete(flow).conforms(POST, COMPLETE).status shouldBe 409
            browser.post(path(ENROLL_TOTP, flow)).conforms(POST, ENROLL_TOTP).status shouldBe 409
            browser.post(path(ENROLL_PASSKEY_OPTIONS, flow)).conforms(POST, ENROLL_PASSKEY_OPTIONS).status shouldBe 409
            browser.post(path(TOTP, flow), codeBody("000000")).conforms(POST, TOTP).status shouldBe 409
            browser.post(path(PASSKEY_OPTIONS, unknown)).conforms(POST, PASSKEY_OPTIONS).status shouldBe 410
            browser.post(path(LOGIN, flow), loginBody("nobody@acme.test")).status shouldBe 200
            browser.password(flow).conforms(POST, PASSWORD_STEP).status shouldBe 422
        }
    })
