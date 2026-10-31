package idp.sharedkernel.tenant

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.io.IOException
import java.util.UUID

class TenantContextTest :
    StringSpec({
        val a = TenantContext(UUID.randomUUID(), kekUri = "hcvault://a")
        val b = TenantContext(UUID.randomUUID(), kekUri = null)

        "no tenant outside a scope" {
            TenantContext.currentOrNull().shouldBeNull()
            shouldThrow<TenantContextRequired> { TenantContext.current() }
        }

        "the tenant is visible inside the scope and gone after it" {
            TenantContext.with(a) { TenantContext.current() } shouldBe a

            TenantContext.currentOrNull().shouldBeNull()
        }

        "an inner scope replaces the tenant until it ends" {
            TenantContext.with(a) {
                TenantContext.with(b) { TenantContext.current() } shouldBe b
                TenantContext.current() shouldBe a
            }
        }

        "an exception leaves no tenant behind" {
            shouldThrow<IOException> { TenantContext.with(a) { throw IOException("stop") } }

            TenantContext.currentOrNull().shouldBeNull()
        }
    })
