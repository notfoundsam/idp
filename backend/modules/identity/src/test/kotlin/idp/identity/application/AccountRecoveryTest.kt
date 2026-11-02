package idp.identity.application

import idp.identity.domain.credential.PasskeyCredential
import idp.sharedkernel.audit.Actor
import idp.sharedkernel.audit.AuditLog
import idp.sharedkernel.audit.AuditRecord
import idp.sharedkernel.audit.AuditTarget
import idp.sharedkernel.crypto.KeyHierarchy
import idp.sharedkernel.crypto.adapter.OpenBaoKeyManagementService
import idp.sharedkernel.migration.TenantMigrationRunner
import idp.sharedkernel.migration.TenantSchema
import idp.sharedkernel.migration.TenantSchemaFactory
import idp.sharedkernel.outbox.OutboxPublisher
import idp.sharedkernel.security.UserRole
import idp.sharedkernel.security.UserRole.TENANT_ADMIN
import idp.sharedkernel.security.UserRole.USER
import idp.sharedkernel.security.UserStatus
import idp.sharedkernel.tenant.TenantContext
import idp.sharedkernel.tenant.TenantTransaction
import idp.sharedkernel.testing.MovableClock
import idp.sharedkernel.testing.TestDatabase
import idp.sharedkernel.testing.TestOpenBao
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import org.jooq.DSLContext
import org.springframework.jdbc.core.JdbcTemplate
import java.nio.charset.StandardCharsets.ISO_8859_1
import java.security.MessageDigest
import java.time.Duration
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit

private const val EMAIL = "ada.lovelace@acme.test"
private const val NEW_EMAIL = "ada.byron@acme.test"
private const val OTHER_EMAIL = "grace.hopper@acme.test"
private const val IP = "198.51.100.7"
private const val WAIT_SECONDS = 30L
private const val MIN_TOKEN_BYTES = 16
private val ADMIN = Actor.User(UUID.randomUUID())
private val KEY_CACHE_TTL: Duration = Duration.ofMinutes(1)
private val TTL: Duration = Duration.ofHours(72)
private val RECOVERY_TTL: Duration = Duration.ofHours(1)
private val NOTICE_TTL: Duration = Duration.ofDays(7)

private class RecoveryAudit : AuditLog {
    val changes = CopyOnWriteArrayList<AuditRecord>()

    @Volatile var failing = false

    override fun recordChange(record: AuditRecord) {
        check(!failing) { "audit is down" }
        changes += record
    }

    override fun recordDenial(record: AuditRecord) = throw UnsupportedOperationException()
}

private class Recovery {
    val database = TestDatabase.create()
    val owner = database.owner().also { TenantMigrationRunner(it).migrateAll() }
    val sql = JdbcTemplate(owner)
    val kms = OpenBaoKeyManagementService(TestOpenBao.address, TestOpenBao.TOKEN)
    val transaction = TenantTransaction(database.pooled("worker_runtime"))
    val keys = KeyHierarchy(kms, transaction, KEY_CACHE_TTL)
    val audit = RecoveryAudit()
    val clock = MovableClock()
    val service = IdentityService(transaction, keys, audit, OutboxPublisher(), clock, TTL, RECOVERY_TTL)
    val totp = TotpService(transaction, keys, audit, clock)

    fun tenant(): TenantContext {
        val id = UUID.randomUUID()
        sql.update("INSERT INTO platform.tenants (id, name, status) VALUES (?, 'tenant', 'ACTIVE')", id)
        TenantSchemaFactory(owner).create(id)
        TenantMigrationRunner(owner).migrateTenant(id)
        val context = TenantContext(id, kms.createKey("tenant-$id"))
        TenantContext.with(context) { keys.createTenantKey() }
        return context
    }

    fun <T> TenantContext.acting(block: IdentityService.() -> T): T = TenantContext.with(this) { service.block() }

    fun TenantContext.table(name: String) = "\"${TenantSchema(tenantId).name}\".$name"

    fun TenantContext.rows(name: String): List<Map<String, Any?>> = sql.queryForList("SELECT * FROM ${table(name)}")

    fun TenantContext.events(type: String): List<Map<String, Any?>> =
        sql.queryForList(
            "SELECT aggregate_id, payload::text AS payload FROM platform.outbox WHERE tenant_id = ? AND event_type = ?",
            tenantId,
            type,
        )

    fun TenantContext.invited(
        email: String = EMAIL,
        role: UserRole = USER,
    ): UUID = acting { invite(email, role, ADMIN, IP) }

    fun TenantContext.active(
        email: String = EMAIL,
        role: UserRole = USER,
    ): UUID {
        val user = invited(email, role)
        acting { acceptInvitation(checkNotNull(issueInvitation(user, ADMIN)), Actor.Anonymous) }
        return user
    }

    fun TenantContext.resetToken(user: UUID): String = acting { issuePasswordReset(user, Actor.System) }.shouldNotBeNull()

    fun TenantContext.emailToken(
        user: UUID,
        address: String = NEW_EMAIL,
    ): String {
        acting { requestEmailChange(user, address, Actor.User(user), IP) }
        return acting { issueEmailChange(user, Actor.System) }.shouldNotBeNull()
    }

    fun TenantContext.withTotp(user: UUID) {
        TenantContext.with(this) {
            totp.enroll(user)
            totp.issueRecoveryCodes(user, Actor.User(user))
        }
        sql.update("UPDATE ${table("totp_credentials")} SET confirmed_at = now() WHERE user_id = ?", user)
    }

    fun TenantContext.withPasskey(user: UUID) {
        sql.update(
            """
            INSERT INTO ${table("passkey_credentials")} (id, user_id, credential_id, attested_credential_data, sign_count,
                backup_eligible, backed_up, created_at)
            VALUES (?, ?, ?, ?, 0, false, false, now())
            """.trimIndent(),
            UUID.randomUUID(),
            user,
            PasskeyCredential.userHandle(user),
            byteArrayOf(1),
        )
    }

    fun <T> TenantContext.inTransaction(block: (DSLContext) -> T): T = TenantContext.with(this) { transaction.execute(block) }

    fun actions(): List<String> = audit.changes.map { it.action }
}

private fun sha256(text: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())

private fun printed(value: Any?): String =
    when (value) {
        is ByteArray -> String(value, ISO_8859_1)
        else -> value.toString()
    }

private fun <T> concurrently(call: () -> T): List<Result<T>> {
    val barrier = CyclicBarrier(2)
    return List(2) {
        CompletableFuture.supplyAsync {
            barrier.await(WAIT_SECONDS, TimeUnit.SECONDS)
            runCatching(call)
        }
    }.map { it.get(WAIT_SECONDS, TimeUnit.SECONDS) }
}

class AccountRecoveryTest :
    FunSpec({
        context("a password reset") {
            test("a request names only the user in its event and the audit") {
                Recovery().run {
                    val tenant = tenant()
                    val user = tenant.active()
                    audit.changes.clear()

                    tenant.acting { requestPasswordReset(EMAIL, IP) }

                    val event = tenant.events(IdentityEvents.PASSWORD_RESET_REQUESTED).single()
                    event["aggregate_id"] shouldBe user
                    event["payload"].toString() shouldNotContain EMAIL
                    val record = audit.changes.single()
                    record.action shouldBe "user.password_reset_requested"
                    record.target shouldBe AuditTarget.user(user)
                    record.ip shouldBe IP
                }
            }

            test("a login that matched nobody leaves the same traces, so the work takes as long") {
                Recovery().run {
                    val tenant = tenant()
                    audit.changes.clear()

                    tenant.acting { requestPasswordReset("nobody@acme.test", IP) }

                    tenant.events(IdentityEvents.PASSWORD_RESET_REQUESTED) shouldHaveSize 1
                    audit.changes
                        .single()
                        .target
                        .shouldBeNull()
                    tenant.rows("password_resets").shouldBeEmpty()
                }
            }

            test("the event of a login that matched nobody issues nothing") {
                Recovery().run {
                    val tenant = tenant()
                    tenant.acting { requestPasswordReset("nobody@acme.test") }
                    val nobody = tenant.events(IdentityEvents.PASSWORD_RESET_REQUESTED).single()["aggregate_id"] as UUID

                    tenant.acting { issuePasswordReset(nobody, Actor.System) }.shouldBeNull()
                }
            }

            test("the row stores only the hash of the token") {
                Recovery().run {
                    val tenant = tenant()
                    val token = tenant.resetToken(tenant.active())

                    val row = tenant.rows("password_resets").single()
                    row["token_hash"] shouldBe sha256(token)
                    row.values.map(::printed).forEach { it shouldNotContain token }
                }
            }

            test("the token carries at least 128 bits and is not a UUID") {
                Recovery().run {
                    val tenant = tenant()
                    val token = tenant.resetToken(tenant.active())

                    Base64.getUrlDecoder().decode(token).size shouldBeGreaterThanOrEqual MIN_TOKEN_BYTES
                    shouldThrow<IllegalArgumentException> { UUID.fromString(token) }
                }
            }

            test("reading the owner spends nothing, so a refused password can be typed again") {
                Recovery().run {
                    val tenant = tenant()
                    val user = tenant.active()
                    val token = tenant.resetToken(user)

                    tenant.acting { passwordResetOwner(token) } shouldBe user
                    tenant.acting { passwordResetOwner(token) } shouldBe user

                    tenant.acting { completePasswordReset(token, IP) } shouldBe user
                }
            }

            test("a token works once") {
                Recovery().run {
                    val tenant = tenant()
                    val token = tenant.resetToken(tenant.active())
                    tenant.acting { completePasswordReset(token) }

                    shouldThrow<PasswordResetNotValid> { tenant.acting { completePasswordReset(token) } }

                    tenant.acting { passwordResetOwner(token) }.shouldBeNull()
                    tenant.rows("password_resets").shouldBeEmpty()
                }
            }

            test("two concurrent uses of one token give one success") {
                Recovery().run {
                    val tenant = tenant()
                    val token = tenant.resetToken(tenant.active())

                    val outcomes = concurrently { tenant.acting { completePasswordReset(token) } }

                    outcomes.count { it.isSuccess } shouldBe 1
                }
            }

            test("a token expires") {
                Recovery().run {
                    val tenant = tenant()
                    val token = tenant.resetToken(tenant.active())
                    clock.offset = RECOVERY_TTL.plusSeconds(1)

                    tenant.acting { passwordResetOwner(token) }.shouldBeNull()
                    shouldThrow<PasswordResetNotValid> { tenant.acting { completePasswordReset(token) } }
                }
            }

            test("a new token cancels the old one") {
                Recovery().run {
                    val tenant = tenant()
                    val user = tenant.active()
                    val first = tenant.resetToken(user)
                    val second = tenant.resetToken(user)

                    shouldThrow<PasswordResetNotValid> { tenant.acting { completePasswordReset(first) } }

                    tenant.acting { completePasswordReset(second) } shouldBe user
                }
            }

            test("a made-up token is refused, and a token of one tenant means nothing in another") {
                Recovery().run {
                    val first = tenant()
                    val second = tenant()
                    val token = first.resetToken(first.active())

                    shouldThrow<PasswordResetNotValid> { first.acting { completePasswordReset("made-up") } }
                    shouldThrow<PasswordResetNotValid> { second.acting { completePasswordReset(token) } }
                }
            }

            test("only an active user gets a token") {
                Recovery().run {
                    val tenant = tenant()
                    val invited = tenant.invited(EMAIL)
                    val blocked = tenant.active(OTHER_EMAIL)
                    tenant.acting { block(blocked, ADMIN) }

                    tenant.acting { issuePasswordReset(invited, Actor.System) }.shouldBeNull()
                    tenant.acting { issuePasswordReset(blocked, Actor.System) }.shouldBeNull()
                    tenant.acting { issuePasswordReset(UUID.randomUUID(), Actor.System) }.shouldBeNull()
                }
            }

            test("a user blocked after the mail cannot use the token") {
                Recovery().run {
                    val tenant = tenant()
                    val user = tenant.active()
                    val token = tenant.resetToken(user)
                    tenant.acting { block(user, ADMIN) }

                    tenant.acting { passwordResetOwner(token) }.shouldBeNull()
                    shouldThrow<PasswordResetNotValid> { tenant.acting { completePasswordReset(token) } }
                }
            }

            test("it cancels an email change that is waiting, because the session behind it may be stolen") {
                Recovery().run {
                    val tenant = tenant()
                    val user = tenant.active()
                    val change = tenant.emailToken(user)

                    tenant.acting { completePasswordReset(tenant.resetToken(user)) }

                    shouldThrow<EmailChangeNotValid> { tenant.acting { confirmEmailChange(change) } }
                }
            }

            test("issuing and completing are recorded, and a failed record keeps the token") {
                Recovery().run {
                    val tenant = tenant()
                    val user = tenant.active()
                    val token = tenant.resetToken(user)
                    audit.failing = true

                    shouldThrow<IllegalStateException> { tenant.acting { completePasswordReset(token, IP) } }

                    audit.failing = false
                    audit.changes.clear()
                    tenant.acting { completePasswordReset(token, IP) }
                    val record = audit.changes.single()
                    record.action shouldBe "user.password_reset"
                    record.actor shouldBe Actor.User(user)
                    record.ip shouldBe IP
                }
            }
        }

        context("an invitation") {
            test("names its owner without spending the token") {
                Recovery().run {
                    val tenant = tenant()
                    val user = tenant.invited()
                    val token = tenant.acting { issueInvitation(user, Actor.System) }.shouldNotBeNull()

                    tenant.acting { invitationOwner(token) } shouldBe user

                    tenant.acting { acceptInvitation(token, Actor.Anonymous) } shouldBe user
                }
            }

            test("names nobody once it is spent, expired or made up") {
                Recovery().run {
                    val tenant = tenant()
                    val spent = tenant.invited(EMAIL)
                    val spentToken = tenant.acting { issueInvitation(spent, Actor.System) }.shouldNotBeNull()
                    tenant.acting { acceptInvitation(spentToken, Actor.Anonymous) }
                    val expired = tenant.invited(OTHER_EMAIL)
                    val expiredToken = tenant.acting { issueInvitation(expired, Actor.System) }.shouldNotBeNull()
                    clock.offset = TTL.plusSeconds(1)

                    tenant.acting { invitationOwner(spentToken) }.shouldBeNull()
                    tenant.acting { invitationOwner(expiredToken) }.shouldBeNull()
                    tenant.acting { invitationOwner("made-up") }.shouldBeNull()
                }
            }
        }

        context("an MFA reset") {
            test("removes TOTP, recovery codes and passkeys of this user alone") {
                Recovery().run {
                    val tenant = tenant()
                    val user = tenant.active(EMAIL)
                    val other = tenant.active(OTHER_EMAIL)
                    listOf(user, other).forEach {
                        tenant.withTotp(it)
                        tenant.withPasskey(it)
                    }

                    tenant.acting { resetMfa(user, ADMIN, IP) }

                    listOf("totp_credentials", "recovery_codes", "passkey_credentials").forEach { table ->
                        val owners = tenant.rows(table).map { it["user_id"] }.toSet()

                        owners.shouldContainExactly(other)
                    }
                }
            }

            test("lets the user enroll TOTP again") {
                Recovery().run {
                    val tenant = tenant()
                    val user = tenant.active()
                    tenant.withTotp(user)
                    shouldThrow<TotpAlreadyEnrolled> { TenantContext.with(tenant) { totp.enroll(user) } }

                    tenant.acting { resetMfa(user, ADMIN) }

                    TenantContext.with(tenant) { totp.enroll(user) }.secret.isNotEmpty() shouldBe true
                }
            }

            test("is in the audit under the actor") {
                Recovery().run {
                    val tenant = tenant()
                    val user = tenant.active()
                    audit.changes.clear()

                    tenant.acting { resetMfa(user, ADMIN, IP) }

                    val record = audit.changes.single()
                    record.action shouldBe "user.mfa_reset"
                    record.actor shouldBe ADMIN
                    record.target shouldBe AuditTarget.user(user)
                    record.ip shouldBe IP
                }
            }

            test("a failed audit record keeps the factors") {
                Recovery().run {
                    val tenant = tenant()
                    val user = tenant.active()
                    tenant.withTotp(user)
                    audit.failing = true

                    shouldThrow<IllegalStateException> { tenant.acting { resetMfa(user, ADMIN) } }

                    tenant.rows("totp_credentials") shouldHaveSize 1
                }
            }

            test("a missing user answers as not found") {
                Recovery().run {
                    val tenant = tenant()

                    shouldThrow<UserNotFound> { tenant.acting { resetMfa(UUID.randomUUID(), ADMIN) } }
                }
            }
        }

        context("an email change") {
            test("changes nothing until the new address confirms") {
                Recovery().run {
                    val tenant = tenant()
                    val user = tenant.active()

                    tenant.emailToken(user)

                    tenant.acting { emailOf(user) } shouldBe EMAIL
                    tenant.acting { findByEmail(EMAIL) }.shouldNotBeNull().id shouldBe user
                    tenant.acting { findByEmail(NEW_EMAIL) }.shouldBeNull()
                }
            }

            test("the waiting address and the token are stored sealed, and the event carries neither") {
                Recovery().run {
                    val tenant = tenant()
                    val user = tenant.active()

                    val token = tenant.emailToken(user)

                    val row = tenant.rows("email_changes").single()
                    row["token_hash"] shouldBe sha256(token)
                    listOf(NEW_EMAIL, "byron", token).forEach { secret ->
                        row.values.map(::printed).forEach { it shouldNotContain secret }
                    }
                    tenant.acting { pendingEmailOf(user) } shouldBe NEW_EMAIL
                    val event = tenant.events(IdentityEvents.EMAIL_CHANGE_REQUESTED).single()
                    event["aggregate_id"] shouldBe user
                    event["payload"].toString() shouldNotContain NEW_EMAIL
                }
            }

            test("the token carries at least 128 bits and is not a UUID") {
                Recovery().run {
                    val tenant = tenant()
                    val token = tenant.emailToken(tenant.active())

                    Base64.getUrlDecoder().decode(token).size shouldBeGreaterThanOrEqual MIN_TOKEN_BYTES
                    shouldThrow<IllegalArgumentException> { UUID.fromString(token) }
                }
            }

            test("an invalid address is refused") {
                Recovery().run {
                    val tenant = tenant()
                    val user = tenant.active()

                    shouldThrow<idp.identity.domain.InvalidEmail> { tenant.acting { requestEmailChange(user, "nonsense", ADMIN) } }

                    tenant.rows("email_changes").shouldBeEmpty()
                }
            }

            test("confirming moves the address and the blind index") {
                Recovery().run {
                    val tenant = tenant()
                    val user = tenant.active()
                    val token = tenant.emailToken(user)

                    tenant.acting { confirmEmailChange(token, IP) } shouldBe user

                    tenant.acting { emailOf(user) } shouldBe NEW_EMAIL
                    tenant.acting { findByEmail(NEW_EMAIL) }.shouldNotBeNull().id shouldBe user
                    tenant.acting { findByEmail(EMAIL) }.shouldBeNull()
                    tenant.acting { pendingEmailOf(user) }.shouldBeNull()
                    tenant.invited(EMAIL) shouldNotBe user
                }
            }

            test("the old address stays reachable for the notice until the handler is done with it") {
                Recovery().run {
                    val tenant = tenant()
                    val user = tenant.active()

                    tenant.acting { confirmEmailChange(tenant.emailToken(user)) }

                    tenant.acting { previousEmailOf(user) } shouldBe EMAIL
                    val event = tenant.events(IdentityEvents.EMAIL_CHANGED).single()
                    event["aggregate_id"] shouldBe user
                    event["payload"].toString() shouldNotContain EMAIL
                    val notice = tenant.rows("email_change_notices").single()
                    notice.values.map(::printed).forEach { it shouldNotContain EMAIL }

                    tenant.acting { forgetPreviousEmail(user) }

                    tenant.acting { previousEmailOf(user) }.shouldBeNull()
                    tenant.rows("email_change_notices").shouldBeEmpty()
                }
            }

            test("two quick changes still tell the first address, the one the owner reads") {
                Recovery().run {
                    val tenant = tenant()
                    val user = tenant.active()

                    tenant.acting { confirmEmailChange(tenant.emailToken(user, NEW_EMAIL)) }
                    tenant.acting { confirmEmailChange(tenant.emailToken(user, OTHER_EMAIL)) }

                    tenant.acting { previousEmailOf(user) } shouldBe EMAIL
                }
            }

            test("a token works once") {
                Recovery().run {
                    val tenant = tenant()
                    val token = tenant.emailToken(tenant.active())
                    tenant.acting { confirmEmailChange(token) }

                    shouldThrow<EmailChangeNotValid> { tenant.acting { confirmEmailChange(token) } }
                }
            }

            test("a token expires") {
                Recovery().run {
                    val tenant = tenant()
                    val user = tenant.active()
                    val token = tenant.emailToken(user)
                    clock.offset = RECOVERY_TTL.plusSeconds(1)

                    shouldThrow<EmailChangeNotValid> { tenant.acting { confirmEmailChange(token) } }

                    tenant.acting { emailOf(user) } shouldBe EMAIL
                }
            }

            test("a request past its time issues no token") {
                Recovery().run {
                    val tenant = tenant()
                    val user = tenant.active()
                    tenant.acting { requestEmailChange(user, NEW_EMAIL, ADMIN) }
                    clock.offset = RECOVERY_TTL.plusSeconds(1)

                    tenant.acting { issueEmailChange(user, Actor.System) }.shouldBeNull()
                }
            }

            test("a new request cancels the old one") {
                Recovery().run {
                    val tenant = tenant()
                    val user = tenant.active()
                    val first = tenant.emailToken(user, NEW_EMAIL)
                    val second = tenant.emailToken(user, OTHER_EMAIL)

                    shouldThrow<EmailChangeNotValid> { tenant.acting { confirmEmailChange(first) } }

                    tenant.acting { confirmEmailChange(second) }
                    tenant.acting { emailOf(user) } shouldBe OTHER_EMAIL
                }
            }

            test("an address that belongs to someone else is accepted at first and refused at the end, so it cannot be probed") {
                Recovery().run {
                    val tenant = tenant()
                    val user = tenant.active(EMAIL)
                    tenant.active(OTHER_EMAIL)

                    val token = tenant.emailToken(user, OTHER_EMAIL)

                    shouldThrow<EmailChangeNotValid> { tenant.acting { confirmEmailChange(token) } }

                    tenant.acting { emailOf(user) } shouldBe EMAIL
                }
            }

            test("a blocked user cannot confirm") {
                Recovery().run {
                    val tenant = tenant()
                    val user = tenant.active()
                    val token = tenant.emailToken(user)
                    tenant.acting { block(user, ADMIN) }

                    shouldThrow<EmailChangeNotValid> { tenant.acting { confirmEmailChange(token) } }

                    tenant.acting { emailOf(user) } shouldBe EMAIL
                }
            }

            test("confirming cancels a password reset, whose link went to the old address") {
                Recovery().run {
                    val tenant = tenant()
                    val user = tenant.active()
                    val reset = tenant.resetToken(user)

                    tenant.acting { confirmEmailChange(tenant.emailToken(user)) }

                    shouldThrow<PasswordResetNotValid> { tenant.acting { completePasswordReset(reset) } }
                }
            }

            test("each step is recorded without an address") {
                Recovery().run {
                    val tenant = tenant()
                    val user = tenant.active()
                    audit.changes.clear()

                    tenant.acting { confirmEmailChange(tenant.emailToken(user), IP) }

                    actions().shouldContainExactly("user.email_change_requested", "user.email_change_issued", "user.email_changed")
                    audit.changes.last().ip shouldBe IP
                    audit.changes.flatMap { it.details.values }.forEach { value ->
                        listOf(EMAIL, NEW_EMAIL).forEach { value shouldNotContain it }
                    }
                }
            }
        }

        context("the recovery cleanup") {
            test("removes what expired and keeps what is live") {
                Recovery().run {
                    val tenant = tenant()
                    val expired = tenant.active(EMAIL)
                    tenant.resetToken(expired)
                    tenant.emailToken(expired)
                    val noticed = tenant.active("noticed@acme.test")
                    tenant.acting { confirmEmailChange(tenant.emailToken(noticed)) }
                    clock.offset = RECOVERY_TTL.plusSeconds(1)
                    val live = tenant.active(OTHER_EMAIL)
                    tenant.resetToken(live)
                    tenant.emailToken(live, "live@acme.test")

                    tenant.inTransaction { service.recoveryCleanup.delete(it, clock.instant().atOffset(ZoneOffset.UTC)) }

                    tenant.rows("password_resets").map { it["user_id"] }.shouldContainExactly(live)
                    tenant.rows("email_changes").map { it["user_id"] }.shouldContainExactly(live)
                    tenant.rows("email_change_notices").map { it["user_id"] }.shouldContainExactly(noticed)
                }
            }

            test("gives up on a notice that nobody could send") {
                Recovery().run {
                    val tenant = tenant()
                    val user = tenant.active()
                    tenant.acting { confirmEmailChange(tenant.emailToken(user)) }
                    clock.offset = NOTICE_TTL.plusSeconds(1)

                    tenant.inTransaction { service.recoveryCleanup.delete(it, clock.instant().atOffset(ZoneOffset.UTC)) }

                    tenant.rows("email_change_notices").shouldBeEmpty()
                }
            }

            test("has a stable name, which is its lock") {
                Recovery().run {
                    service.recoveryCleanup.name shouldBe "recovery-tokens"
                }
            }
        }

        context("a new invitation for an admin") {
            test("creates an invited admin when nobody has the address") {
                Recovery().run {
                    val tenant = tenant()
                    audit.changes.clear()

                    val user = tenant.acting { reinviteAdmin(EMAIL) }

                    val view = tenant.acting { user(user) }.shouldNotBeNull()
                    view.role shouldBe TENANT_ADMIN
                    view.status shouldBe UserStatus.INVITED
                    tenant.events(IdentityEvents.INVITED).single()["aggregate_id"] shouldBe user
                    audit.changes.single().actor shouldBe Actor.System
                }
            }

            test("sends a first admin whose link expired another one") {
                Recovery().run {
                    val tenant = tenant()
                    val user = tenant.invited(role = TENANT_ADMIN)
                    val expired = tenant.acting { issueInvitation(user, Actor.System) }.shouldNotBeNull()
                    clock.offset = TTL.plusSeconds(1)

                    tenant.acting { reinviteAdmin(EMAIL) } shouldBe user

                    tenant.events(IdentityEvents.INVITED) shouldHaveSize 2
                    val fresh = tenant.acting { issueInvitation(user, Actor.System) }.shouldNotBeNull()
                    shouldThrow<InvitationNotValid> { tenant.acting { acceptInvitation(expired, Actor.Anonymous) } }
                    tenant.acting { acceptInvitation(fresh, Actor.Anonymous) } shouldBe user
                }
            }

            test("takes an admin who lost everything back to the start, without the factors") {
                Recovery().run {
                    val tenant = tenant()
                    val user = tenant.active(role = TENANT_ADMIN)
                    tenant.withTotp(user)
                    tenant.withPasskey(user)
                    val reset = tenant.resetToken(user)
                    val change = tenant.emailToken(user)
                    audit.changes.clear()

                    tenant.acting { reinviteAdmin(EMAIL) } shouldBe user

                    tenant.acting { user(user) }.shouldNotBeNull().status shouldBe UserStatus.INVITED
                    listOf("totp_credentials", "recovery_codes", "passkey_credentials").forEach { tenant.rows(it).shouldBeEmpty() }
                    tenant.acting { issuePasswordReset(user, Actor.System) }.shouldBeNull()
                    shouldThrow<PasswordResetNotValid> { tenant.acting { completePasswordReset(reset) } }
                    shouldThrow<EmailChangeNotValid> { tenant.acting { confirmEmailChange(change) } }
                    val record = audit.changes.single()
                    record.action shouldBe "user.reinvited"
                    record.actor shouldBe Actor.System
                    record.target shouldBe AuditTarget.user(user)
                }
            }

            test("makes an admin of the user who holds the address") {
                Recovery().run {
                    val tenant = tenant()
                    val user = tenant.active(role = USER)

                    tenant.acting { reinviteAdmin(EMAIL) } shouldBe user

                    tenant.acting { user(user) }.shouldNotBeNull().role shouldBe TENANT_ADMIN
                }
            }

            test("finds the user whatever the spelling of the address") {
                Recovery().run {
                    val tenant = tenant()
                    val user = tenant.active(role = TENANT_ADMIN)

                    tenant.acting { reinviteAdmin(" ADA.Lovelace@ACME.test ") } shouldBe user
                }
            }
        }
    })
