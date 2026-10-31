package idp.sharedkernel.bus.decorators

import idp.sharedkernel.bus.Message
import idp.sharedkernel.outbox.EventCause
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.UUID

private const val OK = "OK"

private val log = LoggerFactory.getLogger(LoggerDecorator::class.java)

/** Messages and exception texts carry personal data, so a line holds only their types. */
class LoggerDecorator(
    private val nanoTime: () -> Long = System::nanoTime,
) : BusDecorator {
    override fun <R> around(
        message: Message<R>,
        next: () -> R,
    ): R {
        val started = nanoTime()
        val cause = EventCause.currentOrNull() ?: EventCause(UUID.randomUUID(), null)
        try {
            val result = EventCause.with(cause, next)
            write(message, OK, started, cause.correlationId)
            return result
        } catch (failure: Exception) {
            write(message, failure.javaClass.simpleName, started, cause.correlationId)
            throw failure
        }
    }

    private fun write(
        message: Message<*>,
        outcome: String,
        started: Long,
        correlationId: UUID,
    ) {
        val duration = Duration.ofNanos(nanoTime() - started).toMillis()
        log.info("message={} outcome={} durationMs={} correlationId={}", message.javaClass.name, outcome, duration, correlationId)
    }
}
