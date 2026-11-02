package idp.audience.login

import idp.audience.login.application.command.StartFlow
import idp.audience.login.application.command.SubmitPassword
import idp.audience.login.application.command.SubmitTotp
import idp.sharedkernel.throttle.ThrottleDimension
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.shouldBe

private const val ADDRESSES_A_TENANT_OUTLASTS = 100L

class LoginThrottleConfigTest :
    FunSpec({
        test("every message with a limit checks the address first, so a blocked one spends nothing of its tenant") {
            LoginThrottleConfig.rules.values.forEach { rules ->
                rules.map { it.dimension }.shouldContainExactly(ThrottleDimension.IP, ThrottleDimension.TENANT)
            }
        }

        test("a tenant outlasts a botnet: its bucket is far above the limit of an address") {
            LoginThrottleConfig.rules.values.forEach { rules ->
                val (address, tenant) = rules
                tenant.limit.period shouldBe address.limit.period
                tenant.limit.capacity shouldBeGreaterThanOrEqual address.limit.capacity * ADDRESSES_A_TENANT_OUTLASTS
            }
        }

        test("guessing is slower than walking through the steps") {
            val guessing =
                LoginThrottleConfig.rules
                    .getValue(SubmitPassword::class)
                    .first()
                    .limit.capacity
            val walking =
                LoginThrottleConfig.rules
                    .getValue(StartFlow::class)
                    .first()
                    .limit.capacity

            (guessing < walking) shouldBe true
        }

        test("each way to guess a credential is limited") {
            LoginThrottleConfig.rules shouldContainKey SubmitPassword::class
            LoginThrottleConfig.rules shouldContainKey SubmitTotp::class
        }

        test("what is limited is public, so an address is all there is to limit by") {
            LoginSecurityConfig.permissions.keys.containsAll(LoginThrottleConfig.rules.keys) shouldBe true
        }
    })
