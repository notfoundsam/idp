package idp.authentication.application

import idp.authentication.adapters.persistence.FlowCleanup
import idp.authentication.adapters.persistence.Flows
import idp.authentication.adapters.persistence.SessionCleanup
import idp.authentication.adapters.persistence.Sessions
import idp.authentication.adapters.persistence.TenantPolicies
import idp.authentication.domain.AuthFactor
import idp.authentication.domain.AuthFlow
import idp.authentication.domain.FlowExpired
import idp.authentication.domain.FlowStep
import idp.authentication.domain.FlowUser
import idp.authentication.domain.PriorAuthentication
import idp.authentication.domain.SessionToken
import idp.authentication.domain.SpRequirements
import idp.authentication.domain.SsoSession
import idp.authentication.domain.StepNotAllowed
import idp.authentication.domain.TenantPolicy
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
import idp.sharedkernel.scheduling.TenantCleanup
import idp.sharedkernel.security.Principal
import idp.sharedkernel.security.Role
import idp.sharedkernel.security.UserStatus
import idp.sharedkernel.tenant.TenantContext
import idp.sharedkernel.tenant.TenantTransaction
import org.jooq.DSLContext
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

private const val SESSION_OPENED = "session.opened"
private const val SESSIONS_REVOKED_FOR_USER = "session.revoked_for_user"
private const val SESSIONS_REVOKED_FOR_TENANT = "session.revoked_for_tenant"

private const val LOGIN_FIELD = "flows.login_ciphertext"
private const val IP_FIELD = "sso_sessions.ip_ciphertext"
private const val USER_AGENT_FIELD = "sso_sessions.user_agent_ciphertext"

/** Not a data class: `toString` must not print the token. */
class IssuedSession(
    val token: String,
) {
    override fun toString(): String = "IssuedSession(***)"
}

/** [session] is null when the browser keeps the session it has: nothing new was proved, so there is nothing to rotate. */
class CompletedFlow(
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
) {
    private val policies = TenantPolicies()
    private val flows = Flows()
    private val sessions = Sessions()
    private val random = SecureRandom()

    val flowCleanup: TenantCleanup = FlowCleanup()

    val sessionCleanup: TenantCleanup = SessionCleanup()

    /** A session in [sessionToken] that is no longer good counts as none. */
    fun startFlow(
        continuation: FlowContinuation? = null,
        sessionToken: String? = null,
    ): UUID =
        transaction.execute { dsl ->
            val now = clock.instant()
            val policy = policies.load(dsl)
            val prior = sessionToken?.let { prior(dsl, it, policy, now) }
            val requirements = continuation?.requirements() ?: SpRequirements()
            val flow = AuthFlow.start(UUID.randomUUID(), policy, requirements, continuation?.returnUrl, prior, now, flowTtl)
            flows.add(dsl, flow)
            flow.id
        }

    fun identify(
        flowId: UUID,
        login: String,
    ) {
        onFlow(flowId) { flow, now ->
            val user = identity.findByEmail(login)?.let(::flowUser)
            flow.identify(user, keys.derivedCipher(DerivedKey.FLOW).encrypt(login.toByteArray(), FieldContext(LOGIN_FIELD, flowId)), now)
        }
    }

    /** Loads the flow with what is true now, lets [change] walk it and stores the result. A parallel step loses by version. */
    internal fun <T> onFlow(
        flowId: UUID,
        change: (AuthFlow, Instant) -> T,
    ): T =
        transaction.execute { dsl ->
            val flow = load(dsl, flowId)
            change(flow, clock.instant()).also { flows.save(dsl, flow) }
        }

    /**
     * Consumes the flow. A flow that took over a session fails once that session is gone, otherwise a revoked session would hand its
     * factors on. It also keeps the first authentication of the session, or repeating flows would escape the absolute timeout.
     */
    fun completeFlow(
        flowId: UUID,
        ip: String?,
        userAgent: String?,
    ): CompletedFlow =
        transaction.execute { dsl ->
            val now = clock.instant()
            val flow = load(dsl, flowId)
            if (flow.step(now) != FlowStep.Complete) throw StepNotAllowed()
            val userId = checkNotNull(flow.userId)
            if (identity.user(userId)?.status != UserStatus.ACTIVE) throw StepNotAllowed()
            flows.delete(dsl, flow)
            val previous = flow.sessionId?.let { sessions.find(dsl, it) }?.takeIf { it.isLive(flow.policy, now) }
            if (flow.continuesSession && previous == null) throw FlowExpired()
            if (flow.continuesSession && previous?.factors == flow.proved) return@execute CompletedFlow(flow.returnUrl, null)
            previous?.let { sessions.save(dsl, it.apply { revoke(now) }) }
            val authenticatedAt = previous?.authenticatedAt?.takeIf { flow.continuesSession } ?: now
            CompletedFlow(flow.returnUrl, open(dsl, userId, flow.proved, authenticatedAt, ip, userAgent, now))
        }

    /** The role and the status are read on every request, so a change reaches a live session on its next request. */
    fun resolveSession(token: String): Principal? =
        transaction.execute { dsl ->
            val now = clock.instant()
            val session = sessions.findByTokenHash(dsl, SessionToken(token).hash())?.takeIf { it.isLive(policies.load(dsl), now) }
            val user = session?.let { identity.user(it.userId) }?.takeIf { it.status == UserStatus.ACTIVE } ?: return@execute null
            if (session.isDueForLastSeen(now, lastSeenInterval)) sessions.markSeen(dsl, session.id, now)
            Principal(Actor.User(user.id), Role(user.role.name))
        }

    /** Joins the transaction of the caller, because a block or a reset that rolled back must leave the sessions alone. */
    fun revokeUserSessions(
        userId: UUID,
        actor: Actor,
        ip: String? = null,
    ) {
        transaction.join { dsl ->
            val revoked = sessions.revokeOfUser(dsl, userId, clock.instant())
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

    /** A flow whose id is gone reads as expired, so a guess learns nothing. */
    private fun load(
        dsl: DSLContext,
        flowId: UUID,
    ): AuthFlow {
        val policy = policies.load(dsl)
        return flows.find(dsl, flowId, policy) { userId -> identity.user(userId)?.let(::flowUser) } ?: throw FlowExpired()
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

    private fun flowUser(user: UserView): FlowUser {
        val enrolled =
            buildSet {
                if (totp.hasConfirmedTotp(user.id)) add(AuthFactor.TOTP)
                if (passkeys.hasPasskey(user.id)) add(AuthFactor.PASSKEY)
            }
        return FlowUser(user.id, user.role, enrolled)
    }
}
