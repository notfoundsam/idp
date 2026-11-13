package idp.app.throttle

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import idp.app.account.passwordChangeBody
import idp.app.admin.inviteBody
import idp.app.admin.signedIn
import idp.app.login.LoginEnvironment
import idp.app.login.PASSWORD
import idp.app.login.Reply
import idp.app.login.WRONG_PASSWORD
import idp.app.login.text
import idp.sharedkernel.audit.Actor
import idp.sharedkernel.security.UserRole
import idp.sharedkernel.testing.TestValkey
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.slf4j.LoggerFactory
import java.util.UUID

private const val PASSWORD_CHANGE = "/api/account/password"
private const val NEW_PASSWORD = "amber-falcon-gathers-slowly"
private const val GUESSES_A_MINUTE = 10
private const val RESENDS_AN_HOUR = 10

/** The limits of a signed-in user count per user, whose ID is random, so the pooled tenants of `LoginEnvironment` share no bucket. */
class SessionLimitsTest :
    FunSpec({
        val environment = LoginEnvironment()
        val application =
            environment.start(
                "--VALKEY_URL=${TestValkey.shared.url}",
                "--THROTTLE_IP_HMAC_KEY=${UUID.randomUUID()}",
                "--idp.throttle.valkey.timeout=5s",
                "--idp.throttle.valkey.connect-timeout=10s",
                throttled = true,
            )

        afterSpec { application.close() }

        fun rejections(action: () -> Unit): List<String> {
            val logger = LoggerFactory.getLogger("idp.security") as Logger
            val events = ListAppender<ILoggingEvent>().also { it.start() }
            logger.addAppender(events)
            try {
                action()
            } finally {
                logger.detachAppender(events)
            }
            return events.list.map { it.formattedMessage }.filter { "event=throttle.rejected" in it }
        }

        fun throttled(reply: Reply) {
            reply.status shouldBe 429
            reply.header("Retry-After").shouldNotBeNull()
        }

        test("a session that guesses the current password is stopped, and the refusal reaches the log of security events") {
            val (browser, user) = environment.signedIn(application, "guesser@${environment.acme.host}", role = UserRole.USER)

            val rejected =
                rejections {
                    repeat(GUESSES_A_MINUTE) {
                        browser.post(PASSWORD_CHANGE, passwordChangeBody(WRONG_PASSWORD, NEW_PASSWORD)).status shouldBe 422
                    }
                    throttled(browser.post(PASSWORD_CHANGE, passwordChangeBody(PASSWORD, NEW_PASSWORD)))
                }

            rejected shouldHaveSize 1
            rejected.single() shouldContain "ChangePassword"
            rejected.single() shouldContain "dimension=USER"
            val journal = with(environment) { application.audit() }
            journal.filter { it.action == "user.password_set" && it.actor == Actor.User(user) } shouldHaveSize 0
        }

        test("the limit of one user leaves another user of the tenant alone") {
            val (stopped, _) = environment.signedIn(application, "stopped@${environment.acme.host}", role = UserRole.USER)
            val (other, _) = environment.signedIn(application, "unaffected@${environment.acme.host}", role = UserRole.USER)
            repeat(GUESSES_A_MINUTE) { stopped.post(PASSWORD_CHANGE, passwordChangeBody(WRONG_PASSWORD, NEW_PASSWORD)) }

            throttled(stopped.post(PASSWORD_CHANGE, passwordChangeBody(WRONG_PASSWORD, NEW_PASSWORD)))
            other.post(PASSWORD_CHANGE, passwordChangeBody(WRONG_PASSWORD, NEW_PASSWORD)).status shouldBe 422
        }

        test("an admin who resends one invitation over and over is stopped before the mails pile up") {
            val (browser, _) = environment.signedIn(application, "resender@${environment.acme.host}")
            val invited = browser.post("/api/admin/users", inviteBody("waiting@${environment.acme.host}", UserRole.USER))
            invited.status shouldBe 201
            val path = "/api/admin/users/${invited.json().text("id")}/invitation"

            val rejected =
                rejections {
                    repeat(RESENDS_AN_HOUR) { browser.post(path).status shouldBe 204 }
                    throttled(browser.post(path))
                }

            rejected.single() shouldContain "ResendInvitation"
            environment.sql.queryForObject(
                "SELECT count(*) FROM platform.outbox WHERE tenant_id = ? AND aggregate_id = ? AND event_type = 'user.invited'",
                Int::class.java,
                environment.acme.context.tenantId,
                UUID.fromString(invited.json().text("id")),
            ) shouldBe 1 + RESENDS_AN_HOUR
        }
    })
