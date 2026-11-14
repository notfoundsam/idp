package idp.sharedkernel.observability

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxyUtil
import ch.qos.logback.core.UnsynchronizedAppenderBase
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.logs.LoggerProvider
import io.opentelemetry.api.logs.Severity
import io.opentelemetry.context.Context
import org.slf4j.LoggerFactory

private const val APPENDER = "OTLP"

/** The exporter logs its own failures, and sending those would feed a broken export forever. */
private const val OWN_LOGGERS = "io.opentelemetry."

/** Micrometer puts the span into the MDC for the console, and the record carries it in its context already. */
private val SPAN_FIELDS = setOf("traceId", "spanId")

private val THREAD_NAME: AttributeKey<String> = AttributeKey.stringKey("thread.name")
private val EXCEPTION_TYPE: AttributeKey<String> = AttributeKey.stringKey("exception.type")
private val EXCEPTION_STACKTRACE: AttributeKey<String> = AttributeKey.stringKey("exception.stacktrace")

/** Sends the line as the console writes it, with the fields of the MDC as attributes and the span it was written in. */
internal class OtlpLogAppender(
    private val logs: LoggerProvider,
) : UnsynchronizedAppenderBase<ILoggingEvent>() {
    override fun append(event: ILoggingEvent) {
        if (event.loggerName.startsWith(OWN_LOGGERS)) return
        val record =
            logs
                .get(event.loggerName)
                .logRecordBuilder()
                .setTimestamp(event.instant)
                .setSeverity(severity(event.level))
                .setSeverityText(event.level.levelStr)
                .setBody(event.formattedMessage)
                .setContext(Context.current())
                .setAttribute(THREAD_NAME, event.threadName)
        event.mdcPropertyMap
            .filterKeys { it !in SPAN_FIELDS }
            .forEach { (key, value) -> record.setAttribute(AttributeKey.stringKey(key), value) }
        event.throwableProxy?.let {
            record.setAttribute(EXCEPTION_TYPE, it.className)
            record.setAttribute(EXCEPTION_STACKTRACE, ThrowableProxyUtil.asString(it))
        }
        record.emit()
    }

    private fun severity(level: Level): Severity =
        when (level) {
            Level.ERROR -> Severity.ERROR
            Level.WARN -> Severity.WARN
            Level.INFO -> Severity.INFO
            Level.DEBUG -> Severity.DEBUG
            Level.TRACE -> Severity.TRACE
            else -> Severity.UNDEFINED_SEVERITY_NUMBER
        }
}

/**
 * Adds [OtlpLogAppender] to the root logger for the life of the application context. Logback starts before Spring and
 * OpenTelemetry, so the appender cannot come from its configuration.
 */
class LogExport(
    openTelemetry: OpenTelemetry,
) : AutoCloseable {
    private val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
    private val appender =
        OtlpLogAppender(openTelemetry.logsBridge).also {
            it.name = APPENDER
            it.context = root.loggerContext
            it.start()
        }

    init {
        root.addAppender(appender)
    }

    override fun close() {
        root.detachAppender(appender)
        appender.stop()
    }
}
