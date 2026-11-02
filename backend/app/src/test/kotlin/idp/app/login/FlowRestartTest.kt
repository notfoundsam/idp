package idp.app.login

import idp.authentication.application.CsrfCookie
import idp.authentication.application.SessionCookie
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** Nothing about a login lives in the memory of `app`, so a rollout must not cost anyone their step or their session. */
class FlowRestartTest :
    FunSpec({
        test("a session, its CSRF token and an unfinished flow survive a restart") {
            val environment = LoginEnvironment()
            val before = environment.start()
            with(environment) { before.user() }
            val signedIn = Browser(before).loggedIn()
            val halfway = Browser(before)
            val flow = halfway.identified()
            val cookies = signedIn.cookies.toMap()
            before.close()

            val after = environment.start()
            try {
                val sameSession = Browser(after).also { it.cookies += cookies }
                sameSession.cookies.getValue(CsrfCookie.NAME) shouldBe CsrfCookie.tokenFor(cookies.getValue(SessionCookie.NAME))
                val resumed = sameSession.complete(sameSession.startFlow())
                resumed.status shouldBe 200
                resumed.cookies().size shouldBe 0

                val continued = Browser(after).also { it.cookies += halfway.cookies }
                continued.get("/api/flows/$flow").json().text("step") shouldBe "PASSWORD"
                continued.password(flow).status shouldBe 200
                continued.complete(flow).status shouldBe 200
            } finally {
                after.close()
            }
        }
    })
