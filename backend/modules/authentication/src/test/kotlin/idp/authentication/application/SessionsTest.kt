package idp.authentication.application

import idp.authentication.adapters.persistence.TenantPolicies
import idp.authentication.domain.AuthFactor
import idp.authentication.domain.FlowExpired
import idp.authentication.domain.FlowStep
import idp.authentication.domain.StepNotAllowed
import idp.authentication.domain.TenantPolicy
import idp.identity.application.IdentityService
import idp.identity.application.PasskeyService
import idp.identity.application.TotpService
import idp.sharedkernel.audit.Actor
import idp.sharedkernel.audit.AuditLog
import idp.sharedkernel.audit.AuditRecord
import idp.sharedkernel.audit.AuditTarget
import idp.sharedkernel.crypto.DerivedKey
import idp.sharedkernel.crypto.FieldContext
import idp.sharedkernel.crypto.FieldDecryptionException
import idp.sharedkernel.crypto.KeyHierarchy
import idp.sharedkernel.crypto.adapter.OpenBaoKeyManagementService
import idp.sharedkernel.migration.TenantMigrationRunner
import idp.sharedkernel.migration.TenantSchema
import idp.sharedkernel.migration.TenantSchemaFactory
import idp.sharedkernel.outbox.OutboxPublisher
import idp.sharedkernel.persistence.VersionConflictException
import idp.sharedkernel.security.Principal
import idp.sharedkernel.security.Role
import idp.sharedkernel.security.UserRole
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
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.springframework.jdbc.core.JdbcTemplate
import java.security.MessageDigest
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

private const val EMAIL = "ada.lovelace@acme.test"
private const val IP = "198.51.100.7"
private const val USER_AGENT = "Mozilla/5.0 (Macintosh)"
private const val RETURN_URL = "/saml/resume/7f3c"
private const val MAX_SESSIONS = 3
private val ADMIN = Actor.User(UUID.randomUUID())
private val KEY_CACHE_TTL: Duration = Duration.ofMinutes(1)
private val LAST_SEEN_INTERVAL: Duration = Duration.ofMinutes(1)
private val FLOW_TTL: Duration = Duration.ofMinutes(15)
private val RELAXED = TenantPolicy(mfaForUsers = false)

private class SessionAudit : AuditLog {
    val changes = CopyOnWriteArrayList<AuditRecord>()

    override fun recordChange(record: AuditRecord) {
        changes += record
    }

    override fun recordDenial(record: AuditRecord) = throw UnsupportedOperationException()
}

private class SessionsFixture {
    val clock = MovableClock()
    val database = TestDatabase.create()
    val owner = database.owner().also { TenantMigrationRunner(it).migrateAll() }
    val sql = JdbcTemplate(owner)
    val kms = OpenBaoKeyManagementService(TestOpenBao.address, TestOpenBao.TOKEN)
    val transaction = TenantTransaction(database.pooled("app_runtime"))
    val keys = KeyHierarchy(kms, transaction, KEY_CACHE_TTL)
    val audit = SessionAudit()
    val identity = IdentityService(transaction, keys, audit, OutboxPublisher(), clock)
    val policies = TenantPolicies()
    val service =
        AuthenticationService(
            transaction,
            keys,
            audit,
            identity,
            TotpService(transaction, keys, audit, clock),
            PasskeyService(transaction, audit, clock),
            clock,
            FLOW_TTL,
            LAST_SEEN_INTERVAL,
            MAX_SESSIONS,
        )

    fun newTenant(): TenantContext {
        val id = UUID.randomUUID()
        sql.update("INSERT INTO platform.tenants (id, name, status) VALUES (?, 'tenant', 'ACTIVE')", id)
        TenantSchemaFactory(owner).create(id)
        TenantMigrationRunner(owner).migrateTenant(id)
        val context = TenantContext(id, kms.createKey("tenant-$id"))
        TenantContext.with(context) { keys.createTenantKey() }
        return context
    }

    /** Relaxed, so that a password alone completes a flow and each test adds only the factors it is about. */
    fun tenant(policy: TenantPolicy = RELAXED): TenantContext = newTenant().also { it.setPolicy(policy) }

    fun TenantContext.setPolicy(policy: TenantPolicy) {
        TenantContext.with(this) {
            transaction.execute { dsl ->
                val version = policies.load(dsl).version
                policies.save(
                    dsl,
                    TenantPolicy(
                        policy.mfaForUsers,
                        policy.passkeyRequired,
                        policy.sessionIdleTimeout,
                        policy.sessionAbsoluteTimeout,
                        version,
                    ),
                )
            }
        }
    }

    fun <T> TenantContext.acting(block: AuthenticationService.() -> T): T = TenantContext.with(this) { service.block() }

    fun TenantContext.user(
        email: String = EMAIL,
        role: UserRole = UserRole.USER,
    ): UUID =
        TenantContext.with(this) {
            val id = identity.invite(email, role, ADMIN)
            identity.acceptInvitation(identity.issueInvitation(id, ADMIN).shouldNotBeNull(), Actor.User(id))
            audit.changes.clear()
            id
        }

    fun TenantContext.passwordFlow(
        email: String = EMAIL,
        continuation: FlowContinuation? = null,
        session: String? = null,
    ): UUID =
        acting {
            startFlow(continuation, session).also { flow ->
                identify(flow, email)
                onFlow(flow) { current, now -> current.passwordPassed(now) }
            }
        }

    fun TenantContext.login(
        email: String = EMAIL,
        continuation: FlowContinuation? = null,
        session: String? = null,
    ): String = completed(passwordFlow(email, continuation, session))

    fun TenantContext.completed(flow: UUID): String = acting { completeFlow(flow, IP, USER_AGENT) }.session.shouldNotBeNull().token

    /** Takes over the session and passes the TOTP it lacks, so the user needs a confirmed one first. */
    fun TenantContext.steppedUp(token: String): UUID =
        acting { startFlow(sessionToken = token).also { flow -> onFlow(flow) { current, now -> current.totpPassed(now) } } }

    fun TenantContext.table(name: String) = "\"${TenantSchema(tenantId).name}\".$name"

    fun TenantContext.confirmTotp(user: UUID) {
        val credential = "INSERT INTO ${table("totp_credentials")} (user_id, secret_ciphertext, confirmed_at) VALUES (?, ?, now())"
        sql.update(credential, user, ByteArray(1))
    }

    fun TenantContext.sessionRow(token: String): Map<String, Any?> =
        sql.queryForMap("SELECT * FROM ${table("sso_sessions")} WHERE token_hash = ?", sha256(token))

    fun TenantContext.sessionRows(user: UUID): List<Map<String, Any?>> =
        sql.queryForList("SELECT * FROM ${table("sso_sessions")} WHERE user_id = ?", user)

    fun TenantContext.flowRow(flow: UUID): Map<String, Any?> = sql.queryForMap("SELECT * FROM ${table("flows")} WHERE id = ?", flow)

    fun TenantContext.flowCount(): Int = sql.queryForObject("SELECT count(*) FROM ${table("flows")}", Int::class.java)!!

    fun TenantContext.liveFor(token: String): Boolean = acting { resolveSession(token) } != null

    fun advance(duration: Duration) {
        clock.offset = clock.offset.plus(duration)
    }
}

private fun sha256(token: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(token.toByteArray())

private fun Any?.asInstant(): Instant =
    when (this) {
        is Timestamp -> toInstant()
        is java.time.OffsetDateTime -> toInstant()
        else -> error("Not a timestamp: $this")
    }

@Suppress("UNCHECKED_CAST")
private fun Any?.asStrings(): List<String> = ((this as java.sql.Array).array as Array<String>).toList()

class SessionsTest :
    FunSpec({
        val fixture = SessionsFixture()

        afterTest { fixture.clock.offset = Duration.ZERO }

        with(fixture) {
            context("a new tenant") {
                test("gets the default policy") {
                    val tenant = newTenant()

                    val policy = TenantContext.with(tenant) { transaction.execute { policies.load(it) } }

                    policy.mfaForUsers shouldBe true
                    policy.passkeyRequired shouldBe false
                    policy.sessionIdleTimeout shouldBe TenantPolicy().sessionIdleTimeout
                    policy.sessionAbsoluteTimeout shouldBe TenantPolicy().sessionAbsoluteTimeout
                    policy.version shouldBe 0
                }

                test("logs in under MFA before anyone sets a policy") {
                    val tenant = newTenant()
                    tenant.user()

                    tenant.acting { onFlow(tenant.passwordFlow()) { current, now -> current.step(now) } } shouldBe
                        FlowStep.EnrollMfa(setOf(AuthFactor.TOTP, AuthFactor.PASSKEY))
                }
            }

            context("a stored policy") {
                test("comes back as saved, with a new version") {
                    val tenant = tenant()
                    val stored =
                        TenantPolicy(
                            mfaForUsers = false,
                            passkeyRequired = true,
                            sessionIdleTimeout = Duration.ofMinutes(5),
                            sessionAbsoluteTimeout = Duration.ofHours(1),
                        )

                    tenant.setPolicy(stored)

                    val policy = TenantContext.with(tenant) { transaction.execute { policies.load(it) } }
                    policy.mfaForUsers shouldBe false
                    policy.passkeyRequired shouldBe true
                    policy.sessionIdleTimeout shouldBe Duration.ofMinutes(5)
                    policy.sessionAbsoluteTimeout shouldBe Duration.ofHours(1)
                    policy.version shouldBe 2
                }

                test("loses to a newer one") {
                    val tenant = tenant()

                    shouldThrow<VersionConflictException> {
                        TenantContext.with(tenant) { transaction.execute { policies.save(it, TenantPolicy(version = 0)) } }
                    }
                }
            }

            context("a flow") {
                test("is stored with what the caller demands") {
                    val tenant = tenant()
                    val continuation = FlowContinuation(RETURN_URL, requiresPasskey = true, requiresFreshLogin = true)

                    val flow = tenant.acting { startFlow(continuation) }

                    val row = tenant.flowRow(flow)
                    row["user_id"].shouldBeNull()
                    row["login_ciphertext"].shouldBeNull()
                    row["proved"].asStrings().shouldBeEmpty()
                    row["required_assurance"] shouldBe "PASSKEY"
                    row["fresh_login"] shouldBe true
                    row["return_url"] shouldBe RETURN_URL
                    row["version"] shouldBe 0L
                }

                test("expires after the TTL of the service") {
                    val tenant = tenant()
                    val before = clock.instant().truncatedTo(ChronoUnit.MILLIS)

                    val flow = tenant.acting { startFlow() }

                    val after = clock.instant()
                    val expiresAt = tenant.flowRow(flow)["expires_at"].asInstant()
                    (expiresAt >= before.plus(FLOW_TTL) && expiresAt <= after.plus(FLOW_TTL)) shouldBe true
                }

                test("keeps the user and the proved factors between steps") {
                    val tenant = tenant()
                    val user = tenant.user()

                    val flow = tenant.passwordFlow()

                    val row = tenant.flowRow(flow)
                    row["user_id"] shouldBe user
                    row["proved"].asStrings().shouldContainExactly(AuthFactor.PASSWORD.name)
                    row["version"] shouldBe 2L
                }

                test("keeps a login that matched nobody, and such a login never gets past the password") {
                    val tenant = tenant()
                    val flow = tenant.acting { startFlow().also { identify(it, "nobody@acme.test") } }

                    tenant.flowRow(flow)["user_id"].shouldBeNull()
                    tenant.flowRow(flow)["login_ciphertext"].shouldNotBeNull()
                    tenant.acting { onFlow(flow) { current, now -> current.step(now) } } shouldBe FlowStep.Password
                    shouldThrow<StepNotAllowed> { tenant.acting { onFlow(flow) { current, now -> current.passwordPassed(now) } } }
                }

                test("stores the login so that only this flow reads it") {
                    val tenant = tenant()
                    val flow = tenant.acting { startFlow().also { identify(it, EMAIL) } }
                    val sealed = tenant.flowRow(flow)["login_ciphertext"] as ByteArray

                    String(sealed, Charsets.ISO_8859_1).contains(EMAIL) shouldBe false

                    val cipher = TenantContext.with(tenant) { keys.derivedCipher(DerivedKey.FLOW) }
                    cipher.decrypt(sealed, FieldContext("flows.login_ciphertext", flow)).decodeToString() shouldBe EMAIL
                    val elsewhere = FieldContext("flows.login_ciphertext", UUID.randomUUID())
                    shouldThrow<FieldDecryptionException> { cipher.decrypt(sealed, elsewhere) }
                }

                test("loses to a parallel step") {
                    val tenant = tenant()
                    tenant.user()
                    val flow = tenant.acting { startFlow().also { identify(it, EMAIL) } }

                    shouldThrow<VersionConflictException> {
                        tenant.acting {
                            onFlow(flow) { current, now ->
                                sql.update("UPDATE ${tenant.table("flows")} SET version = version + 1 WHERE id = ?", flow)
                                current.passwordPassed(now)
                            }
                        }
                    }
                }

                test("reads a stricter policy on every load") {
                    val tenant = tenant()
                    tenant.user()
                    val flow = tenant.passwordFlow()
                    tenant.acting { onFlow(flow) { current, now -> current.step(now) } } shouldBe FlowStep.Complete

                    tenant.setPolicy(TenantPolicy())

                    tenant.acting { onFlow(flow) { current, now -> current.step(now) } } shouldBe
                        FlowStep.EnrollMfa(setOf(AuthFactor.TOTP, AuthFactor.PASSKEY))
                    shouldThrow<StepNotAllowed> { tenant.acting { completeFlow(flow, IP, USER_AGENT) } }
                }

                test("reads a new factor on every load") {
                    val tenant = tenant()
                    val user = tenant.user()
                    val flow = tenant.passwordFlow()

                    tenant.confirmTotp(user)

                    tenant.acting { onFlow(flow) { current, now -> current.step(now) } } shouldBe
                        FlowStep.SecondFactor(setOf(AuthFactor.TOTP, AuthFactor.RECOVERY_CODE))
                }

                test("reads a new role on every load") {
                    val tenant = tenant()
                    val user = tenant.user()
                    val flow = tenant.passwordFlow()

                    TenantContext.with(tenant) { identity.changeRole(user, UserRole.TENANT_ADMIN, ADMIN) }

                    tenant.acting { onFlow(flow) { current, now -> current.step(now) } } shouldBe
                        FlowStep.EnrollMfa(setOf(AuthFactor.TOTP, AuthFactor.PASSKEY))
                }

                test("gives a late caller the same answer as an unknown one") {
                    val tenant = tenant()
                    tenant.user()
                    val flow = tenant.passwordFlow()

                    advance(FLOW_TTL)

                    shouldThrow<FlowExpired> { tenant.acting { completeFlow(flow, IP, USER_AGENT) } }
                    shouldThrow<FlowExpired> { tenant.acting { completeFlow(UUID.randomUUID(), IP, USER_AGENT) } }
                }

                test("opens a session only when complete") {
                    val tenant = tenant()
                    tenant.user()
                    val flow = tenant.acting { startFlow().also { identify(it, EMAIL) } }

                    shouldThrow<StepNotAllowed> { tenant.acting { completeFlow(flow, IP, USER_AGENT) } }
                }

                test("opens one session and is gone") {
                    val tenant = tenant()
                    val user = tenant.user()
                    val flow = tenant.passwordFlow()

                    tenant.acting { completeFlow(flow, IP, USER_AGENT) }

                    shouldThrow<FlowExpired> { tenant.acting { completeFlow(flow, IP, USER_AGENT) } }
                    tenant.sessionRows(user) shouldHaveSize 1
                    tenant.flowCount() shouldBe 0
                }

                test("returns the opaque URL of the caller") {
                    val tenant = tenant()
                    tenant.user()
                    val flow = tenant.passwordFlow(continuation = FlowContinuation(RETURN_URL))

                    tenant.acting { completeFlow(flow, IP, USER_AGENT) }.returnUrl shouldBe RETURN_URL
                }

                test("opens no session for a user blocked meanwhile") {
                    val tenant = tenant()
                    val user = tenant.user()
                    val flow = tenant.passwordFlow()

                    TenantContext.with(tenant) { identity.block(user, ADMIN) }

                    shouldThrow<StepNotAllowed> { tenant.acting { completeFlow(flow, IP, USER_AGENT) } }
                    tenant.sessionRows(user).shouldBeEmpty()
                }
            }

            context("a session") {
                test("is stored only as the SHA-256 of its token") {
                    val tenant = tenant()
                    tenant.user()

                    val token = tenant.login()

                    val row = tenant.sessionRow(token)
                    row["token_hash"] shouldBe sha256(token)
                    row.values.forEach { value ->
                        val text = if (value is ByteArray) String(value, Charsets.ISO_8859_1) else value.toString()
                        text.contains(token) shouldBe false
                    }
                }

                test("keeps the IP and the User-Agent under the key of its user") {
                    val tenant = tenant()
                    val user = tenant.user()

                    val row = tenant.sessionRow(tenant.login())

                    val ip = row["ip_ciphertext"] as ByteArray
                    val userAgent = row["user_agent_ciphertext"] as ByteArray
                    String(ip, Charsets.ISO_8859_1).contains(IP) shouldBe false
                    String(userAgent, Charsets.ISO_8859_1).contains(USER_AGENT) shouldBe false
                    val id = row["id"] as UUID
                    val cipher = TenantContext.with(tenant) { keys.userCipher(user) }
                    cipher.decrypt(ip, FieldContext("sso_sessions.ip_ciphertext", id)).decodeToString() shouldBe IP
                    cipher.decrypt(userAgent, FieldContext("sso_sessions.user_agent_ciphertext", id)).decodeToString() shouldBe USER_AGENT
                }

                test("remembers the proved factors") {
                    val tenant = tenant()
                    tenant.user()

                    tenant.sessionRow(tenant.login())["factors"].asStrings().shouldContainExactly(AuthFactor.PASSWORD.name)
                }

                test("resolves to the user and the role") {
                    val tenant = tenant()
                    val user = tenant.user()

                    tenant.acting { resolveSession(tenant.login()) } shouldBe Principal(Actor.User(user), Role(UserRole.USER.name))
                }

                test("does not resolve from a made-up token") {
                    val tenant = tenant()

                    tenant.liveFor("made-up") shouldBe false
                }

                test("does not resolve in another tenant") {
                    val tenant = tenant()
                    val other = tenant()
                    tenant.user()

                    val token = tenant.login()

                    tenant.liveFor(token) shouldBe true
                    other.liveFor(token) shouldBe false
                }

                test("takes the role from the user on every request") {
                    val tenant = tenant()
                    val user = tenant.user()
                    val token = tenant.login()

                    TenantContext.with(tenant) { identity.changeRole(user, UserRole.TENANT_ADMIN, ADMIN) }

                    tenant.acting { resolveSession(token) } shouldBe Principal(Actor.User(user), Role(UserRole.TENANT_ADMIN.name))
                }

                test("does not resolve for a blocked user") {
                    val tenant = tenant()
                    val user = tenant.user()
                    val token = tenant.login()

                    TenantContext.with(tenant) { identity.block(user, ADMIN) }

                    tenant.liveFor(token) shouldBe false
                }

                test("is audited when it opens") {
                    val tenant = tenant()
                    val user = tenant.user()

                    tenant.login()

                    val record = audit.changes.single { it.action == "session.opened" }
                    record.actor shouldBe Actor.User(user)
                    record.target shouldBe AuditTarget.user(user)
                    record.ip shouldBe IP
                }
            }

            context("the timeouts of the tenant") {
                test("end a session without activity") {
                    val tenant = tenant(TenantPolicy(false, false, Duration.ofMinutes(10), Duration.ofHours(12)))
                    tenant.user()
                    val token = tenant.login()

                    advance(Duration.ofMinutes(9))
                    tenant.liveFor(token) shouldBe true
                    advance(Duration.ofMinutes(9))
                    tenant.liveFor(token) shouldBe true
                    advance(Duration.ofMinutes(10))
                    tenant.liveFor(token) shouldBe false
                }

                test("end a session whatever its activity") {
                    val tenant = tenant(TenantPolicy(false, false, Duration.ofMinutes(10), Duration.ofMinutes(25)))
                    tenant.user()
                    val token = tenant.login()

                    repeat(2) {
                        advance(Duration.ofMinutes(9))
                        tenant.liveFor(token) shouldBe true
                    }
                    advance(Duration.ofMinutes(7))
                    tenant.liveFor(token) shouldBe false
                }

                test("apply to a session that is already open when they tighten") {
                    val tenant = tenant()
                    tenant.user()
                    val token = tenant.login()
                    advance(Duration.ofMinutes(20))
                    tenant.liveFor(token) shouldBe true
                    advance(Duration.ofMinutes(9))

                    tenant.setPolicy(TenantPolicy(false, false, Duration.ofMinutes(5), Duration.ofHours(1)))

                    tenant.liveFor(token) shouldBe false
                }
            }

            context("last seen") {
                test("moves at most once per interval and leaves the version alone") {
                    val tenant = tenant()
                    tenant.user()
                    val token = tenant.login()
                    val opened = tenant.sessionRow(token)["last_seen_at"].asInstant()

                    advance(LAST_SEEN_INTERVAL.minusSeconds(10))
                    tenant.liveFor(token) shouldBe true
                    tenant.sessionRow(token)["last_seen_at"].asInstant() shouldBe opened

                    advance(Duration.ofSeconds(20))
                    tenant.liveFor(token) shouldBe true
                    val moved = tenant.sessionRow(token)["last_seen_at"].asInstant()
                    moved shouldNotBe opened
                    tenant.sessionRow(token)["version"] shouldBe 0L

                    tenant.liveFor(token) shouldBe true
                    tenant.sessionRow(token)["last_seen_at"].asInstant() shouldBe moved
                }
            }

            context("a repeated authentication") {
                test("issues a new token and session ID, and the old token stops working") {
                    val tenant = tenant()
                    val user = tenant.user()
                    val first = tenant.login()
                    val firstId = tenant.sessionRow(first)["id"]

                    val second = tenant.login(continuation = FlowContinuation(RETURN_URL, requiresFreshLogin = true), session = first)

                    second shouldNotBe first
                    tenant.sessionRow(second)["id"] shouldNotBe firstId
                    tenant.liveFor(first) shouldBe false
                    tenant.liveFor(second) shouldBe true
                    tenant.sessionRows(user) shouldHaveSize 2
                }

                test("starts the absolute timeout again only for a fresh login") {
                    val tenant = tenant()
                    tenant.user()
                    val first = tenant.login()
                    val started = tenant.sessionRow(first)["authenticated_at"].asInstant()
                    advance(Duration.ofMinutes(5))

                    val second = tenant.login(continuation = FlowContinuation(RETURN_URL, requiresFreshLogin = true), session = first)

                    tenant.sessionRow(second)["authenticated_at"].asInstant() shouldNotBe started
                }
            }

            context("a flow that continues a session") {
                test("issues a new token when it proves a new factor") {
                    val tenant = tenant()
                    val user = tenant.user()
                    val first = tenant.login()
                    tenant.confirmTotp(user)
                    val flow = tenant.steppedUp(first)

                    val second = tenant.completed(flow)

                    second shouldNotBe first
                    tenant.liveFor(first) shouldBe false
                    tenant.liveFor(second) shouldBe true
                    tenant.sessionRow(second)["factors"].asStrings().toSet() shouldBe setOf(AuthFactor.PASSWORD.name, AuthFactor.TOTP.name)
                }

                test("does not start the absolute timeout again") {
                    val tenant = tenant()
                    val user = tenant.user()
                    val first = tenant.login()
                    val started = tenant.sessionRow(first)["authenticated_at"].asInstant()
                    tenant.confirmTotp(user)
                    advance(Duration.ofMinutes(5))
                    val flow = tenant.steppedUp(first)

                    val second = tenant.completed(flow)

                    tenant.sessionRow(second)["authenticated_at"].asInstant() shouldBe started
                }

                test("leaves the session alone when it proves nothing new") {
                    val tenant = tenant()
                    tenant.user()
                    val token = tenant.login()
                    val first = tenant.acting { startFlow(FlowContinuation(RETURN_URL), token) }
                    val second = tenant.acting { startFlow(FlowContinuation(RETURN_URL), token) }

                    val completed = listOf(first, second).map { flow -> tenant.acting { completeFlow(flow, IP, USER_AGENT) } }

                    completed.forEach { it.session.shouldBeNull() }
                    completed.map { it.returnUrl } shouldBe listOf(RETURN_URL, RETURN_URL)
                    tenant.liveFor(token) shouldBe true
                }

                test("fails when the session was revoked meanwhile") {
                    val tenant = tenant()
                    val user = tenant.user()
                    val token = tenant.login()
                    tenant.confirmTotp(user)
                    val flow = tenant.steppedUp(token)

                    TenantContext.with(tenant) { transaction.execute { service.revokeUserSessions(user, ADMIN) } }

                    shouldThrow<FlowExpired> { tenant.acting { completeFlow(flow, IP, USER_AGENT) } }
                    tenant.sessionRows(user).filter { it["revoked_at"] == null }.shouldBeEmpty()
                }

                test("fails when the session timed out meanwhile") {
                    val tenant = tenant()
                    val user = tenant.user()
                    val token = tenant.login()
                    tenant.confirmTotp(user)
                    advance(Duration.ofMinutes(29))
                    val flow = tenant.steppedUp(token)

                    advance(Duration.ofMinutes(2))

                    shouldThrow<FlowExpired> { tenant.acting { completeFlow(flow, IP, USER_AGENT) } }
                }

                test("fails when the session row is gone") {
                    val tenant = tenant()
                    val user = tenant.user()
                    val token = tenant.login()
                    tenant.confirmTotp(user)
                    val flow = tenant.steppedUp(token)

                    sql.update("DELETE FROM ${tenant.table("sso_sessions")} WHERE user_id = ?", user)

                    shouldThrow<FlowExpired> { tenant.acting { completeFlow(flow, IP, USER_AGENT) } }
                }

                test("of a dead session starts from nothing") {
                    val tenant = tenant()
                    tenant.user()
                    val token = tenant.login()
                    advance(Duration.ofHours(1))

                    val flow = tenant.acting { startFlow(sessionToken = token) }

                    tenant.acting { onFlow(flow) { current, now -> current.step(now) } } shouldBe FlowStep.Identify
                    tenant.flowRow(flow)["session_id"].shouldBeNull()
                }

                test("of a blocked user starts from nothing") {
                    val tenant = tenant()
                    val user = tenant.user()
                    val token = tenant.login()
                    TenantContext.with(tenant) { identity.block(user, ADMIN) }

                    val flow = tenant.acting { startFlow(sessionToken = token) }

                    tenant.acting { onFlow(flow) { current, now -> current.step(now) } } shouldBe FlowStep.Identify
                }
            }

            context("the limit of sessions") {
                test("revokes the longest idle session and audits it") {
                    val tenant = tenant()
                    val user = tenant.user()
                    val tokens =
                        List(MAX_SESSIONS) {
                            advance(Duration.ofSeconds(1))
                            tenant.login()
                        }
                    audit.changes.clear()
                    advance(Duration.ofSeconds(1))

                    val newest = tenant.login()

                    tenant.liveFor(tokens.first()) shouldBe false
                    (tokens.drop(1) + newest).forEach { tenant.liveFor(it) shouldBe true }
                    tenant.sessionRows(user).count { it["revoked_at"] == null } shouldBe MAX_SESSIONS
                    audit.changes.single { it.action == "session.opened" }.details shouldBe mapOf("evicted" to "1")
                }

                test("counts only the sessions of that user") {
                    val tenant = tenant()
                    tenant.user()
                    tenant.user("grace.hopper@acme.test")
                    val other = tenant.login("grace.hopper@acme.test")

                    repeat(MAX_SESSIONS) { tenant.login() }

                    tenant.liveFor(other) shouldBe true
                }
            }

            context("revoking") {
                test("the sessions of a user joins the open transaction") {
                    val tenant = tenant()
                    val user = tenant.user()
                    val token = tenant.login()

                    TenantContext.with(tenant) {
                        transaction.execute {
                            service.revokeUserSessions(user, ADMIN, IP)
                            sql.queryForObject(
                                "SELECT count(*) FROM ${tenant.table("sso_sessions")} WHERE revoked_at IS NOT NULL",
                                Int::class.java,
                            ) shouldBe 0
                        }
                    }

                    tenant.liveFor(token) shouldBe false
                }

                test("the sessions of a user rolls back with the caller") {
                    val tenant = tenant()
                    val user = tenant.user()
                    val token = tenant.login()

                    shouldThrow<IllegalArgumentException> {
                        TenantContext.with(tenant) {
                            transaction.execute {
                                service.revokeUserSessions(user, ADMIN, IP)
                                throw IllegalArgumentException("the caller failed")
                            }
                        }
                    }

                    tenant.liveFor(token) shouldBe true
                }

                test("the sessions of a user refuses to run on its own") {
                    val tenant = tenant()
                    val user = tenant.user()

                    shouldThrow<IllegalStateException> { tenant.acting { revokeUserSessions(user, ADMIN, IP) } }
                }

                test("the sessions of a user leaves other users alone and is audited") {
                    val tenant = tenant()
                    val user = tenant.user()
                    tenant.user("grace.hopper@acme.test")
                    tenant.login()
                    tenant.login()
                    val other = tenant.login("grace.hopper@acme.test")
                    audit.changes.clear()

                    TenantContext.with(tenant) { transaction.execute { service.revokeUserSessions(user, ADMIN, IP) } }

                    tenant.liveFor(other) shouldBe true
                    val record = audit.changes.single()
                    record.action shouldBe "session.revoked_for_user"
                    record.actor shouldBe ADMIN
                    record.target shouldBe AuditTarget.user(user)
                    record.ip shouldBe IP
                    record.details shouldBe mapOf("sessions" to "2")
                }

                test("bumps the version, because it is a decision") {
                    val tenant = tenant()
                    val user = tenant.user()
                    val token = tenant.login()

                    TenantContext.with(tenant) { transaction.execute { service.revokeUserSessions(user, ADMIN) } }

                    tenant.sessionRow(token)["version"] shouldBe 1L
                    tenant.sessionRow(token)["revoked_at"].shouldNotBeNull()
                }

                test("all sessions of a tenant reaches every user and no other tenant") {
                    val tenant = tenant()
                    val other = tenant()
                    tenant.user()
                    tenant.user("grace.hopper@acme.test")
                    other.user()
                    val tokens = listOf(tenant.login(), tenant.login("grace.hopper@acme.test"))
                    val outside = other.login()
                    audit.changes.clear()

                    TenantContext.with(tenant) { transaction.execute { service.revokeAllSessions(Actor.System) } }

                    tokens.forEach { tenant.liveFor(it) shouldBe false }
                    other.liveFor(outside) shouldBe true
                    val record = audit.changes.single()
                    record.action shouldBe "session.revoked_for_tenant"
                    record.actor shouldBe Actor.System
                    record.target shouldBe AuditTarget.tenant(tenant.tenantId)
                    record.details shouldBe mapOf("sessions" to "2")
                }
            }

            context("the cleanup") {
                test("removes flows past their expiry") {
                    val tenant = tenant()
                    val kept = tenant.acting { startFlow() }
                    val expired = tenant.acting { startFlow() }
                    sql.update("UPDATE ${tenant.table("flows")} SET expires_at = now() - interval '1 second' WHERE id = ?", expired)

                    TenantContext.with(tenant) {
                        transaction.execute { service.flowCleanup.delete(it, clock.instant().atOffset(ZoneOffset.UTC)) }
                    }

                    sql.queryForList("SELECT id FROM ${tenant.table("flows")}", UUID::class.java).shouldContainExactly(kept)
                }

                test("removes revoked and timed out sessions and keeps live ones") {
                    val tenant = tenant()
                    val user = tenant.user()
                    tenant.user("grace.hopper@acme.test")
                    tenant.user("alan.turing@acme.test")
                    tenant.login()
                    TenantContext.with(tenant) { transaction.execute { service.revokeUserSessions(user, ADMIN) } }
                    val idle = tenant.login("grace.hopper@acme.test")
                    sql.update(
                        "UPDATE ${tenant.table("sso_sessions")} SET last_seen_at = now() - interval '31 minutes' WHERE token_hash = ?",
                        sha256(idle),
                    )
                    val old = tenant.login("alan.turing@acme.test")
                    sql.update(
                        "UPDATE ${tenant.table("sso_sessions")} SET authenticated_at = now() - interval '13 hours' WHERE token_hash = ?",
                        sha256(old),
                    )
                    tenant.user("edsger.dijkstra@acme.test")
                    val live = tenant.login("edsger.dijkstra@acme.test")

                    TenantContext.with(tenant) {
                        transaction.execute { service.sessionCleanup.delete(it, clock.instant().atOffset(ZoneOffset.UTC)) }
                    }

                    sql
                        .queryForList("SELECT token_hash FROM ${tenant.table("sso_sessions")}", ByteArray::class.java)
                        .map { it!!.toList() }
                        .shouldContainExactly(listOf(sha256(live).toList()))
                }
            }
        }
    })
