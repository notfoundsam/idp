package idp.tenancy.domain

import idp.sharedkernel.tenant.TenantStatus
import idp.sharedkernel.tenant.TenantStatus.ACTIVE
import idp.sharedkernel.tenant.TenantStatus.APPROVED
import idp.sharedkernel.tenant.TenantStatus.DELETED
import idp.sharedkernel.tenant.TenantStatus.OFFBOARDING
import idp.sharedkernel.tenant.TenantStatus.PROVISIONING
import idp.sharedkernel.tenant.TenantStatus.REQUESTED
import idp.sharedkernel.tenant.TenantStatus.SUSPENDED
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.time.Instant
import java.util.UUID

private const val KEK = "hcvault://tenant-key"
private const val STEP = "keys"
private val AFTER: Instant = Instant.parse("2026-12-01T00:00:00Z")

private fun tenant(
    status: TenantStatus,
    step: String? = STEP,
    kekUri: String? = KEK,
    offboardingAfter: Instant? = null,
) = Tenant(UUID.randomUUID(), "Acme", status, version = 0, step, kekUri, offboardingAfter)

private class Move(
    val name: String,
    val from: Set<TenantStatus>,
    val to: TenantStatus,
    val run: Tenant.() -> Unit,
)

private val MOVES =
    listOf(
        Move("approve", setOf(REQUESTED), APPROVED) { approve() },
        Move("start provisioning", setOf(APPROVED), PROVISIONING) { startProvisioning() },
        Move("activate", setOf(PROVISIONING), ACTIVE) { activate() },
        Move("suspend", setOf(ACTIVE), SUSPENDED) { suspend() },
        Move("resume", setOf(SUSPENDED), ACTIVE) { resume() },
        Move("start offboarding", setOf(SUSPENDED), OFFBOARDING) { startOffboarding(AFTER) },
        Move("cancel offboarding", setOf(OFFBOARDING), SUSPENDED) { cancelOffboarding() },
        Move("delete", setOf(OFFBOARDING), DELETED) { delete(AFTER) },
    )

class TenantTest :
    FunSpec({
        context("status transitions") {
            MOVES.forEach { move ->
                TenantStatus.entries.forEach { status ->
                    if (status in move.from) {
                        test("${move.name} goes from $status to ${move.to}") {
                            tenant(status).apply(move.run).status shouldBe move.to
                        }
                    } else {
                        test("${move.name} is forbidden from $status") {
                            val tenant = tenant(status)

                            shouldThrow<ForbiddenTenantChange> { tenant.apply(move.run) }

                            tenant.status shouldBe status
                        }
                    }
                }
            }

            test("a new tenant is requested") {
                val tenant = Tenant.requested(UUID.randomUUID(), "Acme")

                tenant.status shouldBe REQUESTED
                tenant.version shouldBe 0
                tenant.provisioningStep.shouldBeNull()
                tenant.kekUri.shouldBeNull()
            }

            test("a blank name is rejected") {
                shouldThrow<InvalidApplication> { Tenant.requested(UUID.randomUUID(), "  ") }
            }
        }

        context("provisioning step") {
            test("a finished step is recorded") {
                val tenant = tenant(PROVISIONING, step = null, kekUri = null)

                tenant.recordStep("schema")

                tenant.provisioningStep shouldBe "schema"
            }

            test("the next step replaces the previous one and keeps the KEK") {
                val tenant = tenant(PROVISIONING, step = null, kekUri = null)

                tenant.recordStep("keys", KEK)
                tenant.recordStep("invitation")

                tenant.provisioningStep shouldBe "invitation"
                tenant.kekUri shouldBe KEK
            }

            test("repeating a step with the same KEK changes nothing") {
                val tenant = tenant(PROVISIONING, step = "keys", kekUri = KEK)

                shouldNotThrowAny { tenant.recordStep("keys", KEK) }

                tenant.kekUri shouldBe KEK
            }

            test("a second KEK is rejected, because the tenant key is wrapped by the first one") {
                val tenant = tenant(PROVISIONING, step = "keys", kekUri = KEK)

                shouldThrow<ForbiddenTenantChange> { tenant.recordStep("keys", "hcvault://other") }

                tenant.kekUri shouldBe KEK
            }

            test("a blank step is rejected") {
                shouldThrow<ForbiddenTenantChange> { tenant(PROVISIONING).recordStep(" ") }
            }

            TenantStatus.entries.filter { it != PROVISIONING }.forEach { status ->
                test("a step cannot be recorded in $status") {
                    val tenant = tenant(status, step = null)

                    shouldThrow<ForbiddenTenantChange> { tenant.recordStep("schema") }

                    tenant.provisioningStep.shouldBeNull()
                }
            }

            test("activation needs a recorded step") {
                val tenant = tenant(PROVISIONING, step = null)

                shouldThrow<ForbiddenTenantChange> { tenant.activate() }

                tenant.status shouldBe PROVISIONING
            }

            test("activation needs a KEK") {
                val tenant = tenant(PROVISIONING, kekUri = null)

                shouldThrow<ForbiddenTenantChange> { tenant.activate() }

                tenant.status shouldBe PROVISIONING
            }
        }

        context("offboarding") {
            test("starting it stores the end of the waiting period") {
                tenant(SUSPENDED).apply { startOffboarding(AFTER) }.offboardingAfter shouldBe AFTER
            }

            test("cancelling it clears the end of the waiting period") {
                tenant(OFFBOARDING, offboardingAfter = AFTER).apply { cancelOffboarding() }.offboardingAfter.shouldBeNull()
            }

            test("deletion waits for the end of the waiting period") {
                val tenant = tenant(OFFBOARDING, offboardingAfter = AFTER)

                shouldThrow<ForbiddenTenantChange> { tenant.delete(AFTER.minusSeconds(1)) }

                tenant.status shouldBe OFFBOARDING
            }

            test("deletion is due at the end of the waiting period") {
                tenant(OFFBOARDING, offboardingAfter = AFTER).apply { delete(AFTER) }.status shouldBe DELETED
            }

            test("the waiting period is checked before anything is destroyed") {
                val offboarding = tenant(OFFBOARDING, offboardingAfter = AFTER)

                shouldNotThrowAny { offboarding.ensureOffboardingDue(AFTER) }
                shouldThrow<ForbiddenTenantChange> { offboarding.ensureOffboardingDue(AFTER.minusSeconds(1)) }
                shouldThrow<ForbiddenTenantChange> { tenant(SUSPENDED).ensureOffboardingDue(AFTER) }
            }

            TenantStatus.entries.forEach { status ->
                test("a deletion applied again after a restore turns $status into DELETED") {
                    val tenant = tenant(status, offboardingAfter = AFTER)

                    tenant.deleteAgain()

                    tenant.status shouldBe DELETED
                    tenant.offboardingAfter.shouldBeNull()
                }
            }
        }

        context("KEK move") {
            val next = "aws-kms://next"

            listOf(ACTIVE, SUSPENDED).forEach { status ->
                test("a tenant in $status takes a new KEK and keeps the current one as previous") {
                    val tenant = tenant(status).apply { replaceKek(next) }

                    tenant.kekUri shouldBe next
                    tenant.previousKekUri shouldBe KEK
                    tenant.previousKekDestroyAfter.shouldBeNull()
                }
            }

            TenantStatus.entries.filter { it != ACTIVE && it != SUSPENDED }.forEach { status ->
                test("a tenant in $status keeps its KEK") {
                    val tenant = tenant(status)

                    shouldThrow<ForbiddenTenantChange> { tenant.replaceKek(next) }

                    tenant.kekUri shouldBe KEK
                }
            }

            test("a tenant without a KEK has nothing to move") {
                shouldThrow<ForbiddenTenantChange> { tenant(ACTIVE, kekUri = null).replaceKek(next) }
            }

            test("the same KEK is no move") {
                shouldThrow<ForbiddenTenantChange> { tenant(ACTIVE).replaceKek(KEK) }
            }

            test("a second move waits until the previous KEK is gone") {
                val tenant = tenant(ACTIVE).apply { replaceKek(next) }

                shouldThrow<ForbiddenTenantChange> { tenant.replaceKek("aws-kms://third") }

                tenant.kekUri shouldBe next
                tenant.previousKekUri shouldBe KEK
            }

            test("a repeated rewrap keeps the first destruction time") {
                val tenant = tenant(ACTIVE).apply { replaceKek(next) }

                tenant.recordRewrap(AFTER)
                tenant.recordRewrap(AFTER.plusSeconds(3600))

                tenant.previousKekDestroyAfter shouldBe AFTER
            }

            test("a rewrap needs a move") {
                shouldThrow<ForbiddenTenantChange> { tenant(ACTIVE).recordRewrap(AFTER) }
            }

            test("the previous KEK is forgotten only after its destruction time") {
                val tenant = tenant(ACTIVE).apply { replaceKek(next) }
                shouldThrow<ForbiddenTenantChange> { tenant.forgetPreviousKek(AFTER) }
                tenant.recordRewrap(AFTER)

                shouldThrow<ForbiddenTenantChange> { tenant.forgetPreviousKek(AFTER.minusSeconds(1)) }
                tenant.previousKekUri shouldBe KEK

                tenant.forgetPreviousKek(AFTER)
                tenant.previousKekUri.shouldBeNull()
                tenant.previousKekDestroyAfter.shouldBeNull()
                tenant.kekUri shouldBe next
            }
        }

        context("subdomain") {
            listOf("acme", "a", "a1", "acme-corp", "1acme", "a".repeat(63)).forEach { value ->
                test("'$value' is valid") {
                    Subdomain.of(value).value shouldBe value
                }
            }

            listOf(
                "",
                "-acme",
                "acme-",
                "Acme",
                "ac me",
                "acme.evil",
                "acme_corp",
                "ácme",
                "acme\n",
                "xn--80ak6aa92e",
                "a".repeat(64),
            ).forEach { value ->
                test("'${value.replace("\n", "\\n")}' is invalid") {
                    shouldThrow<InvalidSubdomain> { Subdomain.of(value) }
                }
            }

            listOf("ops", "ops-sso", "platform", "api", "www").forEach { value ->
                test("'$value' is reserved") {
                    shouldThrow<InvalidSubdomain> { Subdomain.of(value) }
                }
            }

            test("only the service tenant takes a reserved name") {
                Subdomain.serviceTenant().value shouldBe "ops-sso"
            }

            test("the host is the subdomain under the base domain") {
                Subdomain.of("acme").host("idp.test") shouldBe "acme.idp.test"
            }
        }
    })
