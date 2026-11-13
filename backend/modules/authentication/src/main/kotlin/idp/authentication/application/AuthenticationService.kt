package idp.authentication.application

import idp.authentication.adapters.persistence.FlowCleanup
import idp.authentication.adapters.persistence.Flows
import idp.authentication.adapters.persistence.SessionCleanup
import idp.authentication.adapters.persistence.Sessions
import idp.authentication.adapters.persistence.TenantPolicies
import idp.authentication.domain.Assurance
import idp.authentication.domain.AuthFactor
import idp.authentication.domain.AuthFlow
import idp.authentication.domain.FlowExpired
import idp.authentication.domain.FlowStep
import idp.authentication.domain.FlowUser
import idp.authentication.domain.PasskeyNotAccepted
import idp.authentication.domain.PriorAuthentication
import idp.authentication.domain.SessionToken
import idp.authentication.domain.SpRequirements
import idp.authentication.domain.SsoSession
import idp.authentication.domain.StepInProgress
import idp.authentication.domain.StepNotAllowed
import idp.authentication.domain.TenantPolicy
import idp.identity.application.FactorRequirement
import idp.identity.application.IdentityService
import idp.identity.application.PasskeyService
import idp.identity.application.TotpService
import idp.identity.application.UserView
import idp.sharedkernel.audit.Actor
import idp.sharedkernel.audit.AuditLog
import idp.sharedkernel.audit.AuditRecord
import idp.sharedkernel.audit.AuditTarget
import idp.sharedkernel.crypto.DerivedKey
import idp.sharedkernel.crypto.FieldContext
import idp.sharedkernel.crypto.KeyHierarchy
import idp.sharedkernel.domain.DomainRuleViolation
import idp.sharedkernel.domain.NotFound
import idp.sharedkernel.scheduling.TenantCleanup
import idp.sharedkernel.security.Principal
import idp.sharedkernel.security.UserRole
import idp.sharedkernel.security.UserStatus
import idp.sharedkernel.tenant.TenantContext
import idp.sharedkernel.tenant.TenantTransaction
import idp.sharedkernel.throttle.TooManyRequestsException
import org.jooq.DSLContext
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

private const val SESSION_OPENED = "session.opened"
private const val SESSION_REVOKED = "session.revoked"
private const val SESSION_LOGGED_OUT = "session.logged_out"
private const val SESSION = "session"
private const val SESSIONS_REVOKED_FOR_USER = "session.revoked_for_user"
private const val SESSIONS_REVOKED_FOR_TENANT = "session.revoked_for_tenant"
private const val POLICY_CHANGED = "policy.changed"

private const val LOGIN_FIELD = "flows.login_ciphertext"
private const val IP_FIELD = "sso_sessions.ip_ciphertext"
private const val USER_AGENT_FIELD = "sso_sessions.user_agent_ciphertext"

private val FLOWS_FULL_RETRY: Duration = Duration.ofMinutes(1)

/** Not a data class: `toString` must not print the token. */
class IssuedSession(
    val token: String,
) {
    override fun toString(): String = "IssuedSession(***)"
}

/**
 * A live session as an SP sees it: who, by which factors and since when. [expiresAt] is its absolute end under the policy of the
 * moment. The idle end moves with every request, so it is not stated.
 */
class SessionView(
    val id: UUID,
    val userId: UUID,
    val factors: Set<LoginFactor>,
    val authenticatedAt: Instant,
    val expiresAt: Instant,
)

/** A live session as its own user sees it, with the IP and the User-Agent of its login. Not a data class: `toString` must not print them. */
class UserSessionView(
    val id: UUID,
    val authenticatedAt: Instant,
    val lastSeenAt: Instant,
    val ip: String?,
    val userAgent: String?,
)

class SessionNotFound(
    id: UUID,
) : NotFound("No live session $id")

class PasskeysLocked : DomainRuleViolation("The platform requires passkeys of this tenant, so the policy keeps them")

/** MFA for `TENANT_ADMIN` has no field: no policy relaxes it. */
data class PolicyView(
    val mfaForUsers: Boolean,
    val passkeyRequired: Boolean,
    val sessionIdleTimeout: Duration,
    val sessionAbsoluteTimeout: Duration,
    val version: Long,
)

data class PolicyChange(
    val mfaForUsers: Boolean,
    val passkeyRequired: Boolean,
    val sessionIdleTimeout: Duration,
    val sessionAbsoluteTimeout: Duration,
)

/** [session] is null when the browser keeps the session it has: nothing new was proved, so there is nothing to rotate. */
class CompletedFlow(
    val userId: UUID,
    val factors: Set<LoginFactor>,
    val returnUrl: String?,
    val session: IssuedSession?,
)

/** Rotation issues a new token after every authentication, and a session never outlives its first login by continuing in a flow. */
class AuthenticationService(
    private val transaction: TenantTransaction,
    private val keys: KeyHierarchy,
    private val audit: AuditLog,
    private val identity: IdentityService,
    private val totp: TotpService,
    private val passkeys: PasskeyService,
    private val clock: Clock = Clock.systemUTC(),
    private val flowTtl: Duration = Duration.ofMinutes(15),
    private val lastSeenInterval: Duration = Duration.ofMinutes(1),
    private val maxSessions: Int = 10,
    private val maxOpenFlows: Int = 100_000,
    private val stepLease: Duration = Duration.ofSeconds(30),
) {
    private val policies = TenantPolicies()
    private val flows = Flows()
    private val sessions = Sessions()
    private val random = SecureRandom()

    val flowCleanup: TenantCleanup = FlowCleanup()

    val sessionCleanup: TenantCleanup = SessionCleanup()

    /**
     * A session in [sessionToken] that is no longer good counts as none. A browser that already holds [browserSecret] keeps it, so its
     * tabs do not cut each other's flows off. Refuses beyond [maxOpenFlows], because an anonymous caller adds rows here.
     */
    fun startFlow(
        continuation: FlowContinuation? = null,
        sessionToken: String? = null,
        browserSecret: String? = null,
    ): FlowAccess =
        transaction.execute { dsl ->
            val now = clock.instant()
            if (flows.countOpen(dsl, now, maxOpenFlows) >= maxOpenFlows) throw TooManyRequestsException(FLOWS_FULL_RETRY)
            val policy = policies.load(dsl)
            val prior = sessionToken?.let { prior(dsl, it, policy, now) }
            val requirements = continuation?.requirements() ?: SpRequirements()
            val flow = AuthFlow.start(UUID.randomUUID(), policy, requirements, continuation?.returnUrl, prior, now, flowTtl)
            val secret = browserSecret ?: SessionToken.generate(random).value
            flows.add(dsl, flow, SessionToken(secret).hash())
            FlowAccess(flow.id, secret)
        }

    fun step(access: FlowAccess): FlowView =
        transaction.execute { dsl ->
            load(dsl, access).view(clock.instant())
        }

    /** Looks up the factors of nobody when the login matched nobody, so this answer takes as long for an unknown login. */
    fun identify(
        access: FlowAccess,
        login: String,
    ): FlowView =
        onFlow(access) { flow, now ->
            val known = identity.findByEmail(login)
            val enrolled = enrolled(known?.id ?: UUID.randomUUID())
            val user = known?.let { FlowUser(it.id, it.role, enrolled) }
            val sealed = keys.derivedCipher(DerivedKey.FLOW).encrypt(login.toByteArray(), FieldContext(LOGIN_FIELD, access.id))
            flow.identify(user, sealed, now)
            flow.view(now)
        }

    /**
     * Reserves the flow for one check of a credential and says whom to check it against. The credential services spend an attempt or
     * a code on every try, so without this a double click would burn one and report a wrong password while the other request succeeds.
     */
    fun claimStep(
        access: FlowAccess,
        factor: LoginFactor,
    ): FlowAttempt = claim(access) { flow, now -> flow.accepts(factor.domain(), now) }

    /** Reserves the flow like [claimStep], for a user who has to enroll [factor] here. */
    fun claimEnrollment(
        access: FlowAccess,
        factor: LoginFactor,
    ): UUID = checkNotNull(claim(access) { flow, now -> flow.enrolls(factor.domain(), now) && isActive(flow) }.userId)

    /** The user who may start to enroll [factor]. Starting spends nothing, so it reserves nothing. */
    fun enrollingUser(
        access: FlowAccess,
        factor: LoginFactor,
    ): UUID =
        transaction.execute { dsl ->
            val flow = load(dsl, access)
            if (!flow.enrolls(factor.domain(), clock.instant()) || !isActive(flow)) throw StepNotAllowed()
            checkNotNull(flow.userId)
        }

    /** Reserves the flow like [claimStep], before an invitation is checked and a password is hashed. */
    fun claimInvitation(access: FlowAccess) {
        claim(access) { flow, now -> flow.takesInvitation(now) }
    }

    fun releaseStep(access: FlowAccess) {
        transaction.execute { dsl -> flows.release(dsl, access.id) }
    }

    fun passwordPassed(access: FlowAccess): FlowView = pass(access) { flow, now -> flow.passwordPassed(now) }

    /** Joins the transaction that spent the invitation and stored the password, so a flow cannot move on from half of that. */
    fun invitationAccepted(
        access: FlowAccess,
        userId: UUID,
    ): FlowView = pass(access) { flow, now -> flow.invitationAccepted(flowUser(checkNotNull(identity.user(userId))), now) }

    fun totpPassed(access: FlowAccess): FlowView = pass(access) { flow, now -> flow.totpPassed(now) }

    fun recoveryCodeUsed(access: FlowAccess): FlowView = pass(access) { flow, now -> flow.recoveryCodeUsed(now) }

    /** Null when this passkey opens nothing here: its owner may not log in, or the flow expects someone else. */
    fun passkeyPassed(
        access: FlowAccess,
        ownerId: UUID,
    ): FlowView? {
        val owner = identity.user(ownerId)?.takeIf { it.status == UserStatus.ACTIVE }?.let(::flowUser) ?: return null
        return try {
            pass(access) { flow, now -> flow.passkeyPassed(owner, now) }
        } catch (_: PasskeyNotAccepted) {
            null
        }
    }

    /**
     * The credential is stored by now and a flow reads the factors anew, so it would ask for the very factor that was just enrolled.
     * Reading it without that factor brings back the step that asked for the enrollment, whose own rule then decides.
     */
    fun enrolled(
        access: FlowAccess,
        factor: LoginFactor,
    ): FlowView =
        transaction.execute { dsl ->
            val now = clock.instant()
            val flow = load(dsl, access, ignoring = factor.domain())
            flow.enrolled(factor.domain(), now)
            flows.save(dsl, flow)
            flow.view(now)
        }

    private fun pass(
        access: FlowAccess,
        change: (AuthFlow, Instant) -> Unit,
    ): FlowView =
        onFlow(access) { flow, now ->
            change(flow, now)
            flow.view(now)
        }

    private fun claim(
        access: FlowAccess,
        allowed: (AuthFlow, Instant) -> Boolean,
    ): FlowAttempt =
        transaction.execute { dsl ->
            val now = clock.instant()
            val flow = load(dsl, access)
            if (!allowed(flow, now)) throw StepNotAllowed()
            if (!flows.lease(dsl, flow.id, now, now.plus(stepLease))) throw StepInProgress()
            FlowAttempt(flow.userId, isActive(flow))
        }

    private fun isActive(flow: AuthFlow): Boolean = flow.userId?.let(identity::user)?.status == UserStatus.ACTIVE

    /** Loads the flow with what is true now, lets [change] walk it and stores the result. A parallel step loses by version. */
    internal fun <T> onFlow(
        access: FlowAccess,
        change: (AuthFlow, Instant) -> T,
    ): T =
        transaction.execute { dsl ->
            val flow = load(dsl, access)
            change(flow, clock.instant()).also { flows.save(dsl, flow) }
        }

    /**
     * Consumes the flow. A flow that took over a session fails once that session is gone, otherwise a revoked session would hand its
     * factors on. It also keeps the first authentication of the session, or repeating flows would escape the absolute timeout.
     */
    fun completeFlow(
        access: FlowAccess,
        ip: String?,
        userAgent: String?,
    ): CompletedFlow =
        transaction.execute { dsl ->
            val now = clock.instant()
            val flow = load(dsl, access)
            if (flow.step(now) != FlowStep.Complete) throw StepNotAllowed()
            val userId = checkNotNull(flow.userId)
            if (identity.user(userId)?.status != UserStatus.ACTIVE) throw StepNotAllowed()
            flows.delete(dsl, flow)
            val previous = flow.sessionId?.let { sessions.find(dsl, it) }?.takeIf { it.isLive(flow.policy, now) }
            if (flow.continuesSession && previous == null) throw FlowExpired()
            val factors = flow.proved.map(LoginFactor::of).toSet()
            if (flow.continuesSession && previous?.factors == flow.proved) {
                return@execute CompletedFlow(userId, factors, flow.returnUrl, null)
            }
            previous?.let { sessions.save(dsl, it.apply { revoke(now) }) }
            val authenticatedAt = previous?.authenticatedAt?.takeIf { flow.continuesSession } ?: now
            CompletedFlow(userId, factors, flow.returnUrl, open(dsl, userId, flow.proved, authenticatedAt, ip, userAgent, now))
        }

    /** The role and the status are read on every request, so a change reaches a live session on its next request. */
    fun resolveSession(token: String): Principal? =
        transaction.execute { dsl ->
            val now = clock.instant()
            val session = sessions.findByTokenHash(dsl, SessionToken(token).hash())?.takeIf { it.isLive(policies.load(dsl), now) }
            val user = session?.let { identity.user(it.userId) }?.takeIf { it.status == UserStatus.ACTIVE } ?: return@execute null
            if (session.isDueForLastSeen(now, lastSeenInterval)) sessions.markSeen(dsl, session.id, now)
            Principal(Actor.User(user.id), user.role.role, session.authenticatedAt, session.id)
        }

    /** The live sessions of [userId], the one seen last first. Their IP and User-Agent open with the key of the user. */
    fun sessionsOf(userId: UUID): List<UserSessionView> =
        transaction.execute { dsl ->
            val now = clock.instant()
            val policy = policies.load(dsl)
            val cipher = keys.userCipher(userId)

            fun SsoSession.open(
                sealed: ByteArray?,
                field: String,
            ) = sealed?.let { cipher.decrypt(it, FieldContext(field, id)).decodeToString() }

            sessions.notRevokedOfUser(dsl, userId).filter { it.isLive(policy, now) }.map {
                UserSessionView(
                    it.id,
                    it.authenticatedAt,
                    it.lastSeenAt,
                    it.open(it.ipCiphertext, IP_FIELD),
                    it.open(it.userAgentCiphertext, USER_AGENT_FIELD),
                )
            }
        }

    /** Ends one live session of [userId]. A session of someone else reads as missing, the same as one that is gone. */
    fun revokeSession(
        userId: UUID,
        sessionId: UUID,
        actor: Actor,
        ip: String? = null,
    ) {
        transaction.execute { dsl ->
            if (!end(dsl, userId, sessionId)) throw SessionNotFound(sessionId)
            audit.recordChange(AuditRecord(actor, SESSION_REVOKED, AuditTarget.user(userId), ip, mapOf(SESSION to "$sessionId")))
        }
    }

    /** Ends the session the user logs out of. One that is already gone leaves nothing to do and nothing to record. */
    fun logout(
        userId: UUID,
        sessionId: UUID,
        ip: String? = null,
    ) {
        transaction.execute { dsl ->
            if (end(dsl, userId, sessionId)) {
                val user = Actor.User(userId)
                audit.recordChange(AuditRecord(user, SESSION_LOGGED_OUT, AuditTarget.user(userId), ip, mapOf(SESSION to "$sessionId")))
            }
        }
    }

    private fun end(
        dsl: DSLContext,
        userId: UUID,
        sessionId: UUID,
    ): Boolean {
        val now = clock.instant()
        val session = sessions.find(dsl, sessionId)?.takeIf { it.userId == userId && it.isLive(policies.load(dsl), now) } ?: return false
        sessions.save(dsl, session.apply { revoke(now) })
        return true
    }

    /** Null for a session that is gone, timed out or revoked, or whose user may no longer log in. */
    fun session(token: String): SessionView? =
        transaction.execute { dsl ->
            val now = clock.instant()
            val policy = policies.load(dsl)
            val session = sessions.findByTokenHash(dsl, SessionToken(token).hash())?.takeIf { it.isLive(policy, now) }
            session?.takeIf { identity.user(it.userId)?.status == UserStatus.ACTIVE }?.let {
                SessionView(
                    it.id,
                    it.userId,
                    it.factors.map(LoginFactor::of).toSet(),
                    it.authenticatedAt,
                    it.authenticatedAt.plus(policy.sessionAbsoluteTimeout),
                )
            }
        }

    fun policy(): PolicyView =
        transaction.execute { dsl ->
            policies.load(dsl).run { PolicyView(mfaForUsers, passkeyRequired, sessionIdleTimeout, sessionAbsoluteTimeout, version) }
        }

    /** What a user of [role] must keep of their factors, so removing one never leaves them below the policy. */
    fun factorRequirementFor(role: UserRole): FactorRequirement =
        transaction.execute { dsl ->
            when (policies.load(dsl).assuranceFor(role)) {
                Assurance.PASSKEY -> FactorRequirement.PASSKEY
                Assurance.MULTI_FACTOR -> FactorRequirement.SECOND_FACTOR
                Assurance.SINGLE_FACTOR -> FactorRequirement.NONE
            }
        }

    /**
     * Replaces the policy read at [version], so an admin cannot overwrite a change they have not seen. Records the fields that
     * changed, a relaxed MFA too. Returns the new version, or the same one when nothing changed.
     */
    fun savePolicy(
        change: PolicyChange,
        version: Long,
        actor: Actor,
        ip: String? = null,
    ): Long =
        transaction.execute { dsl ->
            if (!change.passkeyRequired && policies.passkeysLocked(dsl)) throw PasskeysLocked()
            val current = policies.load(dsl)
            val next =
                TenantPolicy(change.mfaForUsers, change.passkeyRequired, change.sessionIdleTimeout, change.sessionAbsoluteTimeout, version)
            val changed = changedFields(current, next)
            if (changed.isEmpty() && current.version == version) return@execute version
            val saved = policies.save(dsl, next)
            audit.recordChange(AuditRecord(actor, POLICY_CHANGED, ip = ip, details = changed))
            saved
        }

    private fun changedFields(
        current: TenantPolicy,
        next: TenantPolicy,
    ): Map<String, String> =
        buildMap {
            if (next.mfaForUsers != current.mfaForUsers) put("mfa_for_users", "${next.mfaForUsers}")
            if (next.passkeyRequired != current.passkeyRequired) put("passkey_required", "${next.passkeyRequired}")
            if (next.sessionIdleTimeout != current.sessionIdleTimeout) put("session_idle_seconds", "${next.sessionIdleTimeout.seconds}")
            if (next.sessionAbsoluteTimeout != current.sessionAbsoluteTimeout) {
                put("session_absolute_seconds", "${next.sessionAbsoluteTimeout.seconds}")
            }
        }

    /**
     * Every role then logs in with a passkey, and the policy can no longer drop it: the service tenant is the IdP of `ops`, which
     * accepts only a passkey. Records only a change, so a repeat leaves the journal as it was.
     */
    fun requirePasskeys(
        actor: Actor,
        ip: String? = null,
    ) {
        transaction.execute { dsl ->
            policies.lockPasskeys(dsl)
            val policy = policies.load(dsl)
            if (policy.passkeyRequired) return@execute
            policies.save(dsl, policy.withPasskeysRequired())
            audit.recordChange(AuditRecord(actor, POLICY_CHANGED, ip = ip, details = mapOf("passkey_required" to "true")))
        }
    }

    /**
     * Joins the transaction of the caller, because a block or a reset that rolled back must leave the sessions alone. The logins in
     * progress end too: a password proved before a reset must not open a session after it. [kept] survives, so a user who ends their
     * other sessions stays logged in.
     */
    fun revokeUserSessions(
        userId: UUID,
        actor: Actor,
        ip: String? = null,
        kept: UUID? = null,
    ) {
        transaction.join { dsl ->
            flows.deleteOfUser(dsl, userId)
            val revoked = sessions.revokeOfUser(dsl, userId, clock.instant(), kept)
            audit.recordChange(AuditRecord(actor, SESSIONS_REVOKED_FOR_USER, AuditTarget.user(userId), ip, mapOf("sessions" to "$revoked")))
        }
    }

    /** Joins the transaction of the caller, like [revokeUserSessions]. */
    fun revokeAllSessions(
        actor: Actor,
        ip: String? = null,
    ) {
        transaction.join { dsl ->
            val revoked = sessions.revokeAll(dsl, clock.instant())
            val target = AuditTarget.tenant(TenantContext.current().tenantId)
            audit.recordChange(AuditRecord(actor, SESSIONS_REVOKED_FOR_TENANT, target, ip, mapOf("sessions" to "$revoked")))
        }
    }

    private fun open(
        dsl: DSLContext,
        userId: UUID,
        factors: Set<AuthFactor>,
        authenticatedAt: Instant,
        ip: String?,
        userAgent: String?,
        now: Instant,
    ): IssuedSession {
        val id = UUID.randomUUID()
        val token = SessionToken.generate(random)
        val cipher = keys.userCipher(userId)
        val session =
            SsoSession(
                id,
                userId,
                token.hash(),
                factors,
                ip?.let { cipher.encrypt(it.toByteArray(), FieldContext(IP_FIELD, id)) },
                userAgent?.let { cipher.encrypt(it.toByteArray(), FieldContext(USER_AGENT_FIELD, id)) },
                authenticatedAt,
                now,
            )
        sessions.add(dsl, session)
        val evicted = sessions.revokeBeyond(dsl, userId, maxSessions, now)
        val details = if (evicted > 0) mapOf("evicted" to "$evicted") else emptyMap()
        audit.recordChange(AuditRecord(Actor.User(userId), SESSION_OPENED, AuditTarget.user(userId), ip, details))
        return IssuedSession(token.value)
    }

    /** A flow whose id is gone or that another browser began reads as expired, so a guess learns nothing. */
    private fun load(
        dsl: DSLContext,
        access: FlowAccess,
        ignoring: AuthFactor? = null,
    ): AuthFlow {
        val policy = policies.load(dsl)
        val browserHash = SessionToken(access.browserSecret).hash()
        val flow =
            flows.find(dsl, access.id, browserHash, policy) { userId ->
                identity.user(userId)?.let(::flowUser)?.let { FlowUser(it.id, it.role, it.enrolled - setOfNotNull(ignoring)) }
            }
        return flow ?: throw FlowExpired()
    }

    private fun prior(
        dsl: DSLContext,
        token: String,
        policy: TenantPolicy,
        now: Instant,
    ): PriorAuthentication? {
        val session = sessions.findByTokenHash(dsl, SessionToken(token).hash())?.takeIf { it.isLive(policy, now) } ?: return null
        val user = identity.user(session.userId)?.takeIf { it.status == UserStatus.ACTIVE } ?: return null
        return PriorAuthentication(session.id, flowUser(user), session.factors)
    }

    private fun flowUser(user: UserView) = FlowUser(user.id, user.role, enrolled(user.id))

    private fun enrolled(userId: UUID): Set<AuthFactor> =
        buildSet {
            if (totp.hasConfirmedTotp(userId)) add(AuthFactor.TOTP)
            if (passkeys.hasPasskey(userId)) add(AuthFactor.PASSKEY)
        }
}
