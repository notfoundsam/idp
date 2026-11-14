package idp.sharedkernel.security

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class UserRoleTest :
    FunSpec({
        test("every user role comes back from the role of its principal") {
            UserRole.entries.forEach { UserRole.of(it.role) shouldBe it }
        }

        test("a role no user holds is none of them") {
            UserRole.of(Role("Operator")).shouldBeNull()
            UserRole.of(Role("tenant_admin")).shouldBeNull()
        }
    })
