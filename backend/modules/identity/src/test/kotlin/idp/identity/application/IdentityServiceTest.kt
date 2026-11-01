package idp.identity.application

import idp.identity.adapters.persistence.UserProfiles
import idp.identity.adapters.persistence.Users
import idp.identity.domain.EmailTaken
import idp.identity.domain.ForbiddenUserChange
import idp.identity.domain.InvalidEmail
import idp.identity.domain.LastAdminRequired
import idp.identity.domain.User
import idp.identity.domain.UserProfile
import idp.sharedkernel.audit.Actor
import idp.sharedkernel.audit.AuditLog
import idp.sharedkernel.audit.AuditRecord
import idp.sharedkernel.audit.AuditTarget
import idp.sharedkernel.crypto.FieldDecryptionException
import idp.sharedkernel.crypto.KeyHierarchy
import idp.sharedkernel.crypto.adapter.OpenBaoKeyManagementService
import idp.sharedkernel.migration.TenantMigrationRunner
import idp.sharedkernel.migration.TenantSchema
import idp.sharedkernel.migration.TenantSchemaFactory
import idp.sharedkernel.outbox.OutboxPublisher
import idp.sharedkernel.persistence.VersionConflictException
import idp.sharedkernel.scheduling.TenantCleanup
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
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.jooq.DSLContext
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import java.nio.charset.StandardCharsets.ISO_8859_1
import java.security.MessageDigest
import java.time.Duration
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit

private const val EMAIL = "ada.lovelace@acme.test"
private const val NAME = "Ada Lovelace"
private const val PHONE = "+81 90 1234 5678"
private const val IP = "198.51.100.7"
private const val WAIT_SECONDS = 30L
private val ADMIN = Actor.User(UUID.randomUUID())
private val KEY_CACHE_TTL: Duration = Duration.ofMinutes(1)
private val TTL: Duration = Duration.ofHours(72)

private class RecordingAudit : AuditLog {
    val changes = CopyOnWriteArrayList<AuditRecord>()

    @Volatile var failing = false

    @Volatile var onChange: () -> Unit = {}

    override fun recordChange(record: AuditRecord) {
        check(!failing) { "audit is down" }
        onChange()
        changes += record
    }

    override fun recordDenial(record: AuditRecord) = throw UnsupportedOperationException()
}

private class Identity {
    val database = TestDatabase.create()
    val owner = database.owner().also { TenantMigrationRunner(it).migrateAll() }
    val sql = JdbcTemplate(owner)
    val kms = OpenBaoKeyManagementService(TestOpenBao.address, TestOpenBao.TOKEN)
    val runtime = database.pooled("worker_runtime")
    val transaction = TenantTransaction(runtime)
    val keys = KeyHierarchy(kms, transaction, KEY_CACHE_TTL)
    val audit = RecordingAudit()
    val clock = MovableClock()
    val service = IdentityService(transaction, keys, audit, OutboxPublisher(), clock, TTL)

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

    fun TenantContext.invite(
        email: String = EMAIL,
        role: UserRole = USER,
    ): UUID = acting { invite(email, role, ADMIN, IP) }

    fun TenantContext.active(
        email: String = EMAIL,
        role: UserRole = USER,
    ): UUID {
        val user = invite(email, role)
        acting { acceptInvitation(checkNotNull(issueInvitation(user, ADMIN)), Actor.Anonymous) }
        return user
    }

    fun TenantContext.events(): List<Map<String, Any?>> =
        sql.queryForList(
            "SELECT aggregate_type, aggregate_id, event_type, payload::text AS payload FROM platform.outbox WHERE tenant_id = ?",
            tenantId,
        )

    fun TenantContext.rows(name: String): List<Map<String, Any?>> = sql.queryForList("SELECT * FROM ${table(name)}")

    fun <T> TenantContext.inTransaction(block: (DSLContext) -> T): T = TenantContext.with(this) { transaction.execute(block) }

    fun actions(): List<String> = audit.changes.map { it.action }
}

private fun sha256(text: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())

/** Bytes stay comparable to text this way, whatever they hold. */
private fun printed(value: Any?): String =
    when (value) {
        is ByteArray -> String(value, ISO_8859_1)
        else -> value.toString()
    }

class IdentityServiceTest :
    FunSpec({
        context("inviting") {
            test("a new user waits with the given role, a key and a profile") {
                Identity().run {
                    val tenant = tenant()

                    val user = tenant.invite(role = TENANT_ADMIN)

                    val view = tenant.acting { user(user) }.shouldNotBeNull()
                    view.role shouldBe TENANT_ADMIN
                    view.status shouldBe UserStatus.INVITED
                    tenant.acting { emailOf(user) } shouldBe EMAIL
                    tenant
                        .acting { profile(user) }
                        .shouldNotBeNull()
                        .name
                        .shouldBeNull()
                }
            }

            test("the event carries only the ID") {
                Identity().run {
                    val tenant = tenant()

                    val user = tenant.invite()

                    val event = tenant.events().single()
                    event["event_type"] shouldBe IdentityEvents.INVITED
                    event["aggregate_type"] shouldBe IdentityEvents.AGGREGATE
                    event["aggregate_id"] shouldBe user
                    event["payload"].toString() shouldNotContain EMAIL
                }
            }

            test("an invalid address is refused and leaves nothing behind") {
                Identity().run {
                    val tenant = tenant()

                    shouldThrow<InvalidEmail> { tenant.invite("not an address") }

                    tenant.rows("users").shouldBeEmpty()
                    tenant.rows("user_keys").shouldBeEmpty()
                }
            }

            test("a failed audit record rolls the user and its event back") {
                Identity().run {
                    val tenant = tenant()
                    audit.failing = true

                    shouldThrow<IllegalStateException> { tenant.invite() }

                    tenant.rows("users").shouldBeEmpty()
                    tenant.rows("user_keys").shouldBeEmpty()
                    tenant.events().shouldBeEmpty()
                }
            }
        }

        context("the first admin") {
            test("is invited as a tenant admin by the system") {
                Identity().run {
                    val tenant = tenant()

                    val user = tenant.acting { inviteFirstAdmin(EMAIL) }

                    tenant.acting { user(user) }.shouldNotBeNull().role shouldBe TENANT_ADMIN
                    audit.changes.single().actor shouldBe Actor.System
                }
            }

            test("a repeat finds the same user and sends nothing new") {
                Identity().run {
                    val tenant = tenant()
                    val first = tenant.acting { inviteFirstAdmin(EMAIL) }

                    val second = tenant.acting { inviteFirstAdmin(EMAIL) }

                    second shouldBe first
                    tenant.rows("users") shouldHaveSize 1
                    tenant.events() shouldHaveSize 1
                }
            }
        }

        context("email uniqueness") {
            test("a second user with the same address is refused") {
                Identity().run {
                    val tenant = tenant()
                    tenant.invite()

                    shouldThrow<EmailTaken> { tenant.invite() }

                    tenant.rows("users") shouldHaveSize 1
                    tenant.rows("user_keys") shouldHaveSize 1
                }
            }

            test("another spelling of the address is the same user") {
                Identity().run {
                    val tenant = tenant()
                    tenant.invite()

                    shouldThrow<EmailTaken> { tenant.invite(" ADA.Lovelace@ACME.test ") }
                    shouldThrow<EmailTaken> { tenant.invite("ａｄａ.lovelace@acme.test") }
                }
            }

            test("the refusal does not repeat the address") {
                Identity().run {
                    val tenant = tenant()
                    tenant.invite()

                    shouldThrow<EmailTaken> { tenant.invite() }.message.shouldNotBeNull() shouldNotContain "lovelace"
                }
            }

            test("two concurrent invitations leave one user") {
                Identity().run {
                    val tenant = tenant()
                    val barrier = CyclicBarrier(2)

                    val outcomes =
                        List(2) {
                            CompletableFuture.supplyAsync {
                                barrier.await(WAIT_SECONDS, TimeUnit.SECONDS)
                                runCatching { tenant.invite() }
                            }
                        }.map { it.get(WAIT_SECONDS, TimeUnit.SECONDS) }

                    outcomes.count { it.isSuccess } shouldBe 1
                    tenant.rows("users") shouldHaveSize 1
                }
            }

            test("the same address in two tenants is two users with unrelated indexes") {
                Identity().run {
                    val first = tenant()
                    val second = tenant()

                    first.invite()
                    second.invite()

                    printed(first.rows("users").single()["email_index"]) shouldNotBe
                        printed(second.rows("users").single()["email_index"])
                }
            }

            test("the blind index finds a user by the exact address") {
                Identity().run {
                    val tenant = tenant()
                    val user = tenant.invite()

                    tenant.acting { findByEmail("Ada.Lovelace@acme.test") }.shouldNotBeNull().id shouldBe user
                    tenant.acting { findByEmail("ada@acme.test") }.shouldBeNull()
                }
            }
        }

        context("invitations") {
            test("the row stores only the hash of the token") {
                Identity().run {
                    val tenant = tenant()
                    val user = tenant.invite()

                    val token = tenant.acting { issueInvitation(user, Actor.System) }.shouldNotBeNull()

                    val row = tenant.rows("invitations").single()
                    row["token_hash"] shouldBe sha256(token)
                    row.values.map(::printed).forEach { it shouldNotContain token }
                }
            }

            test("accepting activates the user") {
                Identity().run {
                    val tenant = tenant()
                    val user = tenant.invite()
                    val token = tenant.acting { issueInvitation(user, Actor.System) }.shouldNotBeNull()

                    tenant.acting { acceptInvitation(token, Actor.Anonymous, IP) } shouldBe user

                    tenant.acting { user(user) }.shouldNotBeNull().status shouldBe UserStatus.ACTIVE
                }
            }

            test("an invitation works once") {
                Identity().run {
                    val tenant = tenant()
                    val user = tenant.invite()
                    val token = tenant.acting { issueInvitation(user, Actor.System) }.shouldNotBeNull()
                    tenant.acting { acceptInvitation(token, Actor.Anonymous) }

                    shouldThrow<InvitationNotValid> { tenant.acting { acceptInvitation(token, Actor.Anonymous) } }

                    tenant.rows("invitations").shouldBeEmpty()
                }
            }

            test("two concurrent accepts of one token give one success") {
                Identity().run {
                    val tenant = tenant()
                    val user = tenant.invite()
                    val token = tenant.acting { issueInvitation(user, Actor.System) }.shouldNotBeNull()
                    val barrier = CyclicBarrier(2)

                    val outcomes =
                        List(2) {
                            CompletableFuture.supplyAsync {
                                barrier.await(WAIT_SECONDS, TimeUnit.SECONDS)
                                runCatching { tenant.acting { acceptInvitation(token, Actor.Anonymous) } }
                            }
                        }.map { it.get(WAIT_SECONDS, TimeUnit.SECONDS) }

                    outcomes.count { it.isSuccess } shouldBe 1
                }
            }

            test("an invitation expires") {
                Identity().run {
                    val tenant = tenant()
                    val user = tenant.invite()
                    val token = tenant.acting { issueInvitation(user, Actor.System) }.shouldNotBeNull()
                    clock.offset = TTL.plusSeconds(1)

                    shouldThrow<InvitationNotValid> { tenant.acting { acceptInvitation(token, Actor.Anonymous) } }

                    tenant.acting { user(user) }.shouldNotBeNull().status shouldBe UserStatus.INVITED
                }
            }

            test("a second invitation cancels the first") {
                Identity().run {
                    val tenant = tenant()
                    val user = tenant.invite()
                    val first = tenant.acting { issueInvitation(user, Actor.System) }.shouldNotBeNull()
                    val second = tenant.acting { issueInvitation(user, Actor.System) }.shouldNotBeNull()

                    shouldThrow<InvitationNotValid> { tenant.acting { acceptInvitation(first, Actor.Anonymous) } }

                    tenant.acting { acceptInvitation(second, Actor.Anonymous) } shouldBe user
                }
            }

            test("an unknown token is refused the same way as a spent one") {
                Identity().run {
                    val tenant = tenant()

                    shouldThrow<InvitationNotValid> { tenant.acting { acceptInvitation("made-up", Actor.Anonymous) } }
                }
            }

            test("a token of one tenant means nothing in another") {
                Identity().run {
                    val first = tenant()
                    val second = tenant()
                    val user = first.invite()
                    val token = first.acting { issueInvitation(user, Actor.System) }.shouldNotBeNull()

                    shouldThrow<InvitationNotValid> { second.acting { acceptInvitation(token, Actor.Anonymous) } }
                }
            }

            test("a user who already accepted gets no new link") {
                Identity().run {
                    val tenant = tenant()
                    val user = tenant.active()

                    tenant.acting { issueInvitation(user, Actor.System) }.shouldBeNull()

                    tenant.rows("invitations").shouldBeEmpty()
                }
            }

            test("a deleted user gets no link") {
                Identity().run {
                    val tenant = tenant()

                    tenant.acting { issueInvitation(UUID.randomUUID(), Actor.System) }.shouldBeNull()
                }
            }
        }

        context("the invitation cleanup") {
            test("removes expired rows and keeps live ones and their users") {
                Identity().run {
                    val tenant = tenant()
                    val expired = tenant.invite("ada@acme.test")
                    tenant.acting { issueInvitation(expired, Actor.System) }
                    clock.offset = TTL.plusSeconds(1)
                    val live = tenant.invite("grace@acme.test")
                    tenant.acting { issueInvitation(live, Actor.System) }

                    tenant.inTransaction { service.invitationCleanup.delete(it, clock.instant().atOffset(ZoneOffset.UTC)) }

                    tenant.rows("invitations").map { it["user_id"] }.shouldContainExactly(live)
                    tenant.rows("users") shouldHaveSize 2
                }
            }

            test("has a stable name, which is its lock") {
                Identity().run {
                    val cleanup: TenantCleanup = service.invitationCleanup

                    cleanup.name shouldBe "invitations"
                }
            }
        }

        context("the last admin") {
            test("cannot be blocked, demoted or erased") {
                Identity().run {
                    val tenant = tenant()
                    val admin = tenant.active(role = TENANT_ADMIN)

                    shouldThrow<LastAdminRequired> { tenant.acting { block(admin, ADMIN) } }
                    shouldThrow<LastAdminRequired> { tenant.acting { changeRole(admin, USER, ADMIN) } }
                    shouldThrow<LastAdminRequired> { tenant.acting { eraseUser(admin, ADMIN) } }

                    val view = tenant.acting { user(admin) }.shouldNotBeNull()
                    view.status shouldBe UserStatus.ACTIVE
                    view.role shouldBe TENANT_ADMIN
                }
            }

            test("two admins can lose one of them") {
                Identity().run {
                    val tenant = tenant()
                    val first = tenant.active("ada@acme.test", TENANT_ADMIN)
                    val second = tenant.active("grace@acme.test", TENANT_ADMIN)

                    tenant.acting { block(first, ADMIN) }

                    shouldThrow<LastAdminRequired> { tenant.acting { block(second, ADMIN) } }
                }
            }

            test("an invited admin does not stand in for an active one") {
                Identity().run {
                    val tenant = tenant()
                    val active = tenant.active("ada@acme.test", TENANT_ADMIN)
                    tenant.invite("grace@acme.test", TENANT_ADMIN)

                    shouldThrow<LastAdminRequired> { tenant.acting { block(active, ADMIN) } }
                }
            }

            test("two admins blocking each other at once leave one") {
                Identity().run {
                    val tenant = tenant()
                    val admins = listOf(tenant.active("ada@acme.test", TENANT_ADMIN), tenant.active("grace@acme.test", TENANT_ADMIN))
                    // The audit record runs after the update and before the commit. Both reach it only if nothing locks them apart.
                    val bothUpdated = CyclicBarrier(2)
                    audit.onChange = { runCatching { bothUpdated.await(1, TimeUnit.SECONDS) } }

                    val outcomes =
                        admins
                            .map { admin -> CompletableFuture.supplyAsync { runCatching { tenant.acting { block(admin, ADMIN) } } } }
                            .map { it.get(WAIT_SECONDS, TimeUnit.SECONDS) }

                    outcomes.count { it.isSuccess } shouldBe 1
                    outcomes.mapNotNull { it.exceptionOrNull() }.single().shouldBeInstanceOf<LastAdminRequired>()
                }
            }
        }

        context("blocking") {
            test("a blocked user can return") {
                Identity().run {
                    val tenant = tenant()
                    val user = tenant.active()

                    tenant.acting { block(user, ADMIN) }
                    tenant.acting { user(user) }.shouldNotBeNull().status shouldBe UserStatus.BLOCKED

                    tenant.acting { unblock(user, ADMIN) }
                    tenant.acting { user(user) }.shouldNotBeNull().status shouldBe UserStatus.ACTIVE
                }
            }

            test("a missing user answers as not found") {
                Identity().run {
                    val tenant = tenant()

                    shouldThrow<UserNotFound> { tenant.acting { block(UUID.randomUUID(), ADMIN) } }
                }
            }

            test("a broken status rule keeps the user as it was") {
                Identity().run {
                    val tenant = tenant()
                    val user = tenant.invite()

                    shouldThrow<ForbiddenUserChange> { tenant.acting { block(user, ADMIN) } }

                    tenant.acting { user(user) }.shouldNotBeNull().status shouldBe UserStatus.INVITED
                }
            }
        }

        context("erasing") {
            test("takes the user, the key, the profile and the invitation, and the address is free again") {
                Identity().run {
                    val tenant = tenant()
                    val user = tenant.invite()
                    tenant.acting { issueInvitation(user, Actor.System) }

                    tenant.acting { eraseUser(user, ADMIN) }

                    listOf("users", "user_keys", "user_profiles", "invitations").forEach { tenant.rows(it).shouldBeEmpty() }
                    tenant.acting { emailOf(user) }.shouldBeNull()
                    tenant.invite() shouldNotBe user
                }
            }
        }

        context("aggregates") {
            test("editing the profile and changing the status do not conflict") {
                Identity().run {
                    val tenant = tenant()
                    val user = tenant.active()
                    val rows = UserProfiles()
                    val staleProfile = tenant.inTransaction { rows.find(it, user) }.shouldNotBeNull()

                    tenant.acting { block(user, ADMIN) }

                    staleProfile.update(byteArrayOf(1), byteArrayOf(2))
                    tenant.inTransaction { rows.save(it, staleProfile) } shouldBe staleProfile.version + 1
                    tenant.acting { user(user) }.shouldNotBeNull().status shouldBe UserStatus.BLOCKED
                }
            }

            test("each aggregate still conflicts with itself") {
                Identity().run {
                    val tenant = tenant()
                    val user = tenant.active()
                    val rows = Users()
                    val stale = tenant.inTransaction { rows.find(it, user) }.shouldNotBeNull()

                    tenant.acting { block(user, ADMIN) }

                    stale.block(otherActiveAdmins = 1)
                    shouldThrow<VersionConflictException> { tenant.inTransaction { rows.save(it, stale) } }
                }
            }

            test("the profile refers to the user only by ID") {
                UserProfile::class.java.declaredFields.map { it.type } shouldNotContain User::class.java
            }
        }

        context("the profile") {
            test("reads back what was stored") {
                Identity().run {
                    val tenant = tenant()
                    val user = tenant.active()

                    tenant.acting { updateProfile(user, ProfileChange(NAME, PHONE), Actor.User(user), IP) }

                    val profile = tenant.acting { profile(user) }.shouldNotBeNull()
                    profile.name shouldBe NAME
                    profile.phone shouldBe PHONE
                }
            }

            test("toString of the view prints no personal data") {
                Identity().run {
                    val tenant = tenant()
                    val user = tenant.active()
                    tenant.acting { updateProfile(user, ProfileChange(NAME, PHONE), Actor.User(user)) }

                    val printed = tenant.acting { profile(user) }.toString()

                    printed shouldNotContain NAME
                    printed shouldNotContain PHONE
                }
            }
        }

        context("data at rest") {
            test("no table holds personal data or a token in the open") {
                Identity().run {
                    val tenant = tenant()
                    val user = tenant.invite()
                    tenant.acting { updateProfile(user, ProfileChange(NAME, PHONE), ADMIN) }
                    val token = tenant.acting { issueInvitation(user, Actor.System) }.shouldNotBeNull()

                    val stored = listOf("users", "user_profiles", "invitations").flatMap { tenant.rows(it) }.flatMap { it.values }

                    stored.shouldNotBeEmpty()
                    listOf(EMAIL, "lovelace", NAME, PHONE, token).forEach { secret ->
                        stored.map(::printed).forEach { it shouldNotContain secret }
                    }
                    tenant.events().map(::printed).forEach { it shouldNotContain EMAIL }
                }
            }

            test("the ciphertext of one user does not open under another") {
                Identity().run {
                    val tenant = tenant()
                    val first = tenant.invite("ada@acme.test")
                    val second = tenant.invite("grace@acme.test")
                    val stolen =
                        sql.queryForObject(
                            "SELECT email_ciphertext FROM ${tenant.table("users")} WHERE id = ?",
                            ByteArray::class.java,
                            first,
                        )

                    sql.update("UPDATE ${tenant.table("users")} SET email_ciphertext = ? WHERE id = ?", stolen, second)

                    shouldThrow<FieldDecryptionException> { tenant.acting { emailOf(second) } }
                }
            }

            test("a key without a user cannot commit") {
                Identity().run {
                    val tenant = tenant()

                    shouldThrow<DataAccessException> {
                        sql.update("INSERT INTO ${tenant.table("user_keys")} (user_id, keyset) VALUES (?, '\\x00')", UUID.randomUUID())
                    }
                }
            }
        }

        context("the audit") {
            test("records the creation, a block, a return and a role change") {
                Identity().run {
                    val tenant = tenant()
                    val user = tenant.active()
                    audit.changes.clear()

                    tenant.acting { block(user, ADMIN, IP) }
                    tenant.acting { unblock(user, ADMIN, IP) }
                    tenant.acting { changeRole(user, TENANT_ADMIN, ADMIN, IP) }

                    actions().shouldContainExactly("user.blocked", "user.unblocked", "user.role_changed")
                    audit.changes.forEach {
                        it.actor shouldBe ADMIN
                        it.target shouldBe AuditTarget.user(user)
                        it.ip shouldBe IP
                    }
                    audit.changes.last().details shouldBe mapOf("from" to "USER", "to" to "TENANT_ADMIN")
                }
            }

            test("the creation names the actor, the target and the role, never the address") {
                Identity().run {
                    val tenant = tenant()

                    val user = tenant.invite(role = TENANT_ADMIN)

                    val record = audit.changes.single()
                    record.action shouldBe "user.created"
                    record.actor shouldBe ADMIN
                    record.target shouldBe AuditTarget.user(user)
                    record.ip shouldBe IP
                    record.details shouldBe mapOf("role" to "TENANT_ADMIN")
                }
            }

            test("issuing, accepting, editing and erasing are recorded") {
                Identity().run {
                    val tenant = tenant()
                    val user = tenant.invite()
                    val token = tenant.acting { issueInvitation(user, Actor.System) }.shouldNotBeNull()
                    tenant.acting { acceptInvitation(token, Actor.Anonymous, IP) }
                    tenant.acting { updateProfile(user, ProfileChange(NAME, null), Actor.User(user)) }
                    tenant.acting { eraseUser(user, ADMIN) }

                    actions().shouldContainExactly(
                        "user.created",
                        "user.invitation_issued",
                        "user.invitation_accepted",
                        "user.profile_updated",
                        "user.erased",
                    )
                    audit.changes.flatMap { it.details.values }.forEach { value ->
                        listOf(EMAIL, NAME).forEach { value shouldNotContain it }
                    }
                }
            }

            test("an unchanged role writes nothing") {
                Identity().run {
                    val tenant = tenant()
                    val user = tenant.active()
                    audit.changes.clear()

                    tenant.acting { changeRole(user, USER, ADMIN) }

                    audit.changes.shouldBeEmpty()
                }
            }

            test("a refused change writes nothing") {
                Identity().run {
                    val tenant = tenant()
                    val admin = tenant.active(role = TENANT_ADMIN)
                    audit.changes.clear()

                    shouldThrow<LastAdminRequired> { tenant.acting { block(admin, ADMIN) } }

                    audit.changes.shouldBeEmpty()
                }
            }

            test("a change of state fails with its audit record") {
                Identity().run {
                    val tenant = tenant()
                    val user = tenant.active()
                    audit.failing = true

                    shouldThrow<IllegalStateException> { tenant.acting { block(user, ADMIN) } }

                    audit.failing = false
                    tenant.acting { user(user) }.shouldNotBeNull().status shouldBe UserStatus.ACTIVE
                }
            }
        }
    })
