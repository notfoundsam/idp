package idp.authentication.domain

import idp.sharedkernel.security.UserRole.TENANT_ADMIN
import idp.sharedkernel.security.UserRole.USER
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Duration

class TenantPolicyTest :
    FunSpec({
        test("requires MFA of everyone by default") {
            TenantPolicy().assuranceFor(USER) shouldBe Assurance.MULTI_FACTOR
            TenantPolicy().assuranceFor(TENANT_ADMIN) shouldBe Assurance.MULTI_FACTOR
        }

        test("relaxes MFA only for the USER role") {
            val relaxed = TenantPolicy(mfaForUsers = false)

            relaxed.assuranceFor(USER) shouldBe Assurance.SINGLE_FACTOR
            relaxed.assuranceFor(TENANT_ADMIN) shouldBe Assurance.MULTI_FACTOR
        }

        test("a required passkey wins over a relaxed MFA") {
            val policy = TenantPolicy(mfaForUsers = false, passkeyRequired = true)

            policy.assuranceFor(USER) shouldBe Assurance.PASSKEY
            policy.assuranceFor(TENANT_ADMIN) shouldBe Assurance.PASSKEY
        }

        test("the default timeouts stay within AAL2 of NIST SP 800-63B") {
            TenantPolicy().sessionIdleTimeout shouldBe Duration.ofMinutes(30)
            TenantPolicy().sessionAbsoluteTimeout shouldBe Duration.ofHours(12)
        }

        test("an idle timeout must be positive") {
            shouldThrow<SessionTimeoutsNotValid> { TenantPolicy(sessionIdleTimeout = Duration.ZERO) }
        }

        test("an idle timeout cannot outlast the absolute one") {
            shouldThrow<SessionTimeoutsNotValid> {
                TenantPolicy(sessionIdleTimeout = Duration.ofHours(2), sessionAbsoluteTimeout = Duration.ofHours(1))
            }
        }
    })
