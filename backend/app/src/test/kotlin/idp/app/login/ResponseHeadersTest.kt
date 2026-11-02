package idp.app.login

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import java.util.UUID

private const val APP_VERSION = "1.42.0"

class ResponseHeadersTest :
    FunSpec({
        val environment = LoginEnvironment()
        val application = environment.start("--APP_VERSION=$APP_VERSION", "--MIN_CLIENT_VERSION=1.20.3")

        afterSpec { application.close() }

        fun browser(host: String = ACME) = Browser(application, host)

        fun Reply.shouldCarrySecurityHeaders() {
            header("Strict-Transport-Security") shouldBe "max-age=31536000; includeSubDomains"
            header("X-Content-Type-Options") shouldBe "nosniff"
            header("Referrer-Policy") shouldBe "no-referrer"
            val policy = header("Content-Security-Policy").orEmpty()
            policy shouldContain "default-src 'none'"
            policy shouldContain "object-src 'none'"
            policy shouldContain "base-uri 'none'"
            policy shouldContain "frame-ancestors 'none'"
        }

        context("every response") {
            test("of a flow carries the security headers and stays out of caches") {
                val reply = browser().post("/api/flows")

                reply.status shouldBe 201
                reply.shouldCarrySecurityHeaders()
                reply.header("Cache-Control") shouldBe "no-store"
            }

            test("of a refusal carries them too") {
                val reply = browser().get("/api/flows/${UUID.randomUUID()}")

                reply.status shouldBe 410
                reply.shouldCarrySecurityHeaders()
                reply.header("Cache-Control") shouldBe "no-store"
            }

            test("of a host nobody knows carries them too") {
                val reply = browser("nobody.idp.test").post("/api/flows")

                reply.status shouldBe 404
                reply.shouldCarrySecurityHeaders()
            }

            test("of a path outside the API carries them too") {
                browser().get("/nothing-here").shouldCarrySecurityHeaders()
            }

            test("with a body names its charset") {
                browser().post("/api/flows").header("Content-Type") shouldBe "application/json;charset=UTF-8"
            }

            test("sends no CORS headers, whatever the origin") {
                val reply = browser().get("/api/flows/${UUID.randomUUID()}", "Origin", "https://evil.test")

                reply
                    .headers()
                    .keys
                    .filter { it.startsWith("access-control", ignoreCase = true) }
                    .shouldBeEmpty()
            }

            test("to a preflight allows nothing") {
                val preflight = arrayOf("Origin", "https://evil.test", "Access-Control-Request-Method", "POST")

                val reply = browser().send("OPTIONS", "/api/flows", headers = preflight)

                reply.header("Access-Control-Allow-Origin").shouldBeNull()
            }
        }

        test("TRACE is refused") {
            browser().send("TRACE", "/api/flows").status shouldBe 405
        }

        test("every cookie is __Host-, Secure and states SameSite") {
            with(environment) { application.user() }
            val browser = browser()
            val cookies =
                buildList {
                    val started = browser.post("/api/flows")
                    addAll(started.cookies())
                    val flow = started.json().text("id")
                    browser.post("/api/flows/$flow/login", loginBody(EMAIL))
                    browser.password(flow)
                    addAll(browser.complete(flow).cookies())
                }

            cookies.shouldNotBeEmpty()
            cookies.size shouldBe 3
            cookies.forEach { cookie ->
                cookie shouldStartWith "__Host-"
                cookie shouldContain "Secure"
                cookie shouldContain "SameSite="
                cookie shouldContain "Path=/"
                cookie shouldNotContain "Domain"
            }
        }

        context("the release") {
            test("every API response names the version of the backend") {
                browser().post("/api/flows").header("X-App-Version") shouldBe APP_VERSION
            }

            test("a client older than the minimum is told to upgrade and gets no further") {
                val before = environment.openFlows()

                val reply = browser().send("POST", "/api/flows", headers = arrayOf("X-Client-Version", "1.20.2"))

                reply.status shouldBe 426
                reply.header("X-App-Version") shouldBe APP_VERSION
                environment.openFlows() shouldBe before
            }

            test("a shorter version counts as older") {
                browser().get("/api/flows/${UUID.randomUUID()}", "X-Client-Version", "1.20").status shouldBe 426
            }

            test("numbers compare as numbers, not as text") {
                browser().get("/api/flows/${UUID.randomUUID()}", "X-Client-Version", "1.100.0").status shouldBe 410
            }

            test("the minimum itself passes") {
                browser().get("/api/flows/${UUID.randomUUID()}", "X-Client-Version", "1.20.3").status shouldBe 410
            }

            test("an unreadable version is told to upgrade") {
                browser().get("/api/flows/${UUID.randomUUID()}", "X-Client-Version", "dev").status shouldBe 426
            }

            test("a client that names no version is left alone") {
                browser().get("/api/flows/${UUID.randomUUID()}").status shouldBe 410
            }
        }
    })
