package idp.authentication.adapters.persistence

import idp.authentication.domain.TenantPolicy
import idp.db.jooq.tenant.tables.TenantPolicies.TENANT_POLICIES
import idp.db.jooq.tenant.tables.records.TenantPoliciesRecord
import idp.sharedkernel.persistence.VersionedUpdate
import org.jooq.DSLContext
import java.time.Duration

internal class TenantPolicies {
    private val versioned = VersionedUpdate(TENANT_POLICIES, TENANT_POLICIES.ID, TENANT_POLICIES.VERSION)

    /** The migration inserts the row, so every tenant has one. */
    fun load(dsl: DSLContext): TenantPolicy = dsl.selectFrom(TENANT_POLICIES).fetchSingle(::toPolicy)

    fun save(
        dsl: DSLContext,
        policy: TenantPolicy,
    ): Long =
        versioned.execute(
            dsl,
            true,
            policy.version,
            mapOf(
                TENANT_POLICIES.MFA_FOR_USERS to policy.mfaForUsers,
                TENANT_POLICIES.PASSKEY_REQUIRED to policy.passkeyRequired,
                TENANT_POLICIES.SESSION_IDLE_SECONDS to policy.sessionIdleTimeout.seconds,
                TENANT_POLICIES.SESSION_ABSOLUTE_SECONDS to policy.sessionAbsoluteTimeout.seconds,
            ),
        )

    private fun toPolicy(row: TenantPoliciesRecord) =
        TenantPolicy(
            row.mfaForUsers!!,
            row.passkeyRequired!!,
            Duration.ofSeconds(row.sessionIdleSeconds!!),
            Duration.ofSeconds(row.sessionAbsoluteSeconds!!),
            row.version!!,
        )
}
