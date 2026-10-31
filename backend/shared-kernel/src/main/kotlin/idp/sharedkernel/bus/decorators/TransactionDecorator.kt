package idp.sharedkernel.bus.decorators

import idp.sharedkernel.bus.Message
import idp.sharedkernel.bus.TransactionScope
import idp.sharedkernel.tenant.PlatformTransaction
import idp.sharedkernel.tenant.TenantTransaction

class TransactionDecorator(
    private val tenant: TenantTransaction,
    private val platform: PlatformTransaction,
) : BusDecorator {
    override fun <R> around(
        message: Message<R>,
        next: () -> R,
    ): R =
        when (message.transaction) {
            TransactionScope.TENANT -> tenant.execute { next() }
            TransactionScope.PLATFORM -> platform.execute { next() }
            TransactionScope.NONE -> next()
        }
}
