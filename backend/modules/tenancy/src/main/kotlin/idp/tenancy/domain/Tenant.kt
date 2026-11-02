package idp.tenancy.domain

import idp.sharedkernel.domain.DomainRuleViolation
import idp.sharedkernel.tenant.TenantStatus
import idp.sharedkernel.tenant.TenantStatus.ACTIVE
import idp.sharedkernel.tenant.TenantStatus.APPROVED
import idp.sharedkernel.tenant.TenantStatus.DELETED
import idp.sharedkernel.tenant.TenantStatus.OFFBOARDING
import idp.sharedkernel.tenant.TenantStatus.PROVISIONING
import idp.sharedkernel.tenant.TenantStatus.REQUESTED
import idp.sharedkernel.tenant.TenantStatus.SUSPENDED
import java.time.Instant
import java.util.UUID

class ForbiddenTenantChange(
    message: String,
) : DomainRuleViolation(message)

internal class Tenant(
    val id: UUID,
    val name: String,
    status: TenantStatus,
    val version: Long,
    provisioningStep: String?,
    kekUri: String?,
    offboardingAfter: Instant?,
) {
    var status = status
        private set
    var provisioningStep = provisioningStep
        private set
    var kekUri = kekUri
        private set
    var offboardingAfter = offboardingAfter
        private set

    fun approve() = move(REQUESTED, to = APPROVED)

    fun startProvisioning() = move(APPROVED, to = PROVISIONING)

    /** The step survives a crash of `worker`, so the next delivery resumes after it. */
    fun recordStep(
        step: String,
        kekUri: String? = null,
    ) {
        if (status != PROVISIONING) throw ForbiddenTenantChange("A tenant in $status records no provisioning step")
        if (step.isBlank()) throw ForbiddenTenantChange("A provisioning step needs a name")
        if (kekUri != null && this.kekUri != null && kekUri != this.kekUri) {
            throw ForbiddenTenantChange("Tenant $id already has a KEK")
        }
        provisioningStep = step
        this.kekUri = kekUri ?: this.kekUri
    }

    fun activate() {
        if (status == PROVISIONING && (provisioningStep == null || kekUri == null)) {
            throw ForbiddenTenantChange("Tenant $id has not finished provisioning")
        }
        move(PROVISIONING, to = ACTIVE)
    }

    fun ensureActive() {
        if (status != ACTIVE) throw ForbiddenTenantChange("A tenant in $status takes no new admin")
    }

    fun suspend() = move(ACTIVE, to = SUSPENDED)

    fun resume() = move(SUSPENDED, to = ACTIVE)

    /** Only from a suspended tenant: its sessions are already revoked, so cancelling and resuming brings none of them back. */
    fun startOffboarding(after: Instant) {
        move(SUSPENDED, to = OFFBOARDING)
        offboardingAfter = after
    }

    /** Stays suspended: nobody decided to serve this tenant again, resuming is a separate step. */
    fun cancelOffboarding() {
        move(OFFBOARDING, to = SUSPENDED)
        offboardingAfter = null
    }

    fun ensureOffboardingDue(now: Instant) {
        val after = offboardingAfter
        if (status != OFFBOARDING) throw ForbiddenTenantChange("A tenant in $status is not offboarding")
        if (after != null && now.isBefore(after)) throw ForbiddenTenantChange("Tenant $id waits until $after")
    }

    fun delete(now: Instant) {
        ensureOffboardingDue(now)
        move(OFFBOARDING, to = DELETED)
    }

    /** After a restore the journal outranks the row: the tenant was deleted, whatever status the backup kept. */
    fun deleteAgain() {
        status = DELETED
        offboardingAfter = null
    }

    private fun move(
        vararg from: TenantStatus,
        to: TenantStatus,
    ) {
        if (status !in from) throw ForbiddenTenantChange("A tenant cannot go from $status to $to")
        status = to
    }

    companion object {
        fun requested(
            id: UUID,
            name: String,
        ): Tenant {
            if (name.isBlank()) throw InvalidApplication("A tenant needs a name")
            return Tenant(id, name, REQUESTED, version = 0, provisioningStep = null, kekUri = null, offboardingAfter = null)
        }
    }
}
