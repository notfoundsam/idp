package idp.sharedkernel.crypto

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.funSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.experimental.xor

private val MATERIAL = ByteArray(32) { it.toByte() }

private fun alias() = "test-${UUID.randomUUID()}"

fun keyManagementServiceContract(
    kms: () -> KeyManagementService,
    unknownKey: () -> String,
    unreachable: () -> KeyManagementService,
) = funSpec {
    test("wrapped material unwraps to the same bytes") {
        val key = kms().createKey(alias())

        kms().unwrap(key, kms().wrap(key, MATERIAL)) shouldBe MATERIAL
    }

    test("wrapped material differs from the plaintext and between calls") {
        val key = kms().createKey(alias())

        val wrapped = kms().wrap(key, MATERIAL)

        wrapped shouldNotBe MATERIAL
        wrapped shouldNotBe kms().wrap(key, MATERIAL)
    }

    test("creating a key with the same alias returns the same key") {
        val alias = alias()
        val key = kms().createKey(alias)
        val wrapped = kms().wrap(key, MATERIAL)

        val again = kms().createKey(alias)

        again shouldBe key
        kms().unwrap(again, wrapped) shouldBe MATERIAL
    }

    test("concurrent creation with one alias gives one key") {
        val alias = alias()

        val keys =
            Executors.newFixedThreadPool(4).use { pool ->
                List(4) { pool.submit<String> { kms().createKey(alias) } }.map { it.get() }
            }

        keys.toSet() shouldHaveSize 1
        kms().unwrap(keys.first(), kms().wrap(keys.first(), MATERIAL)) shouldBe MATERIAL
    }

    test("different aliases give different keys") {
        kms().createKey(alias()) shouldNotBe kms().createKey(alias())
    }

    test("an alias that could escape the key namespace is rejected") {
        shouldThrow<IllegalArgumentException> { kms().createKey("../../sys/seal") }
        shouldThrow<IllegalArgumentException> { kms().createKey("") }
    }

    test("a destroyed key neither wraps nor unwraps") {
        val key = kms().createKey(alias())
        val wrapped = kms().wrap(key, MATERIAL)

        kms().destroy(key)

        shouldThrow<KeyNotFoundException> { kms().wrap(key, MATERIAL) }
        shouldThrow<KeyNotFoundException> { kms().unwrap(key, wrapped) }
    }

    test("destroying a key twice is harmless") {
        val key = kms().createKey(alias())

        kms().destroy(key)
        kms().destroy(key)
    }

    test("the alias of a destroyed key gives a new key") {
        val alias = alias()
        val key = kms().createKey(alias)
        val wrapped = kms().wrap(key, MATERIAL)
        kms().destroy(key)

        val replacement = kms().createKey(alias)

        kms().unwrap(replacement, kms().wrap(replacement, MATERIAL)) shouldBe MATERIAL
        shouldThrow<KeyManagementException> { kms().unwrap(replacement, wrapped) }
    }

    test("an unknown key is reported") {
        shouldThrow<KeyNotFoundException> { kms().wrap(unknownKey(), MATERIAL) }
        shouldThrow<KeyNotFoundException> { kms().unwrap(unknownKey(), MATERIAL) }
    }

    test("destroying an unknown key is harmless") {
        kms().destroy(unknownKey())
    }

    test("material wrapped by another key is rejected") {
        val wrapped = kms().wrap(kms().createKey(alias()), MATERIAL)

        shouldThrow<WrongKeyException> { kms().unwrap(kms().createKey(alias()), wrapped) }
    }

    test("altered material is rejected") {
        val key = kms().createKey(alias())
        val wrapped = kms().wrap(key, MATERIAL)
        wrapped[wrapped.lastIndex] = wrapped.last() xor 1

        shouldThrow<WrongKeyException> { kms().unwrap(key, wrapped) }
    }

    test("an unreachable KMS is reported on every operation") {
        shouldThrow<KeyManagementUnavailableException> { unreachable().createKey(alias()) }
        shouldThrow<KeyManagementUnavailableException> { unreachable().wrap(unknownKey(), MATERIAL) }
        shouldThrow<KeyManagementUnavailableException> { unreachable().unwrap(unknownKey(), MATERIAL) }
        shouldThrow<KeyManagementUnavailableException> { unreachable().destroy(unknownKey()) }
    }
}
