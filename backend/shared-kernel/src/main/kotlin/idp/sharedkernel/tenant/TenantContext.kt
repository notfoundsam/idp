package idp.sharedkernel.tenant

import java.util.UUID

class TenantContextRequired : IllegalStateException("No tenant in the current scope")

/** The URI travels here because a tenant role cannot read `platform.tenants`. */
data class TenantContext(
    val tenantId: UUID,
    val kekUri: String?,
) {
    companion object {
        // A scoped value ends with its block, so a tenant cannot leak into the next request on a pooled thread.
        private val current: ScopedValue<TenantContext> = ScopedValue.newInstance()

        fun currentOrNull(): TenantContext? = if (current.isBound) current.get() else null

        fun current(): TenantContext = currentOrNull() ?: throw TenantContextRequired()

        fun <T> with(
            context: TenantContext,
            block: () -> T,
        ): T = ScopedValue.where(current, context).call(ScopedValue.CallableOp<T, Exception> { block() })
    }
}
