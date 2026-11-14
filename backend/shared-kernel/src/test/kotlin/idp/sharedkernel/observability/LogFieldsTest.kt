package idp.sharedkernel.observability

import idp.sharedkernel.tenant.TenantContext
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.slf4j.MDC
import java.util.UUID

class LogFieldsTest :
    FunSpec({
        test("a tenant scope names its tenant in the lines and leaves nothing behind") {
            val tenant = UUID.randomUUID()

            TenantContext.with(TenantContext(tenant, null)) { MDC.get(LogFields.TENANT_ID) } shouldBe tenant.toString()

            MDC.get(LogFields.TENANT_ID).shouldBeNull()
        }

        test("a nested scope gives the outer value back, also when it fails") {
            LogFields.with(LogFields.CORRELATION_ID, "outer") {
                shouldThrow<IllegalStateException> { LogFields.with(LogFields.CORRELATION_ID, "inner") { error("failed") } }
                MDC.get(LogFields.CORRELATION_ID) shouldBe "outer"
            }

            MDC.get(LogFields.CORRELATION_ID).shouldBeNull()
        }
    })
