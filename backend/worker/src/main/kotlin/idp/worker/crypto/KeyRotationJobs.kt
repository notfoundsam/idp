package idp.worker.crypto

import idp.sharedkernel.audit.Actor
import idp.sharedkernel.audit.AuditLog
import idp.sharedkernel.audit.AuditRecord
import idp.sharedkernel.audit.AuditTarget
import idp.sharedkernel.crypto.KeyHierarchy
import idp.sharedkernel.crypto.KeyManagementService
import idp.sharedkernel.observability.Failures
import idp.sharedkernel.scheduling.JobLease
import idp.sharedkernel.scheduling.ScheduledJob
import idp.sharedkernel.tenant.TenantContext
import idp.sharedkernel.tenant.TenantTransaction
import idp.tenancy.application.TenancyNotFound
import idp.tenancy.application.TenancyService
import idp.tenancy.application.TenantView
import idp.worker.forEachTenantWithKek
import java.time.Clock
import java.time.Duration

const val KEK_REPLACED = "tenant.kek_replaced"
const val PREVIOUS_KEK_DESTROYED = "tenant.previous_kek_destroyed"

/**
 * Moves every tenant whose KEK lives outside the KMS of new keys. Only the tenant key is wrapped again, everything under it stays
 * as it is. A rotation inside one KMS needs no job: the KMS keeps the old versions of a key for decryption.
 *
 * The URI changes before the rewrap, and each step is safe to repeat: a run cut short leaves both KEKs in `platform.tenants`,
 * and `KeyHierarchy` tries both.
 */
class KekMoveJob(
    private val tenancy: TenancyService,
    private val kms: KeyManagementService,
    private val keys: KeyHierarchy,
    private val transaction: TenantTransaction,
    private val audit: AuditLog,
    private val newKeys: String,
    private val keepPrevious: Duration,
    private val failures: Failures,
    override val interval: Duration = Duration.ofHours(1),
) : ScheduledJob {
    override val name: String = "kek-move"

    override fun run(lease: JobLease) {
        tenancy.forEachTenantWithKek(name, lease, failures) { tenant ->
            when {
                tenant.previousKekUri == null && !tenant.kekUri!!.startsWith("$newKeys://") -> rewrap(replace(tenant))
                tenant.previousKekUri != null && tenant.previousKekDestroyAfter == null -> rewrap(tenant)
            }
        }
    }

    /** The alias follows the version, so a run that dies before saving the URI gets the same key back the next time. */
    private fun replace(tenant: TenantView): TenantView {
        val next = kms.createKey("tenant-${tenant.id}-v${tenant.version}")
        tenancy.replaceKek(tenant.id, next)
        return tenancy.tenant(tenant.id) ?: throw TenancyNotFound(tenant.id)
    }

    private fun rewrap(tenant: TenantView) {
        val previous = checkNotNull(tenant.previousKekUri)
        val current = checkNotNull(tenant.kekUri)
        TenantContext.with(TenantContext(tenant.id, current)) {
            transaction.atomically {
                if (keys.rewrapTenantKey(previous, current)) {
                    val record = AuditRecord(Actor.System, KEK_REPLACED, AuditTarget.tenant(tenant.id), details = mapOf("kms" to newKeys))
                    audit.recordChange(record)
                }
            }
        }
        tenancy.recordKekRewrapped(tenant.id, keepPrevious)
    }
}

/** Destroys a KEK left by [KekMoveJob] once the backups it opens have expired. */
class PreviousKekJob(
    private val tenancy: TenancyService,
    private val kms: KeyManagementService,
    private val transaction: TenantTransaction,
    private val audit: AuditLog,
    private val failures: Failures,
    private val clock: Clock = Clock.systemUTC(),
    override val interval: Duration = Duration.ofHours(1),
) : ScheduledJob {
    override val name: String = "kek-destroy-previous"

    override fun run(lease: JobLease) {
        tenancy.forEachTenantWithKek(name, lease, failures) { tenant ->
            val previous = tenant.previousKekUri
            val after = tenant.previousKekDestroyAfter
            if (previous != null && after != null && !clock.instant().isBefore(after)) {
                kms.destroy(previous)
                val record = AuditRecord(Actor.System, PREVIOUS_KEK_DESTROYED, AuditTarget.tenant(tenant.id))
                TenantContext.with(TenantContext(tenant.id, tenant.kekUri)) { transaction.atomically { audit.recordChange(record) } }
                tenancy.forgetPreviousKek(tenant.id)
            }
        }
    }
}
