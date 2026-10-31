package idp.sharedkernel.security

object SecurityContext {
    private val current: ScopedValue<Principal> = ScopedValue.newInstance()

    fun currentOrNull(): Principal? = if (current.isBound) current.get() else null

    fun <T> with(
        principal: Principal,
        block: () -> T,
    ): T = ScopedValue.where(current, principal).call(ScopedValue.CallableOp<T, Exception> { block() })
}
