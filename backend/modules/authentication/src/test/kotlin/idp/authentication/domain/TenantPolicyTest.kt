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
                TenantPolicy(sessionIdleTimeout = Duration.ofMinutes(50), sessionAbsoluteTimeout = Duration.ofMinutes(40))
            }
        }

        test("an idle timeout may equal the absolute one") {
            val policy = TenantPolicy(sessionIdleTimeout = Duration.ofMinutes(20), sessionAbsoluteTimeout = Duration.ofMinutes(20))

            policy.sessionAbsoluteTimeout shouldBe policy.sessionIdleTimeout
        }

        test("an idle timeout stays between 5 minutes and the 1 hour of AAL2") {
            TenantPolicy(sessionIdleTimeout = Duration.ofMinutes(5)).sessionIdleTimeout shouldBe Duration.ofMinutes(5)
            TenantPolicy(sessionIdleTimeout = Duration.ofHours(1)).sessionIdleTimeout shouldBe Duration.ofHours(1)
            shouldThrow<SessionTimeoutsNotValid> { TenantPolicy(sessionIdleTimeout = Duration.ofMinutes(5).minusSeconds(1)) }
            shouldThrow<SessionTimeoutsNotValid> { TenantPolicy(sessionIdleTimeout = Duration.ofHours(1).plusSeconds(1)) }
        }

        test("an absolute timeout stays within the 24 hours of AAL2") {
            TenantPolicy(sessionAbsoluteTimeout = Duration.ofHours(24)).sessionAbsoluteTimeout shouldBe Duration.ofHours(24)
            shouldThrow<SessionTimeoutsNotValid> { TenantPolicy(sessionAbsoluteTimeout = Duration.ofHours(24).plusSeconds(1)) }
        }

        test("no value relaxes MFA for TENANT_ADMIN, and none sets an expiry of the password") {
            val fields = TenantPolicy::class.java.declaredFields.map { it.name }
            fields.toSet() shouldBe
                setOf("mfaForUsers", "passkeyRequired", "sessionIdleTimeout", "sessionAbsoluteTimeout", "version")
        }
    })
