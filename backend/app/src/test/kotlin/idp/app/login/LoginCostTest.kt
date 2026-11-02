package idp.app.login

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.longs.shouldBeGreaterThan
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.jdbc.datasource.DelegatingDataSource
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

private const val WARM_UP = 5
private const val SAMPLES = 9
private const val CLOSE = "close"
private const val UNKNOWN_EMAIL = "nobody@acme.test"
private val SHORTEST_HASH: Duration = Duration.ofMillis(5)

private class Moment(
    val nanos: Long,
    val held: Int,
)

private object Connections {
    private val held = AtomicInteger()
    val moments = CopyOnWriteArrayList<Moment>()

    fun taken() {
        moments += Moment(System.nanoTime(), held.incrementAndGet())
    }

    fun returned() {
        moments += Moment(System.nanoTime(), held.decrementAndGet())
    }

    /** Between the first connection and the last one, so the time of parsing a request does not count. */
    fun longestFreeSpell(): Duration =
        moments
            .zipWithNext()
            .filter { (from, _) -> from.held == 0 }
            .maxOfOrNull { (from, to) -> Duration.ofNanos(to.nanos - from.nanos) } ?: Duration.ZERO
}

private class TracedDataSource(
    target: DataSource,
) : DelegatingDataSource(target) {
    override fun getConnection(): Connection {
        val connection = super.getConnection()
        Connections.taken()
        val handler =
            InvocationHandler { _, method, arguments ->
                if (method.name == CLOSE) Connections.returned()
                try {
                    method.invoke(connection, *arguments.orEmpty())
                } catch (failure: InvocationTargetException) {
                    throw failure.targetException
                }
            }
        return Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java), handler) as Connection
    }
}

private class TracedDataSources : BeanPostProcessor {
    override fun postProcessAfterInitialization(
        bean: Any,
        beanName: String,
    ): Any = if (bean is DataSource) TracedDataSource(bean) else bean
}

class LoginCostTest :
    FunSpec({
        val environment = LoginEnvironment()
        val application = environment.start(sources = arrayOf(TracedDataSources::class.java))

        afterSpec { application.close() }

        /** Clears the delay first: a user inside one skips the check of the password, which would hide a missing hash on either side. */
        fun attempt(login: String): Duration {
            environment.sql.update(
                "UPDATE ${environment.acme.table("password_credentials")} SET failed_attempts = 0, next_attempt_at = NULL",
            )
            val browser = Browser(application)
            val flow = browser.identified(login)
            val started = System.nanoTime()
            browser.password(flow, WRONG_PASSWORD)
            return Duration.ofNanos(System.nanoTime() - started)
        }

        fun median(login: String): Duration = List(SAMPLES) { attempt(login) }.sorted()[SAMPLES / 2]

        test("the password is checked outside a transaction, so no connection waits for the hash") {
            with(environment) { application.user("hashed@acme.test") }
            val browser = Browser(application)
            val flow = browser.identified("hashed@acme.test")
            Connections.moments.clear()

            browser.password(flow)

            Connections.longestFreeSpell() shouldBeGreaterThan SHORTEST_HASH
        }

        test("an unknown login costs a hash, like a known one") {
            with(environment) { application.user("timed@acme.test") }
            repeat(WARM_UP) {
                attempt("timed@acme.test")
                attempt(UNKNOWN_EMAIL)
            }

            val known = median("timed@acme.test")
            val unknown = median(UNKNOWN_EMAIL)

            unknown.toNanos() * 2 shouldBeGreaterThan known.toNanos()
            known.toNanos() * 2 shouldBeGreaterThan unknown.toNanos()
            unknown shouldBeGreaterThan SHORTEST_HASH
            unknown shouldBeLessThan Duration.ofSeconds(5)
        }
    })
