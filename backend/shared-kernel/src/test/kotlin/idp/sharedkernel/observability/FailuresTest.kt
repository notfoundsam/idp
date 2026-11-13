package idp.sharedkernel.observability

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.slf4j.LoggerFactory
import java.io.IOException
import java.sql.SQLException
import javax.net.ssl.SSLHandshakeException

private const val DETAIL = "alice@example.test"

private class SelfCaused : RuntimeException(DETAIL) {
    override val cause: Throwable get() = this
}

class FailuresTest :
    FunSpec({
        val logger = LoggerFactory.getLogger(Failures::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        lateinit var registry: SimpleMeterRegistry
        lateinit var failures: Failures

        beforeEach {
            appender.list.clear()
            logger.addAppender(appender)
            registry = SimpleMeterRegistry()
            failures = Failures(registry)
        }

        afterEach { logger.detachAppender(appender) }

        fun count(
            source: String,
            tls: String,
        ) = registry
            .find("idp.failures")
            .tags("source", source, "tls", tls)
            .counter()
            ?.count() ?: 0.0

        test("a failure is counted by its source and logged with its causes, without their texts") {
            failures.record(FailureSource.OUTBOX, IllegalStateException(DETAIL, SQLException(DETAIL)))

            count("outbox", "false") shouldBe 1.0
            val line = appender.list.single()
            line.level shouldBe Level.ERROR
            line.throwableProxy.shouldBeNull()
            line.formattedMessage shouldContain "source=outbox tls=false"
            line.formattedMessage shouldContain "java.lang.IllegalStateException at idp.sharedkernel.observability.FailuresTest"
            line.formattedMessage shouldContain " <- java.sql.SQLException at "
            line.formattedMessage shouldNotContain DETAIL
        }

        test("a TLS failure deep in the causes counts as a TLS failure") {
            failures.record(FailureSource.HTTP, RuntimeException(IOException(SSLHandshakeException(DETAIL))))

            count("http", "true") shouldBe 1.0
            count("http", "false") shouldBe 0.0
            appender.list.single().formattedMessage shouldContain "tls=true"
        }

        test("a failed job is named in the one line") {
            failures.record(FailureSource.JOB, IllegalStateException(DETAIL), "retention")

            count("job", "false") shouldBe 1.0
            appender.list.single().formattedMessage shouldContain "event=failure source=job job=retention tls=false causes="
        }

        test("a cause that points at itself ends the chain") {
            failures.record(FailureSource.JOB, SelfCaused())

            count("job", "false") shouldBe 1.0
            appender.list.single().formattedMessage shouldNotContain " <- "
        }
    })
