package idp.audience.login

import idp.audience.login.application.command.StartFlow
import idp.audience.login.application.command.SubmitPassword
import idp.audience.login.application.command.SubmitTotp
import idp.audience.login.application.command.recovery.AcceptInvitation
import idp.audience.login.application.command.recovery.ConfirmEmailChange
import idp.audience.login.application.command.recovery.ConfirmPasswordReset
import idp.audience.login.application.command.recovery.RequestEmailChange
import idp.audience.login.application.command.recovery.RequestPasswordReset
import idp.sharedkernel.security.Role
import idp.sharedkernel.security.UserRole
import idp.sharedkernel.throttle.ThrottleDimension
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.shouldBe
import java.time.Duration

private const val ADDRESSES_A_TENANT_OUTLASTS = 100L
private const val MAILS_AN_HOUR = 5L

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

        test("each way to guess a token of a mail is limited like a credential") {
            val credential = LoginThrottleConfig.rules.getValue(SubmitPassword::class)

            LoginThrottleConfig.rules.getValue(AcceptInvitation::class) shouldBe credential
            LoginThrottleConfig.rules.getValue(ConfirmPasswordReset::class) shouldBe credential
            LoginThrottleConfig.rules.getValue(ConfirmEmailChange::class) shouldBe credential
        }

        test("what sends a mail is far slower than guessing: a person needs one or two") {
            listOf(RequestPasswordReset::class, RequestEmailChange::class).forEach { message ->
                val (address, _) = LoginThrottleConfig.rules.getValue(message)

                address.limit.period shouldBe Duration.ofHours(1)
                address.limit.capacity shouldBe MAILS_AN_HOUR
            }
        }

        test("what is limited has an entry in the security config, so nothing is limited by accident") {
            LoginSecurityConfig.permissions.keys.containsAll(LoginThrottleConfig.rules.keys) shouldBe true
        }

        test("only asking to change the email is not public, and every role of a user may do it") {
            LoginSecurityConfig.permissions.filterValues { it != null }.keys shouldBe setOf(RequestEmailChange::class)
            val permission = LoginSecurityConfig.permissions.getValue(RequestEmailChange::class)
            UserRole.entries.forEach { role ->
                LoginSecurityConfig.roles.getValue(Role(role.name)) shouldBe setOf(permission)
            }
        }
    })
