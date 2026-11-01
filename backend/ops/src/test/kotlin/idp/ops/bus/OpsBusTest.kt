package idp.ops.bus

import idp.ops.OpsApplication
import idp.ops.OpsEnvironment
import idp.sharedkernel.audit.Actor
import idp.sharedkernel.bus.Command
import idp.sharedkernel.bus.CommandBus
import idp.sharedkernel.bus.Message
import idp.sharedkernel.bus.MessageHandler
import idp.sharedkernel.bus.TransactionScope
import idp.sharedkernel.security.AccessDeniedException
import idp.sharedkernel.security.Permission
import idp.sharedkernel.security.Principal
import idp.sharedkernel.security.Role
import idp.sharedkernel.security.SecurityConfig
import idp.sharedkernel.security.SecurityContext
import idp.sharedkernel.tenant.TenantContext
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.springframework.boot.SpringApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.atomic.AtomicInteger
import kotlin.reflect.KClass

private val MANAGE = Permission("tenants.manage")
private val SUPPORT = Role("support")
private val ADMIN = Role("admin")

private class ApproveTenant : Command<Unit> {
    override val transaction = TransactionScope.PLATFORM
}

private val handled = AtomicInteger()

@Configuration(proxyBeanMethods = false)
private class ProbeOperator {
    @Bean
    fun securityConfig(): SecurityConfig =
        object : SecurityConfig {
            override val permissions = mapOf<KClass<out Message<*>>, Permission?>(ApproveTenant::class to MANAGE)
            override val roles = mapOf(ADMIN to setOf(MANAGE))
        }

    @Bean
    fun handler(): MessageHandler<ApproveTenant, Unit> =
        object : MessageHandler<ApproveTenant, Unit> {
            override val messageType = ApproveTenant::class

            override fun handle(message: ApproveTenant) {
                handled.incrementAndGet()
            }
        }
}

@RestController
private class ProbeOperatorEndpoint(
    private val bus: CommandBus,
) {
    @GetMapping("/probe")
    fun probe() = bus.dispatch(ApproveTenant())
}

class OpsBusTest :
    FunSpec({
        val environment = OpsEnvironment()
        val sql = environment.sql
        val application =
            SpringApplication(
                OpsApplication::class.java,
                ProbeOperator::class.java,
                ProbeOperatorEndpoint::class.java,
            ).run(*environment.arguments)
        val bus = application.getBean(CommandBus::class.java)

        afterSpec { application.close() }

        beforeTest {
            handled.set(0)
            sql.update("DELETE FROM platform.operator_audit")
        }

        fun column(name: String): String? = sql.queryForObject("SELECT $name FROM platform.operator_audit", String::class.java)

        test("a denial without a tenant context lands in platform.operator_audit") {
            TenantContext.currentOrNull().shouldBeNull()

            shouldThrow<AccessDeniedException> {
                SecurityContext.with(Principal(Actor.Operator("oncall"), SUPPORT)) { bus.dispatch(ApproveTenant()) }
            }

            handled.get() shouldBe 0
            column("actor") shouldBe "oncall"
            column("action") shouldBe "access.denied"
            column("tenant_id").shouldBeNull()
            column("details") shouldContain "ApproveTenant"
        }

        test("a denial without a principal is recorded as anonymous and answers 401") {
            val port = application.environment.getProperty("local.server.port")
            val response =
                HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI("http://localhost:$port/probe")).build(),
                    HttpResponse.BodyHandlers.ofString(),
                )

            response.statusCode() shouldBe 401
            column("actor") shouldBe "anonymous"
        }

        test("a granted message reaches its handler in the platform transaction") {
            SecurityContext.with(Principal(Actor.Operator("oncall"), ADMIN)) { bus.dispatch(ApproveTenant()) }

            handled.get() shouldBe 1
            sql.queryForObject("SELECT count(*) FROM platform.operator_audit", Int::class.java) shouldBe 0
        }
    })
