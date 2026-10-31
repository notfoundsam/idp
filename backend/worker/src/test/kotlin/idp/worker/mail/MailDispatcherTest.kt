package idp.worker.mail

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxyUtil
import ch.qos.logback.core.read.ListAppender
import idp.sharedkernel.migration.TenantMigrationRunner
import idp.sharedkernel.migration.TenantSchema
import idp.sharedkernel.migration.TenantSchemaFactory
import idp.sharedkernel.outbox.EventHandler
import idp.sharedkernel.outbox.EventScope
import idp.sharedkernel.outbox.IdempotentHandler
import idp.sharedkernel.outbox.NewEvent
import idp.sharedkernel.outbox.OutboxDispatcher
import idp.sharedkernel.outbox.OutboxEvent
import idp.sharedkernel.outbox.OutboxPublisher
import idp.sharedkernel.tenant.PlatformTransaction
import idp.sharedkernel.tenant.TenantContext
import idp.sharedkernel.tenant.TenantTransaction
import idp.sharedkernel.testing.MovableClock
import idp.sharedkernel.testing.TestDatabase
import idp.sharedkernel.testing.TestMailpit
import idp.worker.WorkerApplication
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.slf4j.LoggerFactory
import org.springframework.boot.runApplication
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mail.javamail.JavaMailSender
import java.net.ServerSocket
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

private const val ADDRESS = "ada@acme.test"
private const val FROM = "no-reply@idp.test"
private const val TOKEN = "secret-token"
private const val LINK = "https://acme.idp.test/invite?token=$TOKEN"
private const val INVITED = "user.invited"
private const val WAIT_SECONDS = 30L

private class CapturedLog : AutoCloseable {
    private val logger = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
    private val appender = ListAppender<ILoggingEvent>().also { it.start() }

    init {
        logger.addAppender(appender)
    }

    val text: String
        get() =
            appender.list.toList().joinToString("\n") { line ->
                line.formattedMessage + line.throwableProxy?.let(ThrowableProxyUtil::asString).orEmpty()
            }

    override fun close() {
        logger.detachAppender(appender)
    }
}

private class Recipients(
    private val address: String? = ADDRESS,
) : MailRecipients {
    val asked = mutableListOf<UUID>()

    override fun addressOf(userId: UUID): String? {
        asked += userId
        return address
    }
}

private class InvitationHandler(
    private val mail: MailDispatcher,
) : EventHandler {
    override val name = "invitation-mail"
    override val eventType = INVITED
    override val scope = EventScope.TENANT

    override fun handle(event: OutboxEvent) {
        mail.send(Mail(event.aggregateId, MailTemplate.INVITATION, mapOf("tenant" to "Acme", "link" to LINK)))
    }
}

private class Tenant {
    val database = TestDatabase.create()
    val owner = database.owner().also { TenantMigrationRunner(it).migrateAll() }
    val jdbc = JdbcTemplate(owner)
    val worker = database.role("worker_runtime")
    val id: UUID = UUID.randomUUID()
    val clock = MovableClock()
    val transaction = TenantTransaction(worker)

    init {
        jdbc.update("INSERT INTO platform.tenants (id, name, status, kek_uri) VALUES (?, 'tenant', 'ACTIVE', 'kek')", id)
        TenantSchemaFactory(owner).create(id)
        TenantMigrationRunner(owner).migrateTenant(id)
    }

    fun publish(userId: UUID) {
        TenantContext.with(TenantContext(id, kekUri = null)) {
            transaction.execute { OutboxPublisher().publish(it, NewEvent("user", userId, 1, INVITED)) }
        }
    }

    fun dispatcher(handler: EventHandler) =
        OutboxDispatcher(
            PlatformTransaction(worker),
            IdempotentHandler(transaction),
            listOf(handler),
            clock,
            retryDelay = Duration.ofSeconds(1),
        )

    fun redeliver() {
        jdbc.update("UPDATE platform.outbox SET status = 'PENDING', attempts = 0, available_at = now()")
    }

    fun status(): String? = jdbc.queryForObject("SELECT status FROM platform.outbox", String::class.java)

    fun payload(): String = jdbc.queryForObject("SELECT payload::text FROM platform.outbox", String::class.java)!!

    fun processed(): String? =
        jdbc.queryForObject("""SELECT count(*) FROM "${TenantSchema(id).name}".processed_events""", String::class.java)
}

class MailDispatcherTest :
    FunSpec({
        val mailpit = TestMailpit.start()
        afterSpec { mailpit.close() }

        fun worker(vararg arguments: String): ConfigurableApplicationContext =
            runApplication<WorkerApplication>("--server.port=0", *arguments)

        val plain =
            worker(
                "--spring.mail.host=${mailpit.host}",
                "--spring.mail.port=${mailpit.smtpPort}",
                "--spring.mail.properties.mail.smtp.auth=false",
                "--spring.mail.properties.mail.smtp.starttls.enable=false",
                "--spring.mail.properties.mail.smtp.starttls.required=false",
            )
        afterSpec { plain.close() }

        fun dispatcher(
            recipients: MailRecipients = Recipients(),
            context: ConfigurableApplicationContext = plain,
        ) = MailDispatcher(context.getBean(JavaMailSender::class.java), recipients, MailTemplates(), FROM)

        fun invitation(userId: UUID = UUID.randomUUID()) = Mail(userId, MailTemplate.INVITATION, mapOf("tenant" to "Acme", "link" to LINK))

        beforeTest {
            mailpit.acceptRecipients()
            mailpit.clear()
        }

        test("a mail reaches the SMTP server with both bodies") {
            dispatcher().send(invitation())

            val mail = mailpit.messages().single()
            mail.from shouldBe FROM
            mail.to.shouldContainExactly(ADDRESS)
            mail.subject shouldBe "You are invited to Acme"
            mail.text shouldContain LINK
            mail.html shouldContain "href=\"${LINK}\""
        }

        test("a value cannot add a recipient through the subject") {
            dispatcher().send(
                Mail(
                    UUID.randomUUID(),
                    MailTemplate.EMAIL_CHANGED,
                    mapOf("tenant" to "Acme\r\nBcc: mallory@evil.test"),
                ),
            )

            val mail = mailpit.messages().single()
            mail.subject shouldBe "Your Acme Bcc: mallory@evil.test email was changed"
            mail.to.shouldContainExactly(ADDRESS)
        }

        test("a redelivered event sends one mail") {
            Tenant().run {
                publish(UUID.randomUUID())
                val handler = InvitationHandler(dispatcher())

                dispatcher(handler).dispatch()
                redeliver()
                dispatcher(handler).dispatch()

                mailpit.messages() shouldHaveSize 1
                processed() shouldBe "1"
            }
        }

        test("a rejected mail is not marked as processed, so the retry sends it") {
            Tenant().run {
                publish(UUID.randomUUID())
                val handler = InvitationHandler(dispatcher())

                mailpit.rejectRecipients()
                dispatcher(handler).dispatch()

                status() shouldBe "PENDING"
                processed() shouldBe "0"
                mailpit.messages().shouldBeEmpty()

                mailpit.acceptRecipients()
                redeliver()
                dispatcher(handler).dispatch()

                status() shouldBe "DONE"
                mailpit.messages() shouldHaveSize 1
            }
        }

        test("a user without an address gets no mail and the event finishes") {
            Tenant().run {
                publish(UUID.randomUUID())

                dispatcher(InvitationHandler(dispatcher(Recipients(address = null)))).dispatch()

                status() shouldBe "DONE"
                mailpit.messages().shouldBeEmpty()
            }
        }

        context("the address") {
            test("comes from MailRecipients by user ID") {
                Tenant().run {
                    val userId = UUID.randomUUID()
                    val recipients = Recipients()
                    publish(userId)

                    dispatcher(InvitationHandler(dispatcher(recipients))).dispatch()

                    val mail = mailpit.messages().single()
                    recipients.asked.shouldContainExactly(userId)
                    mail.to.shouldContainExactly(ADDRESS)
                }
            }

            test("stays out of the event and the logs when a mail goes out") {
                Tenant().run {
                    CapturedLog().use { log ->
                        publish(UUID.randomUUID())

                        dispatcher(InvitationHandler(dispatcher())).dispatch()

                        mailpit.messages() shouldHaveSize 1
                        payload() shouldNotContain ADDRESS
                        log.text shouldNotContain ADDRESS
                        log.text shouldNotContain TOKEN
                    }
                }
            }

            test("stays out of the logs when the SMTP server rejects it") {
                Tenant().run {
                    CapturedLog().use { log ->
                        publish(UUID.randomUUID())
                        mailpit.rejectRecipients()

                        dispatcher(InvitationHandler(dispatcher())).dispatch()

                        log.text shouldContain "failed on attempt 1"
                        log.text shouldNotContain ADDRESS
                        log.text shouldNotContain TOKEN
                    }
                }
            }

            test("stays out of the logs when it cannot be parsed") {
                Tenant().run {
                    CapturedLog().use { log ->
                        val malformed = "ada lovelace@acme.test"
                        publish(UUID.randomUUID())

                        dispatcher(InvitationHandler(dispatcher(Recipients(malformed)))).dispatch()

                        log.text shouldContain "failed on attempt 1"
                        log.text shouldNotContain "lovelace"
                    }
                }
            }
        }

        context("the default SMTP settings") {
            test("refuse a server without STARTTLS") {
                worker(
                    "--spring.mail.host=${mailpit.host}",
                    "--spring.mail.port=${mailpit.smtpPort}",
                    "--spring.mail.username=user",
                    "--spring.mail.password=password",
                ).use { context ->
                    shouldThrow<MailNotSent> { dispatcher(context = context).send(invitation()) }

                    mailpit.messages().shouldBeEmpty()
                }
            }

            test("give up on a server that accepts the connection and stays silent") {
                ServerSocket(0).use { silent ->
                    worker(
                        "--spring.mail.host=127.0.0.1",
                        "--spring.mail.port=${silent.localPort}",
                        "--spring.mail.username=user",
                        "--spring.mail.password=password",
                    ).use { context ->
                        val send = CompletableFuture.supplyAsync { runCatching { dispatcher(context = context).send(invitation()) } }

                        val failure = send.get(WAIT_SECONDS, TimeUnit.SECONDS).exceptionOrNull()

                        failure.shouldBeInstanceOf<MailNotSent>().message shouldContain "Timeout"
                    }
                }
            }
        }
    })
