package idp.app.login

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import idp.sharedkernel.testing.TestValkey
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.slf4j.LoggerFactory
import java.net.http.HttpRequest
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Flow

private const val MAX_OPEN_FLOWS = 3
private const val MAX_REQUEST_BYTES = 1024
private const val ATTEMPTS_PER_MINUTE = 10
private const val ATTEMPTS_BEFORE_DELAY = 4
private const val MAILS_AN_HOUR = 5

class LoginLimitsTest :
    FunSpec({
        val environment = LoginEnvironment()

        context("without Valkey") {
            val application =
                environment.start("--idp.login.max-open-flows=$MAX_OPEN_FLOWS", "--idp.login.max-request-size=${MAX_REQUEST_BYTES}B")

            afterSpec { application.close() }

            test("a tenant refuses flows beyond its limit, and the next tenant does not notice") {
                repeat(MAX_OPEN_FLOWS) { Browser(application).post("/api/flows").status shouldBe 201 }

                val refused = Browser(application).post("/api/flows")

                refused.status shouldBe 429
                refused.header("Retry-After").shouldNotBeNull()
                environment.openFlows() shouldBe MAX_OPEN_FLOWS
                Browser(application, GLOBEX).post("/api/flows").status shouldBe 201

                environment.sql.update("DELETE FROM ${environment.acme.table("flows")}")
            }

            test("a body over the limit is refused before anyone reads it") {
                val browser = Browser(application)
                val flow = browser.startFlow()

                val refused = browser.post("/api/flows/$flow/login", loginBody("x".repeat(MAX_REQUEST_BYTES)))

                refused.status shouldBe 413
                browser.get("/api/flows/$flow").json().text("step") shouldBe "IDENTIFY"
            }

            test("a body at the limit is read") {
                val browser = Browser(application)
                val flow = browser.startFlow()
                val padding = MAX_REQUEST_BYTES - loginBody("").length

                browser.post("/api/flows/$flow/login", loginBody("x".repeat(padding))).status shouldBe 200
            }

            test("a body without a length is refused, so the limit cannot be walked around") {
                val browser = Browser(application)
                val flow = browser.startFlow()
                val unknownLength =
                    HttpRequest.BodyPublishers.fromPublisher(
                        Flow.Publisher { subscriber ->
                            HttpRequest.BodyPublishers.ofString(loginBody(EMAIL)).subscribe(subscriber)
                        },
                    )

                browser.send("POST", "/api/flows/$flow/login", unknownLength).status shouldBe 411
            }

            test("the limit holds on every tenant") {
                val browser = Browser(application, GLOBEX)

                browser.post("/api/flows", "x".repeat(MAX_REQUEST_BYTES + 1)).status shouldBe 413
            }
        }

        context("the database stays the guard when limits are off") {
            val application = environment.start()

            afterSpec { application.close() }

            test("with THROTTLE_ENABLED=false failed attempts still count and the delay still holds") {
                val user = with(environment) { application.user("counted@acme.test") }
                val browser = Browser(application)

                repeat(ATTEMPTS_BEFORE_DELAY) {
                    browser.password(browser.identified("counted@acme.test"), WRONG_PASSWORD).status shouldBe 422
                }

                environment.failedAttempts(user) shouldBe ATTEMPTS_BEFORE_DELAY
                // From the clock of the test, so the delay is still running however slow the next request is.
                environment.sql.update(
                    "UPDATE ${environment.acme.table("password_credentials")} SET next_attempt_at = ? " +
                        "WHERE user_id = ? AND next_attempt_at IS NOT NULL",
                    Timestamp.from(Instant.now().plus(Duration.ofHours(1))),
                    user,
                ) shouldBe 1
                browser.password(browser.identified("counted@acme.test")).status shouldBe 422
            }
        }

        context("with Valkey") {
            val application =
                environment.start(
                    "--VALKEY_URL=${TestValkey.shared.url}",
                    "--THROTTLE_IP_HMAC_KEY=${UUID.randomUUID()}",
                    "--idp.throttle.valkey.timeout=5s",
                    "--idp.throttle.valkey.connect-timeout=10s",
                    throttled = true,
                )

            afterSpec { application.close() }

            test("an address that guesses passwords is stopped, and the refusal reaches the log of security events") {
                val logger = LoggerFactory.getLogger("idp.security") as Logger
                val events = ListAppender<ILoggingEvent>().also { it.start() }
                logger.addAppender(events)
                val browser = Browser(application, GLOBEX)
                val flow = browser.identified("guessed@acme.test")

                try {
                    repeat(ATTEMPTS_PER_MINUTE) { browser.password(flow, WRONG_PASSWORD).status shouldBe 422 }

                    val refused = browser.password(flow, WRONG_PASSWORD)

                    refused.status shouldBe 429
                    refused.header("Retry-After").shouldNotBeNull()
                } finally {
                    logger.detachAppender(events)
                }

                events.list shouldHaveSize 1
                events.list.single().formattedMessage shouldContain "event=throttle.rejected"
                events.list.single().formattedMessage shouldContain "SubmitPassword"
            }

            test("an address that asks for reset mails is stopped the same way for a login that matched nobody") {
                val browser = Browser(application, GLOBEX)

                repeat(MAILS_AN_HOUR) { browser.post("/api/password-reset", loginBody("anyone-$it@acme.test")).status shouldBe 202 }

                val refused = browser.post("/api/password-reset", loginBody("anyone@acme.test"))

                refused.status shouldBe 429
                refused.header("Retry-After").shouldNotBeNull()
            }
        }
    })
