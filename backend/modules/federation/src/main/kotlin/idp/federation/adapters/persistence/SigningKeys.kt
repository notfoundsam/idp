package idp.federation.adapters.persistence

import idp.db.jooq.tenant.tables.SamlSigningKeys.SAML_SIGNING_KEYS
import idp.db.jooq.tenant.tables.records.SamlSigningKeysRecord
import idp.federation.domain.SigningKey
import idp.federation.domain.SigningKeyRing
import idp.federation.domain.SigningKeyStatus
import idp.federation.domain.x509
import org.jooq.DSLContext
import java.time.ZoneOffset
import java.util.UUID

internal class SigningKeys {
    fun add(
        dsl: DSLContext,
        key: SigningKey,
    ) {
        dsl
            .insertInto(SAML_SIGNING_KEYS)
            .set(SAML_SIGNING_KEYS.ID, key.id)
            .set(SAML_SIGNING_KEYS.STATUS, key.status.name)
            .set(SAML_SIGNING_KEYS.CERTIFICATE, key.certificate.encoded)
            .set(SAML_SIGNING_KEYS.PRIVATE_KEY_CIPHERTEXT, key.privateKeyCiphertext)
            .set(SAML_SIGNING_KEYS.CREATED_AT, key.createdAt.atOffset(ZoneOffset.UTC))
            .execute()
    }

    fun ring(dsl: DSLContext): SigningKeyRing = SigningKeyRing(dsl.selectFrom(SAML_SIGNING_KEYS).fetch(::toKey))

    /** Locks the table, so two rotations at once cannot both pass the check of the other, even before any row exists. */
    fun lockedRing(dsl: DSLContext): SigningKeyRing {
        dsl.execute("LOCK TABLE {0} IN SHARE ROW EXCLUSIVE MODE", SAML_SIGNING_KEYS)
        return ring(dsl)
    }

    fun saveStatus(
        dsl: DSLContext,
        key: SigningKey,
    ) {
        dsl
            .update(SAML_SIGNING_KEYS)
            .set(SAML_SIGNING_KEYS.STATUS, key.status.name)
            .where(SAML_SIGNING_KEYS.ID.eq(key.id))
            .execute()
    }

    fun delete(
        dsl: DSLContext,
        id: UUID,
    ) {
        dsl.deleteFrom(SAML_SIGNING_KEYS).where(SAML_SIGNING_KEYS.ID.eq(id)).execute()
    }

    private fun toKey(row: SamlSigningKeysRecord) =
        SigningKey(
            row.id!!,
            SigningKeyStatus.valueOf(row.status!!),
            x509(row.certificate!!),
            row.privateKeyCiphertext!!,
            row.createdAt!!.toInstant(),
        )
}
