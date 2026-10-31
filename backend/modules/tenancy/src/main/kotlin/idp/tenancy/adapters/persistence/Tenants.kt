package idp.tenancy.adapters.persistence

import idp.db.jooq.platform.tables.TenantDomains.TENANT_DOMAINS
import idp.db.jooq.platform.tables.Tenants.TENANTS
import idp.sharedkernel.persistence.VersionedUpdate
import idp.sharedkernel.tenant.TenantStatus
import idp.tenancy.domain.Tenant
import org.jooq.DSLContext
import java.time.ZoneOffset
import java.util.UUID

internal class Tenants {
    private val versioned = VersionedUpdate(TENANTS, TENANTS.ID, TENANTS.VERSION)

    fun add(
        dsl: DSLContext,
        tenant: Tenant,
    ) {
        dsl
            .insertInto(TENANTS)
            .set(TENANTS.ID, tenant.id)
            .set(TENANTS.NAME, tenant.name)
            .set(TENANTS.STATUS, tenant.status.name)
            .execute()
    }

    fun find(
        dsl: DSLContext,
        id: UUID,
    ): Tenant? =
        dsl.selectFrom(TENANTS).where(TENANTS.ID.eq(id)).fetchOne { row ->
            Tenant(
                row.id!!,
                row.name!!,
                TenantStatus.valueOf(row.status!!),
                row.version!!,
                row.provisioningStep,
                row.kekUri,
                row.offboardingAfter?.toInstant(),
            )
        }

    fun save(
        dsl: DSLContext,
        tenant: Tenant,
    ): Long =
        versioned.execute(
            dsl,
            tenant.id,
            tenant.version,
            mapOf(
                TENANTS.STATUS to tenant.status.name,
                TENANTS.PROVISIONING_STEP to tenant.provisioningStep,
                TENANTS.KEK_URI to tenant.kekUri,
                TENANTS.OFFBOARDING_AFTER to tenant.offboardingAfter?.atOffset(ZoneOffset.UTC),
            ),
        )

    fun domainExists(
        dsl: DSLContext,
        domain: String,
    ): Boolean = dsl.fetchExists(TENANT_DOMAINS, TENANT_DOMAINS.DOMAIN.eq(domain))

    /** False when the domain already belongs to someone. */
    fun addDomain(
        dsl: DSLContext,
        domain: String,
        tenantId: UUID,
    ): Boolean =
        dsl
            .insertInto(TENANT_DOMAINS)
            .set(TENANT_DOMAINS.DOMAIN, domain)
            .set(TENANT_DOMAINS.TENANT_ID, tenantId)
            .onConflictDoNothing()
            .execute() == 1
}
