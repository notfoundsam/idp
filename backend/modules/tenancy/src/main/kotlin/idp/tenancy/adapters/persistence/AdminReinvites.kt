package idp.tenancy.adapters.persistence

import idp.db.jooq.platform.tables.AdminReinvites.ADMIN_REINVITES
import idp.tenancy.domain.AdminReinvite
import org.jooq.DSLContext
import java.util.UUID

internal class AdminReinvites {
    fun add(
        dsl: DSLContext,
        reinvite: AdminReinvite,
    ) {
        dsl
            .insertInto(ADMIN_REINVITES)
            .set(ADMIN_REINVITES.ID, reinvite.id)
            .set(ADMIN_REINVITES.TENANT_ID, reinvite.tenantId)
            .set(ADMIN_REINVITES.CONTACT_CIPHERTEXT, reinvite.contactCiphertext)
            .execute()
    }

    /** `ops_runtime` has no right to this table, only `worker_runtime` reads it. */
    fun find(
        dsl: DSLContext,
        id: UUID,
    ): AdminReinvite? =
        dsl.selectFrom(ADMIN_REINVITES).where(ADMIN_REINVITES.ID.eq(id)).fetchOne { row ->
            AdminReinvite(row.id!!, row.tenantId!!, row.contactCiphertext!!)
        }

    fun delete(
        dsl: DSLContext,
        id: UUID,
    ) {
        dsl.deleteFrom(ADMIN_REINVITES).where(ADMIN_REINVITES.ID.eq(id)).execute()
    }
}
