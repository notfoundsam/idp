package idp.authentication.application

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith

class FlowCookieTest :
    FunSpec({
        test("the name carries the __Host- prefix") {
            FlowCookie.NAME shouldStartWith "__Host-"
        }

        test("the cookie stays on this host, away from scripts and from requests of other sites") {
            val attributes = FlowCookie.issue("secret").split("; ")

            attributes.first() shouldBe "${FlowCookie.NAME}=secret"
            attributes.drop(1).shouldContainExactlyInAnyOrder("Path=/", "Secure", "HttpOnly", "SameSite=Strict")
        }

        test("a browser secret does not print") {
            FlowAccess(java.util.UUID.randomUUID(), "secret").toString().contains("secret") shouldBe false
        }
    })
