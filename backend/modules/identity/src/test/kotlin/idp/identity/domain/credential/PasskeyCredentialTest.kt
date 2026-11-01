package idp.identity.domain.credential

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.time.Instant
import java.util.UUID

private val NOW: Instant = Instant.parse("2026-11-01T00:00:00Z")

private fun credential(userId: UUID = UUID.randomUUID()) =
    PasskeyCredential.new(userId, byteArrayOf(1), byteArrayOf(2), signCount = 7, backupEligible = true, backedUp = false, now = NOW)

class PasskeyCredentialTest :
    FunSpec({
        test("a new credential keeps what the authenticator reported and was never used") {
            val credential = credential()

            credential.signCount shouldBe 7L
            credential.backupEligible shouldBe true
            credential.backedUp shouldBe false
            credential.createdAt shouldBe NOW
            credential.lastUsedAt.shouldBeNull()
        }

        test("two credentials get different ids") {
            (credential().id == credential().id) shouldBe false
        }

        context("the user handle") {
            test("is 16 bytes and holds no personal data") {
                PasskeyCredential.userHandle(UUID.randomUUID()) shouldHaveSize 16
            }

            test("of the owner matches") {
                val owner = UUID.randomUUID()

                credential(owner).isOwnedBy(PasskeyCredential.userHandle(owner)) shouldBe true
            }

            test("of someone else does not match") {
                credential().isOwnedBy(PasskeyCredential.userHandle(UUID.randomUUID())) shouldBe false
            }

            test("a missing one does not match: a discoverable credential always sends it") {
                credential().isOwnedBy(null) shouldBe false
            }
        }
    })
