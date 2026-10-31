package idp.sharedkernel.bus

import idp.sharedkernel.bus.decorators.LoggerDecorator
import idp.sharedkernel.bus.decorators.SecurityDecorator
import idp.sharedkernel.bus.decorators.ThrottleDecorator
import idp.sharedkernel.bus.decorators.TransactionDecorator
import kotlin.reflect.KClass

/** One typed slot per decorator keeps the order out of the wiring. Only `app` throttles, so that slot is optional. */
class BusPipeline(
    handlers: List<MessageHandler<*, *>>,
    security: SecurityDecorator,
    logger: LoggerDecorator,
    transaction: TransactionDecorator,
    throttle: ThrottleDecorator? = null,
) {
    private val decorators = listOfNotNull(throttle, security, logger, transaction)
    private val handlers: Map<KClass<*>, MessageHandler<*, *>> = handlers.associateBy { it.messageType }

    init {
        val repeated = handlers.groupBy { it.messageType }.filterValues { it.size > 1 }.keys
        require(repeated.isEmpty()) { "A message has exactly one handler: $repeated" }
        val unhandled = security.messages - this.handlers.keys
        require(unhandled.isEmpty()) { "A message has exactly one handler: $unhandled" }
    }

    internal fun <R> dispatch(message: Message<R>): R {
        val handle: () -> R = { handlerOf(message).handle(message) }
        return decorators.foldRight(handle) { decorator, next -> { decorator.around(message, next) } }()
    }

    @Suppress("UNCHECKED_CAST")
    private fun <R> handlerOf(message: Message<R>) = handlers.getValue(message::class) as MessageHandler<Message<R>, R>
}
