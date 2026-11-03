package idp.federation.adapters.persistence

import idp.db.jooq.tenant.tables.SamlRequestIds.SAML_REQUEST_IDS
import idp.db.jooq.tenant.tables.SamlRequests.SAML_REQUESTS
import idp.db.jooq.tenant.tables.records.SamlRequestsRecord
import idp.sharedkernel.scheduling.TenantCleanup
import org.jooq.DSLContext
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

internal class SamlRequestRow(
    val id: UUID,
    val spId: UUID,
    val acsUrl: String,
    val requestCiphertext: ByteArray,
    val relayStateCiphertext: ByteArray?,
    val acceptedAt: Instant,
    val expiresAt: Instant,
)

internal class SamlRequests {
    /** False when the SP sent this ID before: the row is the whole replay check, so two parallel copies cannot both pass. */
    fun remember(
        dsl: DSLContext,
        spId: UUID,
        idHash: ByteArray,
        until: Instant,
    ): Boolean =
        dsl
            .insertInto(SAML_REQUEST_IDS)
            .set(SAML_REQUEST_IDS.SP_ID, spId)
            .set(SAML_REQUEST_IDS.ID_HASH, idHash)
            .set(SAML_REQUEST_IDS.EXPIRES_AT, until.atOffset(ZoneOffset.UTC))
            .onConflictDoNothing()
            .execute() == 1

    fun add(
        dsl: DSLContext,
        row: SamlRequestRow,
    ) {
        dsl
            .insertInto(SAML_REQUESTS)
            .set(SAML_REQUESTS.ID, row.id)
            .set(SAML_REQUESTS.SP_ID, row.spId)
            .set(SAML_REQUESTS.ACS_URL, row.acsUrl)
            .set(SAML_REQUESTS.REQUEST_CIPHERTEXT, row.requestCiphertext)
            .set(SAML_REQUESTS.RELAY_STATE_CIPHERTEXT, row.relayStateCiphertext)
            .set(SAML_REQUESTS.ACCEPTED_AT, row.acceptedAt.atOffset(ZoneOffset.UTC))
            .set(SAML_REQUESTS.EXPIRES_AT, row.expiresAt.atOffset(ZoneOffset.UTC))
            .execute()
    }

    /** One statement, so of two browsers that come back with one URL only the first gets the request. */
    fun claim(
        dsl: DSLContext,
        id: UUID,
        browserHash: ByteArray,
        now: Instant,
    ): SamlRequestRow? =
        dsl
            .update(SAML_REQUESTS)
            .set(SAML_REQUESTS.BROWSER_HASH, browserHash)
            .where(SAML_REQUESTS.ID.eq(id), SAML_REQUESTS.BROWSER_HASH.isNull, SAML_REQUESTS.EXPIRES_AT.gt(now.atOffset(ZoneOffset.UTC)))
            .returning()
            .fetchOne()
            ?.row()

    /** Deletes the request in the statement that finds it, so two tabs that come back with one URL get one Response between them. */
    fun consume(
        dsl: DSLContext,
        id: UUID,
        browserHash: ByteArray,
        now: Instant,
    ): SamlRequestRow? =
        dsl
            .deleteFrom(SAML_REQUESTS)
            .where(
                SAML_REQUESTS.ID.eq(id),
                SAML_REQUESTS.BROWSER_HASH.eq(browserHash),
                SAML_REQUESTS.EXPIRES_AT.gt(now.atOffset(ZoneOffset.UTC)),
            ).returning()
            .fetchOne()
            ?.row()

    private fun SamlRequestsRecord.row() =
        SamlRequestRow(id!!, spId!!, acsUrl!!, requestCiphertext!!, relayStateCiphertext, acceptedAt!!.toInstant(), expiresAt!!.toInstant())

    fun delete(
        dsl: DSLContext,
        id: UUID,
    ) {
        dsl.deleteFrom(SAML_REQUESTS).where(SAML_REQUESTS.ID.eq(id)).execute()
    }

    /** Stops counting at [limit], like the open flows. */
    fun countOpen(
        dsl: DSLContext,
        now: Instant,
        limit: Int,
    ): Int =
        dsl.fetchCount(
            dsl
                .selectOne()
                .from(SAML_REQUESTS)
                .where(SAML_REQUESTS.EXPIRES_AT.gt(now.atOffset(ZoneOffset.UTC)))
                .limit(limit),
        )
}

internal class SamlRequestCleanup : TenantCleanup {
    override val name: String = "saml-requests"

    override fun delete(
        dsl: DSLContext,
        now: OffsetDateTime,
    ) {
        dsl.deleteFrom(SAML_REQUESTS).where(SAML_REQUESTS.EXPIRES_AT.le(now)).execute()
        dsl.deleteFrom(SAML_REQUEST_IDS).where(SAML_REQUEST_IDS.EXPIRES_AT.le(now)).execute()
    }
}
