package idp.sharedkernel.bus.decorators

import idp.sharedkernel.bus.Message

interface BusDecorator {
    fun <R> around(
        message: Message<R>,
        next: () -> R,
    ): R
}
