package idp.identity.domain.credential

import idp.identity.domain.credential.PasswordRejection.BANNED
import idp.identity.domain.credential.PasswordRejection.CONTEXT_WORD
import idp.identity.domain.credential.PasswordRejection.TOO_LONG
import idp.identity.domain.credential.PasswordRejection.TOO_SHORT
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain

private val policy = PasswordPolicy(banned = setOf("password1", "qwertyuiop"))

private fun refusal(
    password: String,
    context: Set<String> = emptySet(),
): PasswordRejection = shouldThrow<WeakPassword> { policy.check(password, context) }.reason

private fun accepted(
    password: String,
    context: Set<String> = emptySet(),
) = shouldNotThrowAny { policy.check(password, context) }

class PasswordPolicyTest :
    FunSpec({
        context("length") {
            test("8 characters are the minimum") {
                accepted("kx7vq2mw")
            }

            test("7 characters are too short") {
                refusal("kx7vq2m") shouldBe TOO_SHORT
            }

            test("the length counts characters, not UTF-16 units") {
                refusal("🔒🔒🔒🔒") shouldBe TOO_SHORT
                accepted("🔒🔒🔒🔒🔑🔑🔑🔑")
            }

            test("64 characters are accepted") {
                accepted("kx7vq2mw".repeat(8))
            }

            test("128 characters are the limit") {
                accepted("kx7vq2mw".repeat(16))
                refusal("kx7vq2mw".repeat(16) + "k") shouldBe TOO_LONG
            }

            test("the limit can be checked alone, before any hash") {
                policy.fitsHash("kx7vq2mw".repeat(16)) shouldBe true
                policy.fitsHash("kx7vq2mw".repeat(16) + "k") shouldBe false
            }

            test("15 characters are what the interface recommends") {
                PasswordPolicy.RECOMMENDED_LENGTH shouldBe 15
            }
        }

        test("no composition rules apply") {
            listOf(
                "onlylowercaseletters",
                "ONLYUPPERCASELETTERS",
                "9264810375",
                "spaces are fine here",
                "пароль из кириллицы",
            ).forEach(::accepted)
        }

        context("the banned list") {
            test("a listed password is refused") {
                refusal("password1") shouldBe BANNED
            }

            test("changing the case does not get around it") {
                refusal("PassWord1") shouldBe BANNED
            }
        }

        context("context words") {
            test("a password with a context word is refused") {
                refusal("acme-2026-winter", setOf("acme")) shouldBe CONTEXT_WORD
            }

            test("the case does not hide the word") {
                refusal("ACME-2026-winter", setOf("Acme")) shouldBe CONTEXT_WORD
            }

            test("each word of a name counts") {
                refusal("blue-lovelace-77", setOf("Ada.Lovelace")) shouldBe CONTEXT_WORD
                refusal("the-widgets-win", setOf("Acme Widgets")) shouldBe CONTEXT_WORD
            }

            test("the product name is always a context word") {
                refusal("idp-winter-2026") shouldBe CONTEXT_WORD
            }

            test("a word under 3 characters would refuse random passwords, so it is ignored") {
                accepted("hp-laptop-winter", setOf("hp"))
            }

            test("an unrelated password passes") {
                accepted("copper-lantern-river", setOf("acme", "ada.lovelace"))
            }
        }

        test("the reason never carries the password") {
            shouldThrow<WeakPassword> { policy.check("password1", emptySet()) }.message.orEmpty() shouldNotContain "password1"
        }
    })
