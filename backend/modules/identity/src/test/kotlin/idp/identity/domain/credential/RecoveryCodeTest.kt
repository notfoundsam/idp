package idp.identity.domain.credential

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldNotContain
import java.security.MessageDigest
import java.security.SecureRandom

private const val CODE = "ABCD-EFGH-IJKL-MNOP-QRST-UVWX-YZ"

class RecoveryCodeTest :
    FunSpec({
        context("issuing") {
            test("gives ten different codes") {
                val codes = RecoveryCode.generate(SecureRandom())

                codes shouldHaveSize 10
                codes.map { it.value }.toSet() shouldHaveSize 10
            }

            test("each code carries 128 bits from the given CSPRNG") {
                val requested = mutableListOf<Int>()
                val random =
                    object : SecureRandom() {
                        override fun nextBytes(bytes: ByteArray) {
                            requested += bytes.size
                            super.nextBytes(bytes)
                        }
                    }

                RecoveryCode.generate(random)

                requested shouldBe List(10) { 16 }
            }

            test("a code has 26 characters of Base32 in groups a person can retype") {
                RecoveryCode.generate(SecureRandom()).forEach { code ->
                    code.value shouldMatch Regex("([A-Z2-7]{4}-){6}[A-Z2-7]{2}")
                }
            }
        }

        context("the hash") {
            test("is SHA-256 of the code without separators") {
                val expected = MessageDigest.getInstance("SHA-256").digest(CODE.replace("-", "").toByteArray())

                RecoveryCode(CODE).hash() shouldBe expected
            }

            test("does not depend on case, spaces or dashes") {
                listOf(
                    "abcd-efgh-ijkl-mnop-qrst-uvwx-yz",
                    "ABCDEFGHIJKLMNOPQRSTUVWXYZ",
                    " ABCD EFGH IJKL MNOP QRST UVWX YZ ",
                ).forEach { typed -> RecoveryCode(typed).hash() shouldBe RecoveryCode(CODE).hash() }
            }

            test("differs for another code") {
                (RecoveryCode(CODE).hash().contentEquals(RecoveryCode("ABCD-EFGH-IJKL-MNOP-QRST-UVWX-Y2").hash())) shouldBe false
            }
        }

        test("the code stays out of toString") {
            RecoveryCode(CODE).toString() shouldNotContain "ABCD"
        }
    })
