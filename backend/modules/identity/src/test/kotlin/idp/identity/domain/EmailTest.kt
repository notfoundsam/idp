package idp.identity.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain

class EmailTest :
    FunSpec({
        test("toString returns a mask") {
            Email.of("ada.lovelace@acme.test").toString() shouldBe "a***@acme.test"
        }

        test("the value keeps the spelling of the user") {
            Email.of(" Ada@Acme.test ").value shouldBe "Ada@Acme.test"
        }

        test("an invalid address is refused") {
            listOf("", "ada", "@acme.test", "ada@", "ada lovelace@acme.test", "a@b@acme.test", "${"a".repeat(250)}@acme.test").forEach {
                shouldThrow<InvalidEmail> { Email.of(it) }
            }
        }

        test("the refusal does not repeat the address") {
            shouldThrow<InvalidEmail> { Email.of("secret lovelace@acme.test") }.message shouldNotContain "lovelace"
        }
    })
