package idp.sharedkernel.crypto

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

private class RecordingKms(
    private val scheme: String,
) : KeyManagementService {
    val calls = mutableListOf<String>()

    override fun createKey(alias: String): String {
        calls += "createKey $alias"
        return "$scheme://$alias"
    }

    override fun wrap(
        kekUri: String,
        plaintext: ByteArray,
    ): ByteArray {
        calls += "wrap $kekUri"
        return plaintext
    }

    override fun unwrap(
        kekUri: String,
        wrapped: ByteArray,
    ): ByteArray {
        calls += "unwrap $kekUri"
        return wrapped
    }

    override fun destroy(kekUri: String) {
        calls += "destroy $kekUri"
    }
}

private class Router {
    val openbao = RecordingKms("hcvault")
    val aws = RecordingKms("aws-kms")
    val router = KeyManagementRouter(aws, mapOf("hcvault" to openbao, "aws-kms" to aws))
}

class KeyManagementRouterTest :
    FunSpec({
        test("a new key goes to the adapter chosen for new keys") {
            Router().run {
                router.createKey("tenant") shouldBe "aws-kms://tenant"

                aws.calls shouldContainExactly listOf("createKey tenant")
                openbao.calls shouldBe emptyList()
            }
        }

        test("an existing key goes to the adapter of its URI") {
            Router().run {
                router.wrap("hcvault://transit/a", ByteArray(1))
                router.unwrap("hcvault://transit/a", ByteArray(1))
                router.destroy("hcvault://transit/a")
                router.wrap("aws-kms://arn:aws:kms:eu-west-1:1:key/a", ByteArray(1))

                openbao.calls shouldContainExactly
                    listOf("wrap hcvault://transit/a", "unwrap hcvault://transit/a", "destroy hcvault://transit/a")
                aws.calls shouldContainExactly listOf("wrap aws-kms://arn:aws:kms:eu-west-1:1:key/a")
            }
        }

        test("a URI with an unsupported scheme is rejected") {
            Router().run {
                shouldThrow<IllegalArgumentException> { router.wrap("gcp-kms://key", ByteArray(1)) }
                shouldThrow<IllegalArgumentException> { router.unwrap("not a uri", ByteArray(1)) }
                shouldThrow<IllegalArgumentException> { router.destroy("") }
            }
        }
    })
