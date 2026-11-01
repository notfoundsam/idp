package idp.authentication.application

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith

class SessionCookieTest :
    FunSpec({
        test("the name carries the __Host- prefix") {
            SessionCookie.NAME shouldStartWith "__Host-"
        }

        test("the cookie stays on this host and away from scripts") {
            val attributes = SessionCookie.issue("token").split("; ")

            attributes.first() shouldBe "${SessionCookie.NAME}=token"
            attributes.drop(1).shouldContainExactlyInAnyOrder("Path=/", "Secure", "HttpOnly", "SameSite=Lax")
        }

        test("clearing keeps the attributes, because a browser matches a __Host- cookie by them") {
            val attributes = SessionCookie.clear().split("; ")

            attributes.first() shouldBe "${SessionCookie.NAME}="
            attributes.drop(1).shouldContainExactlyInAnyOrder("Path=/", "Secure", "HttpOnly", "SameSite=Lax", "Max-Age=0")
        }
    })
