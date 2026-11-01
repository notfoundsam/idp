package idp.authentication.domain

import idp.sharedkernel.security.UserRole.TENANT_ADMIN
import idp.sharedkernel.security.UserRole.USER
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

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
    })
