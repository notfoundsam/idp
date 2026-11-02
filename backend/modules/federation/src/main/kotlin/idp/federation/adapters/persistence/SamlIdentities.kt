package idp.federation.adapters.persistence

import idp.db.jooq.tenant.tables.SamlIdentity.SAML_IDENTITY
import org.jooq.DSLContext

internal class SamlIdentityRow(
    val entityId: String,
    val host: String,
)

internal class SamlIdentities {
    /** Keeps the first row, because the EntityID must not change under SPs that already trust it. */
    fun add(
        dsl: DSLContext,
        identity: SamlIdentityRow,
    ) {
        dsl
            .insertInto(SAML_IDENTITY)
            .set(SAML_IDENTITY.ENTITY_ID, identity.entityId)
            .set(SAML_IDENTITY.HOST, identity.host)
            .onConflictDoNothing()
            .execute()
    }

    fun find(dsl: DSLContext): SamlIdentityRow? = dsl.selectFrom(SAML_IDENTITY).fetchOne { SamlIdentityRow(it.entityId!!, it.host!!) }
}
