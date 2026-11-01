package idp.authentication.adapters.persistence

import idp.authentication.domain.Assurance
import idp.authentication.domain.AuthFactor
import idp.authentication.domain.AuthFlow
import idp.authentication.domain.FlowUser
import idp.authentication.domain.SpRequirements
import idp.authentication.domain.TenantPolicy
import idp.db.jooq.tenant.tables.Flows.FLOWS
import idp.db.jooq.tenant.tables.records.FlowsRecord
import idp.sharedkernel.persistence.VersionConflictException
import idp.sharedkernel.persistence.VersionedUpdate
import idp.sharedkernel.scheduling.TenantCleanup
import org.jooq.DSLContext
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

internal class Flows {
    private val versioned = VersionedUpdate(FLOWS, FLOWS.ID, FLOWS.VERSION)

    fun add(
        dsl: DSLContext,
        flow: AuthFlow,
    ) {
        dsl
            .insertInto(FLOWS)
            .set(FLOWS.ID, flow.id)
            .set(FLOWS.USER_ID, flow.userId)
            .set(FLOWS.LOGIN_CIPHERTEXT, flow.login)
            .set(FLOWS.PROVED, flow.proved.names())
            .set(FLOWS.REQUIRED_ASSURANCE, flow.requirements.assurance.name)
            .set(FLOWS.FRESH_LOGIN, flow.requirements.freshLogin)
            .set(FLOWS.RETURN_URL, flow.returnUrl)
            .set(FLOWS.SESSION_ID, flow.sessionId)
            .set(FLOWS.EXPIRES_AT, flow.expiresAt.atOffset(ZoneOffset.UTC))
            .execute()
    }

    /** The role, the factors and the policy are not stored: they come from the caller, so a change reaches a flow in progress. */
    fun find(
        dsl: DSLContext,
        id: UUID,
        policy: TenantPolicy,
        userOf: (UUID) -> FlowUser?,
    ): AuthFlow? =
        dsl.selectFrom(FLOWS).where(FLOWS.ID.eq(id)).fetchOne()?.let { row ->
            AuthFlow(
                row.id!!,
                row.userId?.let(userOf),
                row.loginCiphertext,
                row.factors(),
                policy,
                SpRequirements(Assurance.valueOf(row.requiredAssurance!!), row.freshLogin!!),
                row.returnUrl,
                row.expiresAt!!.toInstant(),
                row.sessionId,
                row.version!!,
            )
        }

    fun save(
        dsl: DSLContext,
        flow: AuthFlow,
    ): Long =
        versioned.execute(
            dsl,
            flow.id,
            flow.version,
            mapOf(FLOWS.USER_ID to flow.userId, FLOWS.LOGIN_CIPHERTEXT to flow.login, FLOWS.PROVED to flow.proved.names()),
        )

    /** By version, so of two parallel completions only one opens a session. */
    fun delete(
        dsl: DSLContext,
        flow: AuthFlow,
    ) {
        val deleted = dsl.deleteFrom(FLOWS).where(FLOWS.ID.eq(flow.id), FLOWS.VERSION.eq(flow.version)).execute()
        if (deleted == 0) throw VersionConflictException(FLOWS.name, flow.id, flow.version)
    }

    private fun Set<AuthFactor>.names(): Array<String> = map { it.name }.toTypedArray()

    private fun FlowsRecord.factors(): Set<AuthFactor> = proved!!.map { AuthFactor.valueOf(it!!) }.toSet()
}

internal class FlowCleanup : TenantCleanup {
    override val name: String = "flows"

    override fun delete(
        dsl: DSLContext,
        now: OffsetDateTime,
    ) {
        dsl.deleteFrom(FLOWS).where(FLOWS.EXPIRES_AT.le(now)).execute()
    }
}
