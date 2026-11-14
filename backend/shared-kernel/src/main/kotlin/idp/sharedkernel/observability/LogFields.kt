package idp.sharedkernel.observability

import org.slf4j.MDC

/** The fields every line of a request or an event carries, as keys of the JSON line and as attributes of the OTLP record. */
object LogFields {
    const val TENANT_ID = "tenant_id"
    const val CORRELATION_ID = "correlation_id"

    /** Puts the outer value back, so a nested scope leaves nothing of its own on a pooled thread. */
    fun <T> with(
        key: String,
        value: String,
        block: () -> T,
    ): T {
        val outer = MDC.get(key)
        MDC.put(key, value)
        try {
            return block()
        } finally {
            if (outer == null) MDC.remove(key) else MDC.put(key, outer)
        }
    }
}
