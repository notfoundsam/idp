package idp.authentication.adapters.persistence

import idp.authentication.domain.AuthFactor
import idp.authentication.domain.SsoSession
import idp.db.jooq.tenant.tables.SsoSessions.SSO_SESSIONS
import idp.db.jooq.tenant.tables.records.SsoSessionsRecord
import idp.sharedkernel.persistence.VersionedUpdate
import idp.sharedkernel.scheduling.TenantCleanup
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

internal class Sessions {
    private val versioned = VersionedUpdate(SSO_SESSIONS, SSO_SESSIONS.ID, SSO_SESSIONS.VERSION)

    fun add(
        dsl: DSLContext,
        session: SsoSession,
    ) {
        dsl
            .insertInto(SSO_SESSIONS)
            .set(SSO_SESSIONS.ID, session.id)
            .set(SSO_SESSIONS.USER_ID, session.userId)
            .set(SSO_SESSIONS.TOKEN_HASH, session.tokenHash)
            .set(SSO_SESSIONS.FACTORS, session.factors.map { it.name }.toTypedArray())
            .set(SSO_SESSIONS.IP_CIPHERTEXT, session.ipCiphertext)
            .set(SSO_SESSIONS.USER_AGENT_CIPHERTEXT, session.userAgentCiphertext)
            .set(SSO_SESSIONS.AUTHENTICATED_AT, session.authenticatedAt.utc())
            .set(SSO_SESSIONS.LAST_SEEN_AT, session.lastSeenAt.utc())
            .execute()
    }

    fun find(
        dsl: DSLContext,
        id: UUID,
    ): SsoSession? = dsl.selectFrom(SSO_SESSIONS).where(SSO_SESSIONS.ID.eq(id)).fetchOne(::toSession)

    fun findByTokenHash(
        dsl: DSLContext,
        tokenHash: ByteArray,
    ): SsoSession? = dsl.selectFrom(SSO_SESSIONS).where(SSO_SESSIONS.TOKEN_HASH.eq(tokenHash)).fetchOne(::toSession)

    /** One statement outside the version: parallel requests must not conflict over it, and a late one must not move it back. */
    fun markSeen(
        dsl: DSLContext,
        id: UUID,
        now: Instant,
    ) {
        dsl
            .update(SSO_SESSIONS)
            .set(SSO_SESSIONS.LAST_SEEN_AT, now.utc())
            .where(SSO_SESSIONS.ID.eq(id), SSO_SESSIONS.LAST_SEEN_AT.lt(now.utc()))
            .execute()
    }

    /** By version, so a session gets one successor even when two flows finish at once. */
    fun save(
        dsl: DSLContext,
        session: SsoSession,
    ): Long = versioned.execute(dsl, session.id, session.version, mapOf(SSO_SESSIONS.REVOKED_AT to session.revokedAt?.utc()))

    /** Keeps the [limit] sessions seen last. Call it after adding the new one. */
    fun revokeBeyond(
        dsl: DSLContext,
        userId: UUID,
        limit: Int,
        now: Instant,
    ): Int {
        val overflow =
            dsl
                .select(SSO_SESSIONS.ID)
                .from(SSO_SESSIONS)
                .where(SSO_SESSIONS.USER_ID.eq(userId), SSO_SESSIONS.REVOKED_AT.isNull)
                .orderBy(SSO_SESSIONS.LAST_SEEN_AT.desc(), SSO_SESSIONS.ID)
                .offset(limit)
        return revoke(dsl, SSO_SESSIONS.ID.`in`(overflow), now)
    }

    fun revokeOfUser(
        dsl: DSLContext,
        userId: UUID,
        now: Instant,
    ): Int = revoke(dsl, SSO_SESSIONS.USER_ID.eq(userId), now)

    fun revokeAll(
        dsl: DSLContext,
        now: Instant,
    ): Int = revoke(dsl, DSL.noCondition(), now)

    private fun revoke(
        dsl: DSLContext,
        which: Condition,
        now: Instant,
    ): Int =
        dsl
            .update(SSO_SESSIONS)
            .set(SSO_SESSIONS.REVOKED_AT, now.utc())
            .set(SSO_SESSIONS.VERSION, SSO_SESSIONS.VERSION.plus(1))
            .where(which, SSO_SESSIONS.REVOKED_AT.isNull)
            .execute()

    private fun Instant.utc(): OffsetDateTime = atOffset(ZoneOffset.UTC)

    private fun toSession(row: SsoSessionsRecord) =
        SsoSession(
            row.id!!,
            row.userId!!,
            row.tokenHash!!,
            row.factors!!.map { AuthFactor.valueOf(it!!) }.toSet(),
            row.ipCiphertext,
            row.userAgentCiphertext,
            row.authenticatedAt!!.toInstant(),
            row.lastSeenAt!!.toInstant(),
            row.revokedAt?.toInstant(),
            row.version!!,
        )
}

internal class SessionCleanup : TenantCleanup {
    private val policies = TenantPolicies()

    override val name: String = "sso-sessions"

    override fun delete(
        dsl: DSLContext,
        now: OffsetDateTime,
    ) {
        val policy = policies.load(dsl)
        dsl
            .deleteFrom(SSO_SESSIONS)
            .where(
                SSO_SESSIONS.REVOKED_AT.isNotNull
                    .or(SSO_SESSIONS.LAST_SEEN_AT.le(now.minus(policy.sessionIdleTimeout)))
                    .or(SSO_SESSIONS.AUTHENTICATED_AT.le(now.minus(policy.sessionAbsoluteTimeout))),
            ).execute()
    }
}
