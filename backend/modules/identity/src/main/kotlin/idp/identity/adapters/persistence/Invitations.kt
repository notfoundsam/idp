package idp.identity.adapters.persistence

import idp.db.jooq.tenant.tables.Invitations.INVITATIONS
import idp.identity.domain.Invitation
import idp.sharedkernel.scheduling.TenantCleanup
import org.jooq.DSLContext
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

internal class Invitations {
    /** The old hash goes with the row, so a new invitation cancels the old one. */
    fun replace(
        dsl: DSLContext,
        invitation: Invitation,
    ) {
        val expiresAt = invitation.expiresAt.atOffset(ZoneOffset.UTC)
        dsl
            .insertInto(INVITATIONS)
            .set(INVITATIONS.USER_ID, invitation.userId)
            .set(INVITATIONS.TOKEN_HASH, invitation.tokenHash)
            .set(INVITATIONS.EXPIRES_AT, expiresAt)
            .onConflict(INVITATIONS.USER_ID)
            .doUpdate()
            .set(INVITATIONS.TOKEN_HASH, invitation.tokenHash)
            .set(INVITATIONS.EXPIRES_AT, expiresAt)
            .execute()
    }

    /** One statement, so of two concurrent attempts only one gets the row. Null when there is nothing live to accept. */
    fun consume(
        dsl: DSLContext,
        tokenHash: ByteArray,
        now: Instant,
    ): UUID? =
        dsl
            .deleteFrom(INVITATIONS)
            .where(INVITATIONS.TOKEN_HASH.eq(tokenHash), INVITATIONS.EXPIRES_AT.gt(now.atOffset(ZoneOffset.UTC)))
            .returningResult(INVITATIONS.USER_ID)
            .fetchOne(INVITATIONS.USER_ID)
}

internal class InvitationCleanup : TenantCleanup {
    override val name: String = "invitations"

    override fun delete(
        dsl: DSLContext,
        now: OffsetDateTime,
    ) {
        dsl.deleteFrom(INVITATIONS).where(INVITATIONS.EXPIRES_AT.le(now)).execute()
    }
}
