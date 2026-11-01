package idp.identity.adapters.wordlist

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.ints.shouldBeGreaterThan

class BannedPasswordsTest :
    FunSpec({
        val banned = BannedPasswords.load()

        test("the list covers well over the 3000 most used passwords") {
            banned.size shouldBeGreaterThan 60_000
        }

        test("it holds both the most used and the breached passwords") {
            listOf("password", "12345678", "qwertyuiop", "iloveyou1", "p@ssw0rd", "trustno1").forEach { banned shouldContain it }
        }

        test("every entry can pass the length rule, the others would only take memory") {
            banned.filter { it.length !in 8..128 }.shouldBeEmpty()
        }

        test("every entry is lowercase, because a candidate is compared in lowercase") {
            banned.filter { it != it.lowercase() }.shouldBeEmpty()
        }
    })
