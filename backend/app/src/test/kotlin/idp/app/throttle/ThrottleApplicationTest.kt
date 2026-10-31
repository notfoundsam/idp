package idp.app.throttle

import idp.app.AppApplication
import idp.sharedkernel.audit.Actor
import idp.sharedkernel.audit.AuditLog
import idp.sharedkernel.audit.AuditRecord
import idp.sharedkernel.bus.BusPipeline
import idp.sharedkernel.bus.Message
import idp.sharedkernel.bus.MessageHandler
import idp.sharedkernel.bus.Query
import idp.sharedkernel.bus.QueryBus
import idp.sharedkernel.bus.TransactionScope
import idp.sharedkernel.bus.decorators.LoggerDecorator
import idp.sharedkernel.bus.decorators.SecurityDecorator
import idp.sharedkernel.bus.decorators.ThrottleDecorator
import idp.sharedkernel.bus.decorators.TransactionDecorator
import idp.sharedkernel.migration.TenantMigrationRunner
import idp.sharedkernel.migration.TenantSchemaFactory
import idp.sharedkernel.security.Permission
import idp.sharedkernel.security.Principal
import idp.sharedkernel.security.Role
import idp.sharedkernel.security.SecurityConfig
import idp.sharedkernel.security.SecurityContext
import idp.sharedkernel.tenant.PlatformTransaction
import idp.sharedkernel.tenant.TenantTransaction
import idp.sharedkernel.testing.TestDatabase
import idp.sharedkernel.testing.TestValkey
import idp.sharedkernel.throttle.RateLimiter
import idp.sharedkernel.web.ClientIp
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.SpringApplication
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.NestedExceptionUtils
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID
import javax.sql.DataSource
import kotlin.reflect.KClass

private const val HOST = "acme.idp.test"
private const val LIMIT = 2
private const val VIEWER_HEADER = "CloudFront-Viewer-Address"
private const val DEAD_VALKEY = "redis://127.0.0.1:1"
private val READ = Permission("read")

private class Ping : Query<String> {
    override val transaction = TransactionScope.NONE
}

private class AdminPing : Query<String> {
    override val transaction = TransactionScope.NONE
}

private class Answer<M : Query<String>>(
    override val messageType: KClass<M>,
) : MessageHandler<M, String> {
    override fun handle(message: M) = "pong"
}

private object Silent : AuditLog {
    override fun recordChange(record: AuditRecord) = Unit

    override fun recordDenial(record: AuditRecord) = Unit
}

private object ProbeSecurity : SecurityConfig {
    override val permissions = mapOf<KClass<out Message<*>>, Permission?>(Ping::class to null, AdminPing::class to READ)
    override val roles = emptyMap<Role, Set<Permission>>()
}

/** The assembly `app` gets once `AuditLog` is a bean there. */
@Configuration(proxyBeanMethods = false)
private class ProbeBus {
    @Bean
    fun queryBus(
        throttle: ObjectProvider<ThrottleDecorator>,
        dataSource: DataSource,
    ) = QueryBus(
        BusPipeline(
            handlers = listOf(Answer(Ping::class), Answer(AdminPing::class)),
            security = SecurityDecorator(listOf(ProbeSecurity), Silent),
            logger = LoggerDecorator(),
            transaction = TransactionDecorator(TenantTransaction(dataSource), PlatformTransaction(dataSource)),
            throttle = throttle.ifAvailable,
        ),
    )
}

@RestController
private class ProbeEndpoints(
    private val bus: QueryBus,
) {
    @GetMapping("/probe/ping")
    fun ping(): String = bus.ask(Ping())

    @GetMapping("/probe/admin")
    fun admin(): String = bus.ask(AdminPing())

    @GetMapping("/probe/member")
    fun member(): String = SecurityContext.with(Principal(Actor.User(UUID.randomUUID()), Role("member"))) { bus.ask(AdminPing()) }

    @GetMapping("/probe/ip")
    fun ip(): String? = ClientIp.currentOrNull()?.address
}

class ThrottleApplicationTest :
    FunSpec({
        val database = TestDatabase.create()
        val owner = database.owner()
        val tenant: UUID = UUID.randomUUID()
        TenantMigrationRunner(owner).migrateAll()
        JdbcTemplate(owner).run {
            update("INSERT INTO platform.tenants (id, name, status) VALUES (?, 'acme', 'ACTIVE')", tenant)
            update("INSERT INTO platform.tenant_domains (domain, tenant_id) VALUES (?, ?)", HOST, tenant)
        }
        TenantSchemaFactory(owner).create(tenant)
        database.role("app_runtime")

        fun start(
            vararg overrides: String,
            valkey: String = TestValkey.shared.url,
        ): ConfigurableApplicationContext =
            SpringApplication(AppApplication::class.java, ProbeBus::class.java, ProbeEndpoints::class.java).run(
                "--server.port=0",
                "--management.server.port=0",
                "--spring.datasource.url=${database.url}",
                "--spring.datasource.username=app_runtime",
                "--spring.datasource.password=app_runtime",
                "--VALKEY_URL=$valkey",
                "--THROTTLE_IP_HMAC_KEY=${UUID.randomUUID()}",
                "--idp.throttle.default.capacity=$LIMIT",
                "--idp.throttle.valkey.timeout=5s",
                "--idp.throttle.valkey.connect-timeout=10s",
                *overrides,
            )

        fun ConfigurableApplicationContext.get(
            path: String,
            vararg headers: String,
            port: String? = environment.getProperty("local.server.port"),
        ): HttpResponse<String> {
            val request = HttpRequest.newBuilder(URI("http://localhost:$port$path")).header("Host", HOST)
            headers.toList().chunked(2).forEach { (name, value) -> request.header(name, value) }
            return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString())
        }

        fun ConfigurableApplicationContext.management(path: String) = get(path, port = environment.getProperty("local.management.port"))

        test("over the limit the answer is 429 with Retry-After") {
            start().use { application ->
                repeat(LIMIT) { application.get("/probe/ping").statusCode() shouldBe 200 }

                val refused = application.get("/probe/ping")

                refused.statusCode() shouldBe 429
                refused
                    .headers()
                    .firstValue("Retry-After")
                    .get()
                    .toLong() shouldBeGreaterThan 0
            }
        }

        test("a denied message answers 401 without a principal and 403 with one") {
            start().use { application ->
                application.get("/probe/admin").statusCode() shouldBe 401
                application.get("/probe/member").statusCode() shouldBe 403
            }
        }

        test("without Valkey the application starts, stays healthy and answers") {
            start(valkey = DEAD_VALKEY).use { application ->
                val health = application.management("/actuator/health")
                health.statusCode() shouldBe 200
                health.body() shouldContain "\"status\":\"UP\""
                application.management("/actuator/health/readiness").body() shouldContain "\"status\":\"UP\""

                repeat(LIMIT + 1) { application.get("/probe/ping").statusCode() shouldBe 200 }

                application.getBean(MeterRegistry::class.java).counter("idp.throttle.failures").count() shouldBe 1.0
            }
        }

        test("THROTTLE_ENABLED=false removes the decorator and the limiter") {
            start("--THROTTLE_ENABLED=false", valkey = DEAD_VALKEY).use { application ->
                application.getBeansOfType(ThrottleDecorator::class.java).shouldBeEmpty()
                application.getBeansOfType(RateLimiter::class.java).shouldBeEmpty()

                repeat(LIMIT + 1) { application.get("/probe/ping").statusCode() shouldBe 200 }
            }
        }

        test("the prod profile refuses THROTTLE_ENABLED=false") {
            val failure = shouldThrow<Exception> { start("--spring.profiles.active=prod", "--THROTTLE_ENABLED=false") }

            NestedExceptionUtils.getMostSpecificCause(failure).message.shouldNotBeNull() shouldContain "THROTTLE_ENABLED=false"
        }

        test("the prod profile starts with limits on") {
            start("--spring.profiles.active=prod").use { application ->
                application.getBean(ThrottleDecorator::class.java).shouldNotBeNull()
            }
        }

        test("a forged X-Forwarded-For does not change the client address") {
            start().use { application ->
                val peer = application.get("/probe/ip").body()

                application.get("/probe/ip", "X-Forwarded-For", "198.51.100.9").body() shouldBe peer
                application.get("/probe/ip", VIEWER_HEADER, "198.51.100.9:443").body() shouldBe peer
            }
        }

        test("forging X-Forwarded-For does not escape the limit") {
            start().use { application ->
                repeat(LIMIT) { application.get("/probe/ping", "X-Forwarded-For", "198.51.100.$it").statusCode() shouldBe 200 }

                application.get("/probe/ping", "X-Forwarded-For", "198.51.100.99").statusCode() shouldBe 429
            }
        }

        test("the setting names the header the edge overwrites") {
            start("--CLIENT_IP_HEADER=$VIEWER_HEADER").use { application ->
                val response = application.get("/probe/ip", VIEWER_HEADER, "203.0.113.10:50000", "X-Forwarded-For", "198.51.100.9")

                response.body() shouldBe "203.0.113.10"
            }
        }
    })
