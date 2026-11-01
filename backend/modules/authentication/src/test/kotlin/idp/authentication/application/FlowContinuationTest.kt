package idp.authentication.application

import idp.authentication.domain.Assurance
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private const val RETURN_URL = "/saml/resume/7f3c"

class FlowContinuationTest :
    FunSpec({
        test("asks for nothing beyond the policy by default") {
            val requirements = FlowContinuation(RETURN_URL).requirements()

            requirements.assurance shouldBe Assurance.SINGLE_FACTOR
            requirements.freshLogin shouldBe false
        }

        test("passes on a demand for MFA") {
            FlowContinuation(RETURN_URL, requiresMultiFactor = true).requirements().assurance shouldBe Assurance.MULTI_FACTOR
        }

        test("passes on a demand for a passkey") {
            FlowContinuation(RETURN_URL, requiresPasskey = true).requirements().assurance shouldBe Assurance.PASSKEY
        }

        test("a passkey is the stronger demand") {
            val continuation = FlowContinuation(RETURN_URL, requiresMultiFactor = true, requiresPasskey = true)

            continuation.requirements().assurance shouldBe Assurance.PASSKEY
        }

        test("passes on a demand for a fresh login") {
            FlowContinuation(RETURN_URL, requiresFreshLogin = true).requirements().freshLogin shouldBe true
        }
    })
