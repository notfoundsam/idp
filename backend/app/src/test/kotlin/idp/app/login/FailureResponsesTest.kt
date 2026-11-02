package idp.app.login

import idp.authentication.application.SessionCookie
import idp.sharedkernel.bus.MessageHandler
import idp.sharedkernel.bus.Query
import idp.sharedkernel.bus.QueryBus
import idp.sharedkernel.bus.TransactionScope
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.datasource.DelegatingDataSource
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import java.sql.Connection
import java.sql.SQLException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

private const val DETAIL = "the password of db.internal is hunter2"

private object Outage {
    val active = AtomicBoolean()
}

private class FailingDataSource(
    target: DataSource,
) : DelegatingDataSource(target) {
    override fun getConnection(): Connection {
        if (Outage.active.get()) throw SQLException(DETAIL)
        return super.getConnection()
    }
}

private class FailingDataSources : BeanPostProcessor {
    override fun postProcessAfterInitialization(
        bean: Any,
        beanName: String,
    ): Any = if (bean is DataSource) FailingDataSource(bean) else bean
}

/** No `SecurityConfig` lists it, which is the mistake the bus has to survive. */
private class Unlisted : Query<String> {
    override val transaction = TransactionScope.NONE
}

private object Reached {
    val count = AtomicInteger()
}

private class UnlistedHandler : MessageHandler<Unlisted, String> {
    override val messageType = Unlisted::class

    override fun handle(message: Unlisted): String {
        Reached.count.incrementAndGet()
        return "reached"
    }
}

@Configuration(proxyBeanMethods = false)
private class UnlistedMessages {
    @Bean
    fun unlistedHandler() = UnlistedHandler()
}

@RestController
private class FailingEndpoints(
    private val bus: QueryBus,
) {
    @GetMapping("/api/probe/unexpected")
    fun unexpected(): String = error(DETAIL)

    @GetMapping("/api/probe/unlisted")
    fun unlisted(): String = bus.ask(Unlisted())
}

class FailureResponsesTest :
    FunSpec({
        val environment = LoginEnvironment()
        val application = environment.start(sources = arrayOf(FailingDataSources::class.java))

        afterSpec { application.close() }
        afterTest { Outage.active.set(false) }

        fun Reply.shouldTellNothing() {
            status shouldBe 500
            body shouldNotContain DETAIL
            body shouldNotContain "Exception"
            body shouldNotContain "idp."
        }

        test("an unexpected error answers in general terms") {
            Browser(application).get("/api/probe/unexpected").shouldTellNothing()
        }

        test("a database failure on a step answers in general terms and proves nothing") {
            with(environment) { application.user("outage@acme.test") }
            val browser = Browser(application)
            val flow = browser.identified("outage@acme.test")
            Outage.active.set(true)

            browser.password(flow).shouldTellNothing()

            Outage.active.set(false)
            browser.get("/api/flows/$flow").json().text("step") shouldBe "PASSWORD"
        }

        test("a database failure at the end opens no session") {
            with(environment) { application.user("lastmoment@acme.test") }
            val browser = Browser(application)
            val flow = browser.identified("lastmoment@acme.test")
            browser.password(flow)
            Outage.active.set(true)

            val reply = browser.complete(flow)

            reply.shouldTellNothing()
            reply.cookies().shouldBeEmpty()
            browser.cookies.keys shouldNotContain SessionCookie.NAME
        }

        test("a database failure while reading a session does not pass the request on as somebody") {
            with(environment) { application.user("readfail@acme.test") }
            val browser = Browser(application).loggedIn("readfail@acme.test")
            Outage.active.set(true)

            browser.get("/api/probe/unlisted").shouldTellNothing()
        }

        test("a message nobody listed is refused, and its handler does not run") {
            val before = Reached.count.get()

            Browser(application).get("/api/probe/unlisted").status shouldBe 401

            Reached.count.get() shouldBe before
        }
    })
