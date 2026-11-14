package idp.audience.account

import idp.audience.account.application.command.ChangePassword
import idp.audience.account.application.command.ConfirmTotpSetup
import idp.audience.account.application.command.FinishPasskeySetup
import idp.sharedkernel.throttle.ThrottleDimension
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.longs.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import java.time.Duration

private const val GUESSES_A_MINUTE = 10L

class AccountThrottleConfigTest :
    FunSpec({
        test("each way to guess a credential from a session is limited") {
            AccountThrottleConfig.rules.keys shouldBe setOf(ChangePassword::class, ConfirmTotpSetup::class, FinishPasskeySetup::class)
        }

        test("the limit follows the user, at most as fast as guessing at login") {
            AccountThrottleConfig.rules.values.forEach { rules ->
                rules.map { it.dimension } shouldContainExactly listOf(ThrottleDimension.USER)
                rules.single().limit.period shouldBe Duration.ofMinutes(1)
                rules.single().limit.capacity shouldBeLessThanOrEqual GUESSES_A_MINUTE
            }
        }
    })
