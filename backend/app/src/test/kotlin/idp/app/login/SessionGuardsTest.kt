package idp.app.login

import idp.authentication.application.CsrfCookie
import idp.authentication.application.SessionCookie
import idp.sharedkernel.audit.Actor
import idp.sharedkernel.bus.Command
import idp.sharedkernel.bus.CommandBus
import idp.sharedkernel.bus.Message
import idp.sharedkernel.bus.MessageHandler
import idp.sharedkernel.bus.TransactionScope
import idp.sharedkernel.security.Permission
import idp.sharedkernel.security.RequiresFreshAuthentication
import idp.sharedkernel.security.Role
import idp.sharedkernel.security.SecurityConfig
import idp.sharedkernel.security.UserRole
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldBeEmpty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID
import kotlin.reflect.KClass

private const val CHANGE_THEME = "/api/probe/theme"
private const val CHANGE_EMAIL = "/api/probe/email"
private val CHANGE = Permission("change")

private class ChangeTheme : Command<String> {
    override val transaction = TransactionScope.NONE
}

private class ChangeEmail :
    Command<String>,
    RequiresFreshAuthentication {
    override val transaction = TransactionScope.NONE
}

private class Done<M : Command<String>>(
    override val messageType: KClass<M>,
) : MessageHandler<M, String> {
    override fun handle(message: M) = "done"
}

private object GuardedSecurity : SecurityConfig {
    override val permissions = mapOf<KClass<out Message<*>>, Permission?>(ChangeTheme::class to CHANGE, ChangeEmail::class to CHANGE)
    override val roles = mapOf(Role(UserRole.USER.name) to setOf(CHANGE))
}

@Configuration(proxyBeanMethods = false)
private class GuardedMessages {
    @Bean
    fun themeHandler() = Done(ChangeTheme::class)

    @Bean
    fun emailHandler() = Done(ChangeEmail::class)

    @Bean
    fun guardedSecurity(): SecurityConfig = GuardedSecurity
}

@RestController
private class GuardedEndpoints(
    private val bus: CommandBus,
) {
    @PostMapping(CHANGE_THEME)
    fun theme(): String = bus.dispatch(ChangeTheme())

    @PostMapping(CHANGE_EMAIL)
    fun email(): String = bus.dispatch(ChangeEmail())

    @GetMapping(CHANGE_THEME)
    fun read(): String = "read"
}

class SessionGuardsTest :
    FunSpec({
        val environment = LoginEnvironment()
        val application = environment.start()
        val sql = environment.sql
        val sessions = environment.acme.table("sso_sessions")

        afterSpec { application.close() }

        fun browser() = Browser(application)

        fun signedIn(name: String): Pair<Browser, UUID> {
            val email = "$name@acme.test"
            val user = with(environment) { application.user(email) }
            return browser().loggedIn(email) to user
        }

        fun denials(user: UUID) =
            with(environment) { application.audit() }.filter { it.action == "access.csrf_rejected" && it.actor == Actor.User(user) }

        context("CSRF") {
            test("a request that echoes the token passes") {
                val (browser, _) = signedIn("echoes")

                browser.post(CHANGE_THEME).status shouldBe 200
            }

            test("a request without the token is refused and audited, before any handler runs") {
                val (browser, user) = signedIn("forged")

                val refused = browser.post(CHANGE_THEME, echoCsrf = false)

                refused.status shouldBe 403
                refused.body.shouldBeEmpty()
                denials(user) shouldHaveSize 1
            }

            test("a made-up token is refused") {
                val (browser, _) = signedIn("madeup")

                browser.post(CHANGE_THEME, echoCsrf = false, headers = arrayOf(CsrfCookie.HEADER, "made-up")).status shouldBe 403
            }

            test("the token of another session is refused") {
                val (browser, _) = signedIn("mine")
                val (other, _) = signedIn("theirs")
                val token = other.cookies.getValue(CsrfCookie.NAME)

                browser.post(CHANGE_THEME, echoCsrf = false, headers = arrayOf(CsrfCookie.HEADER, token)).status shouldBe 403
            }

            test("a new login retires the old token") {
                val (browser, _) = signedIn("rotates")
                val old = browser.cookies.getValue(CsrfCookie.NAME)
                val flow = browser.startFlow("""{"reauthenticate":true}""")
                browser.post("/api/flows/$flow/login", loginBody("rotates@acme.test"))
                browser.password(flow)
                browser.complete(flow).status shouldBe 200

                browser.post(CHANGE_THEME, echoCsrf = false, headers = arrayOf(CsrfCookie.HEADER, old)).status shouldBe 403
                browser.post(CHANGE_THEME).status shouldBe 200
            }

            test("reading needs no token") {
                val (browser, _) = signedIn("reads")

                browser.get(CHANGE_THEME).status shouldBe 200
            }

            test("a step of a flow in a live session needs the token too") {
                val (browser, _) = signedIn("stepsup")

                browser.post("/api/flows", echoCsrf = false).status shouldBe 403
            }

            test("a browser without a session logs in without one") {
                val browser = browser()

                browser.post("/api/flows", echoCsrf = false).status shouldBe 201
            }

            test("a dead session does not stand in the way of a login") {
                val browser = browser()
                browser.cookies[SessionCookie.NAME] = "dead"

                browser.post("/api/flows", echoCsrf = false).status shouldBe 201
            }

            test("the anonymous answer of a guarded message stays with the bus") {
                browser().post(CHANGE_THEME, echoCsrf = false).status shouldBe 401
            }
        }

        context("a dangerous action") {
            test("passes right after a login") {
                val (browser, _) = signedIn("fresh")

                browser.post(CHANGE_EMAIL).status shouldBe 200
            }

            test("asks to log in again once the login has aged, and says so") {
                val (browser, user) = signedIn("aged")
                sql.update("UPDATE $sessions SET authenticated_at = now() - interval '11 minutes' WHERE user_id = ?", user)

                val refused = browser.post(CHANGE_EMAIL)

                refused.status shouldBe 403
                refused.json().text("error") shouldBe "reauthentication_required"
                with(environment) { application.audit() }
                    .filter { it.action == "access.reauthentication_required" && it.actor == Actor.User(user) } shouldHaveSize 1
            }

            test("passes again after a fresh login, while an ordinary action never asked") {
                val (browser, user) = signedIn("relogin")
                sql.update("UPDATE $sessions SET authenticated_at = now() - interval '11 minutes' WHERE user_id = ?", user)
                browser.post(CHANGE_THEME).status shouldBe 200
                browser.post(CHANGE_EMAIL).status shouldBe 403

                val flow = browser.startFlow("""{"reauthenticate":true}""")
                browser.post("/api/flows/$flow/login", loginBody("relogin@acme.test"))
                browser.password(flow)
                browser.complete(flow).status shouldBe 200

                browser.post(CHANGE_EMAIL).status shouldBe 200
            }

            test("a flow that continues the session does not refresh it") {
                val (browser, user) = signedIn("continues")
                sql.update("UPDATE $sessions SET authenticated_at = now() - interval '11 minutes' WHERE user_id = ?", user)

                browser.complete(browser.startFlow()).status shouldBe 200

                browser.post(CHANGE_EMAIL).status shouldBe 403
            }
        }
    })
