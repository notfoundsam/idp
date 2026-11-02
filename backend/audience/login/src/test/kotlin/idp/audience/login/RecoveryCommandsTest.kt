package idp.audience.login

import idp.audience.login.application.command.recovery.AcceptInvitation
import idp.audience.login.application.command.recovery.AcceptInvitationHandler
import idp.audience.login.application.command.recovery.ConfirmEmailChange
import idp.audience.login.application.command.recovery.ConfirmEmailChangeHandler
import idp.audience.login.application.command.recovery.ConfirmPasswordReset
import idp.audience.login.application.command.recovery.ConfirmPasswordResetHandler
import idp.audience.login.application.command.recovery.RequestEmailChange
import idp.audience.login.application.command.recovery.RequestEmailChangeHandler
import idp.authentication.application.AuthenticationService
import idp.authentication.application.FlowAccess
import idp.authentication.application.LoginFactor
import idp.authentication.application.LoginStep
import idp.authentication.domain.StepNotAllowed
import idp.identity.application.EmailChangeNotValid
import idp.identity.application.IdentityService
import idp.identity.application.InvitationNotValid
import idp.identity.application.PasskeyService
import idp.identity.application.PasswordResetNotValid
import idp.identity.application.PasswordService
import idp.identity.application.TotpService
import idp.identity.domain.credential.WeakPassword
import idp.sharedkernel.audit.Actor
import idp.sharedkernel.audit.AuditLog
import idp.sharedkernel.audit.AuditRecord
import idp.sharedkernel.audit.AuditTarget
import idp.sharedkernel.crypto.KeyHierarchy
import idp.sharedkernel.crypto.adapter.OpenBaoKeyManagementService
import idp.sharedkernel.migration.TenantSchema
import idp.sharedkernel.outbox.OutboxPublisher
import idp.sharedkernel.security.Principal
import idp.sharedkernel.security.RequiresFreshAuthentication
import idp.sharedkernel.security.Role
import idp.sharedkernel.security.SecurityContext
import idp.sharedkernel.security.UserRole
import idp.sharedkernel.tenant.PlatformTransaction
import idp.sharedkernel.tenant.TenantContext
import idp.sharedkernel.tenant.TenantResolver
import idp.sharedkernel.tenant.TenantTransaction
import idp.sharedkernel.testing.TestDatabase
import idp.sharedkernel.testing.TestOpenBao
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

private const val HOST = "acme.idp.test"
private const val EMAIL = "ada.lovelace@acme.test"
private const val PASSWORD = "copper lantern river"
private const val NEW_PASSWORD = "silver heron forest"
private const val USER_AGENT = "Mozilla/5.0 (Macintosh)"
private const val BROWSER = "browser-secret"
private val KEY_CACHE_TTL: Duration = Duration.ofMinutes(1)

private class AuditOnPurpose : AuditLog {
    val changes = CopyOnWriteArrayList<AuditRecord>()
    val denials = CopyOnWriteArrayList<AuditRecord>()

    @Volatile var failOn: String? = null

    override fun recordChange(record: AuditRecord) {
        check(record.action != failOn) { "audit is down" }
        changes += record
    }

    override fun recordDenial(record: AuditRecord) {
        denials += record
    }
}

private class Commands {
    val database = TestDatabase.migrated()
    val sql = JdbcTemplate(database.owner())
    val kms = OpenBaoKeyManagementService(TestOpenBao.address, TestOpenBao.TOKEN)
    val dataSource = database.pooled("app_runtime")
    val transaction = TenantTransaction(dataSource)
    val keys = KeyHierarchy(kms, transaction, PlatformTransaction(dataSource), KEY_CACHE_TTL)
    val audit = AuditOnPurpose()
    val identity = IdentityService(transaction, keys, audit, OutboxPublisher())
    val passwords = PasswordService(transaction, identity, audit)
    val totp = TotpService(transaction, keys, audit)
    val authentication = AuthenticationService(transaction, keys, audit, identity, totp, PasskeyService(transaction, audit))
    val tenants = TenantResolver(PlatformTransaction(dataSource), Duration.ofMinutes(1))
    val accept = AcceptInvitationHandler(transaction, authentication, identity, passwords, tenants, audit)
    val confirmReset = ConfirmPasswordResetHandler(transaction, authentication, identity, passwords, tenants, audit)
    val confirmEmail = ConfirmEmailChangeHandler(identity, audit)
    val tenant = tenant()

    private fun tenant(): TenantContext {
        val id = database.tenantSchema()
        val kekUri = kms.createKey("tenant-${UUID.randomUUID()}")
        sql.update("INSERT INTO platform.tenants (id, name, status, kek_uri) VALUES (?, 'Contoso Group', 'ACTIVE', ?)", id, kekUri)
        sql.update("INSERT INTO platform.tenant_domains (domain, tenant_id) VALUES (?, ?)", HOST, id)
        return TenantContext(id, kekUri).also { context -> TenantContext.with(context) { keys.createTenantKey() } }
    }

    fun <T> acting(block: () -> T): T = TenantContext.with(tenant, block)

    fun table(name: String) = "\"${TenantSchema(tenant.tenantId).name}\".$name"

    fun relaxMfa() {
        sql.update("UPDATE ${table("tenant_policies")} SET mfa_for_users = false")
    }

    fun invited(
        email: String = EMAIL,
        role: UserRole = UserRole.USER,
    ): Pair<UUID, String> =
        acting {
            val user = identity.invite(email, role, Actor.System)
            user to identity.issueInvitation(user, Actor.System).shouldNotBeNull()
        }

    /** An active user with a password and one live session. */
    fun signedIn(email: String = EMAIL): Pair<UUID, String> {
        relaxMfa()
        val (user, token) = invited(email)
        val flow = flow()
        acting { accept.handle(AcceptInvitation(flow, token, PASSWORD, HOST)) }
        val session = acting { authentication.completeFlow(flow, null, USER_AGENT) }.session.shouldNotBeNull().token
        audit.changes.clear()
        return user to session
    }

    fun flow(): FlowAccess = acting { authentication.startFlow(browserSecret = BROWSER) }

    fun resetToken(user: UUID): String = acting { identity.issuePasswordReset(user, Actor.System) }.shouldNotBeNull()

    fun resolves(session: String): Boolean = acting { authentication.resolveSession(session) } != null

    fun passwordHash(user: UUID): String =
        sql.queryForObject("SELECT hash FROM ${table("password_credentials")} WHERE user_id = ?", String::class.java, user)!!

    fun canLogIn(
        user: UUID,
        password: String,
    ): Boolean = acting { passwords.verify(user, password) }

    fun status(user: UUID) = acting { identity.user(user) }.shouldNotBeNull().status.name

    fun actions(): List<String> = audit.changes.map { it.action }
}

class RecoveryCommandsTest :
    FunSpec({
        context("accepting an invitation") {
            test("sets the password, activates the user and walks on to the enrollment the policy asks for") {
                Commands().run {
                    val (user, token) = invited()

                    val view = acting { accept.handle(AcceptInvitation(flow(), token, PASSWORD, HOST)) }

                    view.step shouldBe LoginStep.ENROLL_MFA
                    view.factors shouldBe setOf(LoginFactor.TOTP, LoginFactor.PASSKEY)
                    status(user) shouldBe "ACTIVE"
                    canLogIn(user, PASSWORD) shouldBe true
                }
            }

            test("goes straight to the end where a tenant relaxed MFA for the role") {
                Commands().run {
                    relaxMfa()
                    val (_, token) = invited()

                    acting { accept.handle(AcceptInvitation(flow(), token, PASSWORD, HOST)) }.step shouldBe LoginStep.COMPLETE
                }
            }

            test("asks an admin for MFA even there") {
                Commands().run {
                    relaxMfa()
                    val (_, token) = invited(role = UserRole.TENANT_ADMIN)

                    acting { accept.handle(AcceptInvitation(flow(), token, PASSWORD, HOST)) }.step shouldBe LoginStep.ENROLL_MFA
                }
            }

            test("a refused password keeps the invitation, so it can be typed again") {
                Commands().run {
                    val (user, token) = invited()
                    val flow = flow()

                    shouldThrow<WeakPassword> { acting { accept.handle(AcceptInvitation(flow, token, "short", HOST)) } }

                    status(user) shouldBe "INVITED"
                    acting { accept.handle(AcceptInvitation(flow, token, PASSWORD, HOST)) }.step shouldBe LoginStep.ENROLL_MFA
                }
            }

            test("the name and the subdomain of the tenant are refused in a password") {
                Commands().run {
                    val (_, token) = invited()

                    shouldThrow<WeakPassword> { acting { accept.handle(AcceptInvitation(flow(), token, "contoso river stone", HOST)) } }
                    shouldThrow<WeakPassword> { acting { accept.handle(AcceptInvitation(flow(), token, "acme river stone", HOST)) } }
                }
            }

            test("a failure while storing the password leaves the user invited and the token alive") {
                Commands().run {
                    val (user, token) = invited()
                    audit.failOn = "user.password_set"

                    shouldThrow<IllegalStateException> { acting { accept.handle(AcceptInvitation(flow(), token, PASSWORD, HOST)) } }

                    audit.failOn = null
                    status(user) shouldBe "INVITED"
                    acting { accept.handle(AcceptInvitation(flow(), token, PASSWORD, HOST)) }.step shouldBe LoginStep.ENROLL_MFA
                }
            }

            test("a made-up token is refused and recorded without the token") {
                Commands().run {
                    shouldThrow<InvitationNotValid> { acting { accept.handle(AcceptInvitation(flow(), "made-up", PASSWORD, HOST)) } }

                    val denial = audit.denials.single()
                    denial.action shouldBe "recovery.token_refused"
                    denial.details shouldBe mapOf("kind" to "invitation")
                }
            }

            test("is refused on a flow past its first step, and costs no token") {
                Commands().run {
                    val (user, token) = invited()
                    val flow = flow()
                    acting { authentication.identify(flow, "someone@acme.test") }

                    shouldThrow<StepNotAllowed> { acting { accept.handle(AcceptInvitation(flow, token, PASSWORD, HOST)) } }

                    status(user) shouldBe "INVITED"
                    acting { identity.invitationOwner(token) } shouldBe user
                }
            }

            test("frees the flow after a refusal") {
                Commands().run {
                    val (_, token) = invited()
                    val flow = flow()
                    shouldThrow<InvitationNotValid> { acting { accept.handle(AcceptInvitation(flow, "made-up", PASSWORD, HOST)) } }

                    acting { accept.handle(AcceptInvitation(flow, token, PASSWORD, HOST)) }.step shouldBe LoginStep.ENROLL_MFA
                }
            }

            test("is recorded under the user who proved the mailbox") {
                Commands().run {
                    val (user, token) = invited()
                    audit.changes.clear()

                    acting { accept.handle(AcceptInvitation(flow(), token, PASSWORD, HOST)) }

                    actions().shouldContainExactly("user.invitation_accepted", "user.password_set")
                    audit.changes.forEach { it.actor shouldBe Actor.User(user) }
                }
            }
        }

        context("a forgotten password") {
            test("changes the password and revokes every session") {
                Commands().run {
                    val (user, session) = signedIn()
                    val before = passwordHash(user)

                    acting { confirmReset.handle(ConfirmPasswordReset(resetToken(user), NEW_PASSWORD, HOST)) }

                    passwordHash(user) shouldNotBe before
                    canLogIn(user, NEW_PASSWORD) shouldBe true
                    canLogIn(user, PASSWORD) shouldBe false
                    resolves(session) shouldBe false
                }
            }

            test("rolls back the password and the token when the sessions cannot be revoked") {
                Commands().run {
                    val (user, session) = signedIn()
                    val before = passwordHash(user)
                    val token = resetToken(user)
                    audit.failOn = "session.revoked_for_user"

                    shouldThrow<IllegalStateException> { acting { confirmReset.handle(ConfirmPasswordReset(token, NEW_PASSWORD, HOST)) } }

                    passwordHash(user) shouldBe before
                    resolves(session) shouldBe true
                    acting { identity.passwordResetOwner(token) } shouldBe user
                }
            }

            test("rolls back the sessions and the token when the password cannot be stored") {
                Commands().run {
                    val (user, session) = signedIn()
                    val token = resetToken(user)
                    audit.failOn = "user.password_set"

                    shouldThrow<IllegalStateException> { acting { confirmReset.handle(ConfirmPasswordReset(token, NEW_PASSWORD, HOST)) } }

                    resolves(session) shouldBe true
                    acting { identity.passwordResetOwner(token) } shouldBe user
                }
            }

            test("a refused password keeps the token and the sessions") {
                Commands().run {
                    val (user, session) = signedIn()
                    val token = resetToken(user)

                    shouldThrow<WeakPassword> { acting { confirmReset.handle(ConfirmPasswordReset(token, "short", HOST)) } }

                    resolves(session) shouldBe true
                    acting { confirmReset.handle(ConfirmPasswordReset(token, NEW_PASSWORD, HOST)) }
                    canLogIn(user, NEW_PASSWORD) shouldBe true
                }
            }

            test("the words of the tenant are refused here too") {
                Commands().run {
                    val (user, _) = signedIn()
                    val token = resetToken(user)

                    shouldThrow<WeakPassword> { acting { confirmReset.handle(ConfirmPasswordReset(token, "contoso river stone", HOST)) } }
                }
            }

            test("a spent token is refused and recorded without the token") {
                Commands().run {
                    val (user, _) = signedIn()
                    val token = resetToken(user)
                    acting { confirmReset.handle(ConfirmPasswordReset(token, NEW_PASSWORD, HOST)) }

                    shouldThrow<PasswordResetNotValid> { acting { confirmReset.handle(ConfirmPasswordReset(token, PASSWORD, HOST)) } }

                    val denial = audit.denials.single()
                    denial.action shouldBe "recovery.token_refused"
                    denial.details shouldBe mapOf("kind" to "password_reset")
                    canLogIn(user, NEW_PASSWORD) shouldBe true
                }
            }

            test("opens no session, so the next login starts from nothing") {
                Commands().run {
                    val (user, _) = signedIn()

                    acting { confirmReset.handle(ConfirmPasswordReset(resetToken(user), NEW_PASSWORD, HOST)) }

                    sql.queryForObject(
                        "SELECT count(*) FROM ${table("sso_sessions")} WHERE revoked_at IS NULL",
                        Int::class.java,
                    ) shouldBe 0
                }
            }

            test("is recorded, and the revocation names the sessions it ended") {
                Commands().run {
                    val (user, _) = signedIn()
                    val token = resetToken(user)
                    audit.changes.clear()

                    acting { confirmReset.handle(ConfirmPasswordReset(token, NEW_PASSWORD, HOST)) }

                    actions().shouldContainExactly("user.password_reset", "user.password_set", "session.revoked_for_user")
                    audit.changes.forEach {
                        it.actor shouldBe Actor.User(user)
                        it.target shouldBe AuditTarget.user(user)
                    }
                    audit.changes.last().details shouldBe mapOf("sessions" to "1")
                }
            }
        }

        context("an email change") {
            test("takes the user from the session and waits for the new address") {
                Commands().run {
                    val (user, _) = signedIn()
                    val principal = Principal(Actor.User(user), Role(UserRole.USER.name))
                    val handler = RequestEmailChangeHandler(identity)

                    acting { SecurityContext.with(principal) { handler.handle(RequestEmailChange("new@acme.test")) } }

                    acting { identity.pendingEmailOf(user) } shouldBe "new@acme.test"
                    acting { identity.emailOf(user) } shouldBe EMAIL
                    actions() shouldContain "user.email_change_requested"
                }
            }

            test("the message demands a fresh authentication") {
                RequiresFreshAuthentication::class.java.isAssignableFrom(RequestEmailChange::class.java) shouldBe true
            }

            test("a confirmation moves the address") {
                Commands().run {
                    val (user, _) = signedIn()
                    acting { identity.requestEmailChange(user, "new@acme.test", Actor.User(user)) }
                    val token = acting { identity.issueEmailChange(user, Actor.System) }.shouldNotBeNull()

                    acting { confirmEmail.handle(ConfirmEmailChange(token)) }

                    acting { identity.emailOf(user) } shouldBe "new@acme.test"
                }
            }

            test("a made-up token is refused and recorded") {
                Commands().run {
                    shouldThrow<EmailChangeNotValid> { acting { confirmEmail.handle(ConfirmEmailChange("made-up")) } }

                    audit.denials.single().details shouldBe mapOf("kind" to "email_change")
                }
            }
        }
    })
