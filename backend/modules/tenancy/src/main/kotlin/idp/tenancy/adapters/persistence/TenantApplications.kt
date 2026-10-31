package idp.tenancy.adapters.persistence

import idp.db.jooq.platform.tables.TenantApplications.TENANT_APPLICATIONS
import idp.db.jooq.platform.tables.records.TenantApplicationsRecord
import idp.sharedkernel.persistence.VersionedUpdate
import idp.tenancy.domain.ApplicationStatus
import idp.tenancy.domain.Subdomain
import idp.tenancy.domain.TenantApplication
import org.jooq.Condition
import org.jooq.DSLContext
import java.util.UUID

internal class TenantApplications {
    private val versioned = VersionedUpdate(TENANT_APPLICATIONS, TENANT_APPLICATIONS.ID, TENANT_APPLICATIONS.VERSION)

    fun add(
        dsl: DSLContext,
        application: TenantApplication,
    ) {
        dsl
            .insertInto(TENANT_APPLICATIONS)
            .set(TENANT_APPLICATIONS.ID, application.id)
            .set(TENANT_APPLICATIONS.TENANT_ID, application.tenantId)
            .set(TENANT_APPLICATIONS.COMPANY_NAME, application.companyName)
            .set(TENANT_APPLICATIONS.REQUESTED_SUBDOMAIN, application.subdomain.value)
            .set(TENANT_APPLICATIONS.CONTACT_CIPHERTEXT, application.contactCiphertext)
            .set(TENANT_APPLICATIONS.CONTACT_MASK, application.contactMask)
            .set(TENANT_APPLICATIONS.STATUS, application.status.name)
            .execute()
    }

    fun find(
        dsl: DSLContext,
        id: UUID,
    ): TenantApplication? = findWhere(dsl, TENANT_APPLICATIONS.ID.eq(id))

    fun findByTenant(
        dsl: DSLContext,
        tenantId: UUID,
    ): TenantApplication? = findWhere(dsl, TENANT_APPLICATIONS.TENANT_ID.eq(tenantId))

    fun save(
        dsl: DSLContext,
        application: TenantApplication,
    ): Long =
        versioned.execute(
            dsl,
            application.id,
            application.version,
            mapOf(
                TENANT_APPLICATIONS.CONTACT_CIPHERTEXT to application.contactCiphertext,
                TENANT_APPLICATIONS.CONTACT_MASK to application.contactMask,
                TENANT_APPLICATIONS.STATUS to application.status.name,
            ),
        )

    fun openFor(
        dsl: DSLContext,
        subdomain: String,
    ): Boolean =
        dsl.fetchExists(
            TENANT_APPLICATIONS,
            TENANT_APPLICATIONS.REQUESTED_SUBDOMAIN.eq(subdomain),
            TENANT_APPLICATIONS.STATUS.eq(ApplicationStatus.SUBMITTED.name),
        )

    private fun findWhere(
        dsl: DSLContext,
        condition: Condition,
    ): TenantApplication? = dsl.selectFrom(TENANT_APPLICATIONS).where(condition).fetchOne(::toApplication)

    private fun toApplication(row: TenantApplicationsRecord) =
        TenantApplication(
            row.id!!,
            row.tenantId!!,
            row.companyName!!,
            Subdomain.stored(row.requestedSubdomain!!),
            row.contactCiphertext,
            row.contactMask,
            ApplicationStatus.valueOf(row.status!!),
            row.version!!,
        )
}
