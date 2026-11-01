package idp.identity.adapters.crypto

import com.sun.management.ThreadMXBean
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldNotContain
import java.lang.management.ManagementFactory

private const val PASSWORD = "correct horse battery staple"
private const val MEBIBYTE = 1024L * 1024L

/** Made by the reference `argon2` CLI with the salt `somesaltsomesalt`, so the format is not only this code's own. */
private const val REFERENCE_HASH = "\$argon2id\$v=19\$m=4096,t=2,p=1\$c29tZXNhbHRzb21lc2FsdA\$RYBmfaY2k3Lz3OGQbN0L5PrGQLaVEEnj4b+RLC0pyDk"

private val cheap = PasswordHasher(HashCost(memoryKib = 8, iterations = 1, parallelism = 1))

private fun allocatedBytes(block: () -> Unit): Long {
    val threads = ManagementFactory.getThreadMXBean() as ThreadMXBean
    val before = threads.currentThreadAllocatedBytes
    block()
    return threads.currentThreadAllocatedBytes - before
}

class PasswordHasherTest :
    FunSpec({
        test("a hash is Argon2id at m=19 MiB, t=2, p=1 with its parameters in the string") {
            PasswordHasher().hash(PASSWORD) shouldMatch
                Regex("""\${'$'}argon2id\${'$'}v=19\${'$'}m=19456,t=2,p=1\${'$'}[A-Za-z0-9+/]{22}\${'$'}[A-Za-z0-9+/]{43}""")
        }

        test("the parameters really cost that memory") {
            val hasher = PasswordHasher()

            allocatedBytes { hasher.hash(PASSWORD) } shouldBeGreaterThan 19 * MEBIBYTE
        }

        test("the right password matches") {
            cheap.matches(PASSWORD, cheap.hash(PASSWORD)) shouldBe true
        }

        test("a wrong password does not match") {
            cheap.matches("wrong horse battery staple", cheap.hash(PASSWORD)) shouldBe false
        }

        test("two hashes of one password differ by the salt") {
            cheap.hash(PASSWORD) shouldNotBe cheap.hash(PASSWORD)
        }

        test("the hash does not contain the password") {
            cheap.hash(PASSWORD) shouldNotContain PASSWORD
        }

        test("a hash from the reference implementation matches") {
            cheap.matches(PASSWORD, REFERENCE_HASH) shouldBe true
            cheap.matches("$PASSWORD ", REFERENCE_HASH) shouldBe false
        }

        test("verification reads the parameters from the string") {
            val other = PasswordHasher(HashCost(memoryKib = 16, iterations = 2, parallelism = 1))

            cheap.matches(PASSWORD, other.hash(PASSWORD)) shouldBe true
        }

        context("the password is verified exactly as received") {
            test("spaces around it count") {
                cheap.matches(" $PASSWORD ", cheap.hash(PASSWORD)) shouldBe false
            }

            test("the case counts") {
                cheap.matches(PASSWORD.uppercase(), cheap.hash(PASSWORD)) shouldBe false
            }

            test("unicode is not normalized") {
                cheap.matches("café-terrace", cheap.hash("café-terrace")) shouldBe false
            }

            test("nothing is cut off a long password") {
                cheap.matches("k".repeat(128), cheap.hash("k".repeat(127))) shouldBe false
            }
        }

        context("the cost of a stored hash") {
            val current = HashCost(memoryKib = 16, iterations = 2, parallelism = 1)
            val hasher = PasswordHasher(current)

            test("a hash at the current parameters is current") {
                hasher.isCurrent(hasher.hash(PASSWORD)) shouldBe true
            }

            test("less memory is outdated") {
                hasher.isCurrent(PasswordHasher(current.copy(memoryKib = 8)).hash(PASSWORD)) shouldBe false
            }

            test("fewer iterations are outdated") {
                hasher.isCurrent(PasswordHasher(current.copy(iterations = 1)).hash(PASSWORD)) shouldBe false
            }

            test("other parallelism is outdated") {
                hasher.isCurrent(PasswordHasher(current.copy(parallelism = 2)).hash(PASSWORD)) shouldBe false
            }
        }
    })
