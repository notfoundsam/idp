package idp.federation.domain

import idp.federation.rsaKeyPair
import idp.federation.selfSigned
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import java.security.KeyPairGenerator
import java.security.cert.X509Certificate
import java.time.Instant
import java.util.UUID

private val STRONG: X509Certificate by lazy { x509(selfSigned(rsaKeyPair(MIN_RSA_BITS))) }

private fun key(status: SigningKeyStatus) = SigningKey(UUID.randomUUID(), status, STRONG, byteArrayOf(1, 2, 3), Instant.now())

class SigningKeyTest :
    FunSpec({
        context("the key") {
            test("of RSA-2048 is refused") {
                val weak = x509(selfSigned(rsaKeyPair(2048)))

                shouldThrow<WeakSigningKey> { SigningKey(UUID.randomUUID(), SigningKeyStatus.ACTIVE, weak, byteArrayOf(), Instant.now()) }
            }

            test("of RSA-3072 is accepted") {
                key(SigningKeyStatus.ACTIVE).status shouldBe SigningKeyStatus.ACTIVE
            }

            test("that is not RSA is refused") {
                val ec = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
                val certificate = x509(selfSigned(ec, "SHA256withECDSA"))

                shouldThrow<WeakSigningKey> {
                    SigningKey(
                        UUID.randomUUID(),
                        SigningKeyStatus.ACTIVE,
                        certificate,
                        byteArrayOf(),
                        Instant.now(),
                    )
                }
            }

            test("prints no ciphertext") {
                key(SigningKeyStatus.ACTIVE).toString() shouldNotContain "[B@"
            }
        }

        context("a rotation") {
            test("publishes the active key first and the next one second") {
                val active = key(SigningKeyStatus.ACTIVE)
                val next = key(SigningKeyStatus.NEXT)

                SigningKeyRing(listOf(next, active)).published.shouldContainExactly(active, next)
            }

            test("cannot start while another is in progress") {
                shouldThrow<KeyRotationInProgress> {
                    SigningKeyRing(listOf(key(SigningKeyStatus.ACTIVE), key(SigningKeyStatus.NEXT))).ensureRotationCanStart()
                }
            }

            test("completes by promoting the next key") {
                val next = key(SigningKeyStatus.NEXT)

                SigningKeyRing(listOf(key(SigningKeyStatus.ACTIVE), next)).completeRotation() shouldBe next

                next.status shouldBe SigningKeyStatus.ACTIVE
            }

            test("cannot complete without a next key") {
                shouldThrow<NoKeyRotation> { SigningKeyRing(listOf(key(SigningKeyStatus.ACTIVE))).completeRotation() }
            }

            test("an active key cannot be promoted") {
                shouldThrow<IllegalStateException> { key(SigningKeyStatus.ACTIVE).promote() }
            }

            test("a ring without keys has nothing to publish") {
                SigningKeyRing(emptyList()).active.shouldBeNull()
                SigningKeyRing(emptyList()).published shouldBe emptyList()
            }
        }
    })
