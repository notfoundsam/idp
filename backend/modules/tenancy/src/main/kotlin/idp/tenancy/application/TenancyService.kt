package idp.tenancy.application

import idp.sharedkernel.audit.Actor
import idp.sharedkernel.audit.AuditLog
import idp.sharedkernel.audit.AuditRecord
import idp.sharedkernel.audit.AuditTarget
import idp.sharedkernel.crypto.FieldCipher
import idp.sharedkernel.domain.NotFound
import idp.sharedkernel.outbox.NewEvent
import idp.sharedkernel.outbox.OutboxPublisher
import idp.sharedkernel.tenant.PlatformTransaction
import idp.sharedkernel.tenant.TenantContext
import idp.sharedkernel.tenant.TenantStatus
import idp.tenancy.adapters.persistence.AdminReinvites
import idp.tenancy.adapters.persistence.TenantApplications
import idp.tenancy.adapters.persistence.Tenants
import idp.tenancy.domain.AdminReinvite
import idp.tenancy.domain.Subdomain
import idp.tenancy.domain.SubdomainTaken
import idp.tenancy.domain.Tenant
import idp.tenancy.domain.TenantApplication
import org.jooq.DSLContext
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

private const val APPLICATION_SUBMITTED = "tenant.application.submitted"
private const val APPROVED = "tenant.approved"
private const val SUSPENDED = "tenant.suspended"
private const val RESUMED = "tenant.resumed"
private const val OFFBOARDING_CANCELLED = "tenant.offboarding_cancelled"

class TenancyNotFound(
    id: UUID,
) : NotFound("No tenant or application $id")

/** Not a data class: `toString` must not print the contact. */
class NewApplication(
    val companyName: String,
    val subdomain: String,
    val contact: String,
)

data class TenantView(
    val id: UUID,
    val name: String,
    val status: TenantStatus,
    val version: Long,
    val provisioningStep: String?,
    val kekUri: String?,
    val offboardingAfter: Instant?,
    val previousKekUri: String? = null,
    val previousKekDestroyAfter: Instant? = null,
) {
    /** Everything that must go to make the tenant's data unreadable, backups included. */
    val keks: List<String> get() = listOfNotNull(kekUri, previousKekUri)
}

data class TenantApplicationView(
    val id: UUID,
    val tenantId: UUID,
    val companyName: String,
    val subdomain: String,
    val contactMask: String?,
    val status: String,
)

data class TenantSite(
    val name: String,
    val host: String,
)

/**
 * `ops` submits, approves, suspends and offboards. `worker` provisions, deletes and reads the contact.
 * Their KMS rights differ, so `contacts` only encrypts in `ops` and only decrypts in `worker`.
 */
class TenancyService(
    private val transaction: PlatformTransaction,
    private val audit: AuditLog,
    private val outbox: OutboxPublisher,
    private val contacts: FieldCipher,
    private val baseDomain: String,
    private val clock: Clock = Clock.systemUTC(),
    private val offboardingGrace: Duration = Duration.ofDays(30),
) {
    private val tenants = Tenants()
    private val applications = TenantApplications()
    private val reinvites = AdminReinvites()

    fun submit(
        application: NewApplication,
        actor: Actor.Operator,
        ip: String? = null,
    ): UUID = add(application.companyName, Subdomain.of(application.subdomain), application.contact, actor, ip)

    /**
     * The only way to the reserved name, so only the bootstrap of `ops` may call it. `ops` cannot reach a tenant schema, so the event
     * asks `worker` to make the tenant the IdP of `ops` once it is provisioned. One transaction holds every step, so a failed run leaves
     * the name free for the next one.
     */
    fun bootstrapServiceTenant(
        companyName: String,
        contact: String,
        actor: Actor.Operator,
        ip: String? = null,
    ): UUID =
        transaction.execute { dsl ->
            val application = add(companyName, Subdomain.serviceTenant(), contact, actor, ip)
            approve(application, actor, ip)
            val tenant = load(dsl, checkNotNull(applications.find(dsl, application)).tenantId)
            publish(dsl, tenant, tenant.version, TenancyEvents.SERVICE_TENANT_BOOTSTRAPPED)
            application
        }

    fun approve(
        applicationId: UUID,
        actor: Actor.Operator,
        ip: String? = null,
    ) {
        transaction.execute { dsl ->
            val application = applications.find(dsl, applicationId) ?: throw TenancyNotFound(applicationId)
            val tenant = load(dsl, application.tenantId)
            application.approve()
            tenant.approve()
            applications.saveStatus(dsl, application)
            val version = tenants.save(dsl, tenant)
            if (!tenants.addDomain(dsl, application.subdomain.host(baseDomain), tenant.id)) {
                throw SubdomainTaken(application.subdomain.value)
            }
            publish(dsl, tenant, version, TenancyEvents.APPROVED)
            audit.recordChange(AuditRecord(actor, APPROVED, AuditTarget.tenant(tenant.id), ip))
        }
    }

    fun suspend(
        tenantId: UUID,
        actor: Actor.Operator,
        ip: String? = null,
    ) {
        transaction.execute { dsl ->
            val tenant = load(dsl, tenantId).apply { suspend() }
            publish(dsl, tenant, tenants.save(dsl, tenant), TenancyEvents.SUSPENDED)
            audit.recordChange(AuditRecord(actor, SUSPENDED, AuditTarget.tenant(tenant.id), ip))
        }
    }

    fun resume(
        tenantId: UUID,
        actor: Actor.Operator,
        ip: String? = null,
    ) {
        transaction.execute { dsl ->
            val tenant = load(dsl, tenantId).apply { resume() }
            tenants.save(dsl, tenant)
            audit.recordChange(AuditRecord(actor, RESUMED, AuditTarget.tenant(tenant.id), ip))
        }
    }

    /** Returns the end of the waiting period. Until then the operator can cancel and the data stays. */
    fun startOffboarding(
        tenantId: UUID,
        actor: Actor.Operator,
        ip: String? = null,
    ): Instant {
        val after = clock.instant().plus(offboardingGrace).truncatedTo(ChronoUnit.SECONDS)
        transaction.execute { dsl ->
            val tenant = load(dsl, tenantId).apply { startOffboarding(after) }
            publish(dsl, tenant, tenants.save(dsl, tenant), TenancyEvents.OFFBOARDING_STARTED, availableAt = after)
            val details = mapOf("after" to after.toString())
            audit.recordChange(AuditRecord(actor, TenancyEvents.OFFBOARDING_STARTED, AuditTarget.tenant(tenant.id), ip, details))
        }
        return after
    }

    /** The waiting event stays in the outbox and finds a tenant of another version, so it does nothing. */
    fun cancelOffboarding(
        tenantId: UUID,
        actor: Actor.Operator,
        ip: String? = null,
    ) {
        transaction.execute { dsl ->
            val tenant = load(dsl, tenantId).apply { cancelOffboarding() }
            tenants.save(dsl, tenant)
            audit.recordChange(AuditRecord(actor, OFFBOARDING_CANCELLED, AuditTarget.tenant(tenant.id), ip))
        }
    }

    /** `worker` asks before it destroys anything, because its clock may be behind the one that set the waiting period. */
    fun ensureOffboardingDue(tenantId: UUID) {
        transaction.execute { dsl -> load(dsl, tenantId).ensureOffboardingDue(clock.instant()) }
    }

    /**
     * The last step of offboarding, after the KEK and the schema are gone. Not audited: the tenant's journal went with its schema,
     * and the operator's decision is in `platform.operator_audit`.
     */
    fun delete(tenantId: UUID) {
        transaction.execute { dsl -> tenants.save(dsl, load(dsl, tenantId).apply { delete(clock.instant()) }) }
    }

    /** False when the restored database has no such tenant or already knows it is deleted. */
    fun deleteAgain(tenantId: UUID): Boolean =
        transaction.execute { dsl ->
            val tenant = tenants.find(dsl, tenantId)?.takeIf { it.status != TenantStatus.DELETED } ?: return@execute false
            tenants.save(dsl, tenant.apply { deleteAgain() })
            true
        }

    /** The operator names an address and learns nothing of the tenant: whether it holds that address stays inside `worker`. */
    fun requestAdminReinvite(
        tenantId: UUID,
        contact: String,
        actor: Actor.Operator,
        ip: String? = null,
    ) {
        val reinvite = AdminReinvite.requested(UUID.randomUUID(), tenantId, contact, contacts)
        transaction.execute { dsl ->
            val tenant = load(dsl, tenantId).apply { ensureActive() }
            reinvites.add(dsl, reinvite)
            TenantContext.with(TenantContext(tenant.id, tenant.kekUri)) {
                val event = NewEvent(TenancyEvents.REINVITE_AGGREGATE, reinvite.id, 0, TenancyEvents.ADMIN_REINVITE_REQUESTED)
                outbox.publish(dsl, event)
            }
            audit.recordChange(AuditRecord(actor, TenancyEvents.ADMIN_REINVITE_REQUESTED, AuditTarget.tenant(tenant.id), ip))
        }
    }

    /** Null once [eraseReinvite] ran, so a redelivered event ends here. */
    fun reinviteContact(id: UUID): String? = transaction.execute { dsl -> reinvites.find(dsl, id) }?.contact(contacts)

    fun eraseReinvite(id: UUID) {
        transaction.execute { dsl -> reinvites.delete(dsl, id) }
    }

    fun startProvisioning(tenantId: UUID) {
        transaction.execute { dsl -> tenants.save(dsl, load(dsl, tenantId).apply { startProvisioning() }) }
    }

    fun recordProvisioningStep(
        tenantId: UUID,
        step: String,
        kekUri: String? = null,
    ) {
        transaction.execute { dsl -> tenants.save(dsl, load(dsl, tenantId).apply { recordStep(step, kekUri) }) }
    }

    fun activate(tenantId: UUID) {
        transaction.execute { dsl ->
            tenants.save(dsl, load(dsl, tenantId).apply { activate() })
            val application = applications.findWithContact(dsl, tenantId) ?: throw TenancyNotFound(tenantId)
            application.eraseContact()
            applications.saveContact(dsl, application)
        }
    }

    /** Not audited here: `worker` records the move in the tenant's journal together with the rewrap. */
    fun replaceKek(
        tenantId: UUID,
        next: String,
    ) {
        transaction.execute { dsl -> tenants.save(dsl, load(dsl, tenantId).apply { replaceKek(next) }) }
    }

    fun recordKekRewrapped(
        tenantId: UUID,
        keepPreviousFor: Duration,
    ) {
        transaction.execute { dsl -> tenants.save(dsl, load(dsl, tenantId).apply { recordRewrap(clock.instant().plus(keepPreviousFor)) }) }
    }

    /** After `worker` destroyed it, and only once the backups it opens have expired. */
    fun forgetPreviousKek(tenantId: UUID) {
        transaction.execute { dsl -> tenants.save(dsl, load(dsl, tenantId).apply { forgetPreviousKek(clock.instant()) }) }
    }

    /** Offboarding and a restore destroy every KEK of the other tenants, so a move has nothing to do there. */
    fun tenantsWithKek(): List<TenantView> =
        transaction.execute { dsl ->
            tenants.withKek(dsl, listOf(TenantStatus.ACTIVE, TenantStatus.SUSPENDED)).map { it.view() }
        }

    fun tenant(id: UUID): TenantView? = transaction.execute { dsl -> tenants.find(dsl, id)?.view() }

    fun application(id: UUID): TenantApplicationView? = transaction.execute { dsl -> applications.find(dsl, id)?.view() }

    fun contact(tenantId: UUID): String? = transaction.execute { dsl -> applications.findWithContact(dsl, tenantId) }?.contact(contacts)

    /** On a connection of its own, because a tenant handler calls it and a tenant role cannot read `platform`. */
    fun site(tenantId: UUID): TenantSite? =
        transaction.executeSeparately { dsl ->
            val tenant = tenants.find(dsl, tenantId) ?: return@executeSeparately null
            val host = tenants.domainOf(dsl, tenantId) ?: return@executeSeparately null
            TenantSite(tenant.name, host)
        }

    private fun add(
        companyName: String,
        subdomain: Subdomain,
        contact: String,
        actor: Actor.Operator,
        ip: String?,
    ): UUID {
        val tenant = Tenant.requested(UUID.randomUUID(), companyName)
        val application = TenantApplication.submitted(UUID.randomUUID(), tenant.id, companyName, subdomain, contact, contacts)
        transaction.execute { dsl ->
            if (applications.openFor(dsl, subdomain.value) || tenants.domainExists(dsl, subdomain.host(baseDomain))) {
                throw SubdomainTaken(subdomain.value)
            }
            tenants.add(dsl, tenant)
            applications.add(dsl, application)
            audit.recordChange(AuditRecord(actor, APPLICATION_SUBMITTED, AuditTarget.tenant(tenant.id), ip))
        }
        return application.id
    }

    private fun load(
        dsl: DSLContext,
        id: UUID,
    ): Tenant = tenants.find(dsl, id) ?: throw TenancyNotFound(id)

    /** The publisher takes `tenant_id` from the context, and `ops` runs outside any tenant. */
    private fun publish(
        dsl: DSLContext,
        tenant: Tenant,
        version: Long,
        type: String,
        availableAt: Instant? = null,
    ) {
        TenantContext.with(TenantContext(tenant.id, tenant.kekUri)) {
            outbox.publish(dsl, NewEvent(TenancyEvents.AGGREGATE, tenant.id, version, type, availableAt = availableAt))
        }
    }

    private fun Tenant.view() =
        TenantView(id, name, status, version, provisioningStep, kekUri, offboardingAfter, previousKekUri, previousKekDestroyAfter)

    private fun TenantApplication.view() = TenantApplicationView(id, tenantId, companyName, subdomain.value, contactMask, status.name)
}
