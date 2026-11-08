package idp.sharedkernel.observability

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Metrics
import org.slf4j.LoggerFactory
import javax.net.ssl.SSLException

private const val FAILURES = "idp.failures"
private const val OWN_CODE = "idp."
private const val MAX_CAUSES = 10

private val log = LoggerFactory.getLogger(Failures::class.java)

/** Where a failure surfaced, a tag of [Failures]. */
enum class FailureSource(
    val tag: String,
) {
    HTTP("http"),
    OUTBOX("outbox"),
    JOB("job"),
}

/**
 * Logs and counts a failure that no code expected. Exception texts can carry personal data, so the line names the classes of the
 * causes and where each was thrown, never a message. A TLS failure anywhere in the causes is a broken connection to a backend.
 */
class Failures(
    private val registry: MeterRegistry = Metrics.globalRegistry,
) {
    fun record(
        source: FailureSource,
        failure: Throwable,
    ) {
        val causes = generateSequence(failure) { it.cause?.takeIf { cause -> cause !== it } }.take(MAX_CAUSES).toList()
        val tls = causes.any { it is SSLException }
        registry.counter(FAILURES, "source", source.tag, "tls", tls.toString()).increment()
        log.error("event=failure source={} tls={} causes={}", source.tag, tls, causes.joinToString(" <- ", transform = ::describe))
    }

    private fun describe(failure: Throwable): String {
        val frames = failure.stackTrace
        val thrown = frames.firstOrNull()
        val own = frames.firstOrNull { it.className.startsWith(OWN_CODE) }
        return listOfNotNull(failure.javaClass.name, thrown?.let { "at $it" }, own?.takeIf { it != thrown }?.let { "in $it" })
            .joinToString(" ")
    }
}
