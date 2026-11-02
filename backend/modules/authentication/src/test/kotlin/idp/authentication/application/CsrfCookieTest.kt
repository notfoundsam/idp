package idp.authentication.application

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith

private const val SESSION = "session-token"

class CsrfCookieTest :
    FunSpec({
        test("the name carries the __Host- prefix") {
            CsrfCookie.NAME shouldStartWith "__Host-"
        }

        test("the cookie is readable by the page, which echoes it in a header") {
            val attributes = CsrfCookie.issue(SESSION).split("; ")

            attributes.first() shouldBe "${CsrfCookie.NAME}=${CsrfCookie.tokenFor(SESSION)}"
            attributes.drop(1).shouldContainExactlyInAnyOrder("Path=/", "Secure", "SameSite=Strict")
        }

        test("the token comes from the session alone, so it survives a restart and needs no storage") {
            CsrfCookie.tokenFor(SESSION) shouldBe CsrfCookie.tokenFor(SESSION)
        }

        test("each session has its own token, so rotating the session retires the old one") {
            CsrfCookie.tokenFor(SESSION) shouldNotBe CsrfCookie.tokenFor("other-session")
        }

        test("the token does not reveal the session") {
            CsrfCookie.tokenFor(SESSION) shouldNotContain SESSION
        }

        test("only the token of this session matches") {
            CsrfCookie.matches(SESSION, CsrfCookie.tokenFor(SESSION)) shouldBe true
            CsrfCookie.matches(SESSION, CsrfCookie.tokenFor("other-session")) shouldBe false
            CsrfCookie.matches(SESSION, "") shouldBe false
            CsrfCookie.matches(SESSION, null) shouldBe false
        }
    })
