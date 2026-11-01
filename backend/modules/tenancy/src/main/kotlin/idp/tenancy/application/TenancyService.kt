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
import idp.tenancy.adapters.persistence.TenantApplications
import idp.tenancy.adapters.persistence.Tenants
import idp.tenancy.domain.Subdomain
import idp.tenancy.domain.SubdomainTaken
import idp.tenancy.domain.Tenant
import idp.tenancy.domain.TenantApplication
import org.jooq.DSLContext
import java.util.UUID

private const val APPLICATION_SUBMITTED = "tenant.application.submitted"
private const val APPROVED = "tenant.approved"
private const val SUSPENDED = "tenant.suspended"
private const val RESUMED = "tenant.resumed"

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
)

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
 * `ops` submits, approves and suspends. `worker` provisions and reads the contact.
 * Their KMS rights differ, so `contacts` only encrypts in `ops` and only decrypts in `worker`.
 */
class TenancyService(
    private val transaction: PlatformTransaction,
    private val audit: AuditLog,
    private val outbox: OutboxPublisher,
    private val contacts: FieldCipher,
    private val baseDomain: String,
) {
    private val tenants = Tenants()
    private val applications = TenantApplications()

    fun submit(
        application: NewApplication,
        actor: Actor.Operator,
        ip: String? = null,
    ): UUID = add(application.companyName, Subdomain.of(application.subdomain), application.contact, actor, ip)

    /** The only way to a reserved name, so only the bootstrap of `ops` may call it. */
    fun submitServiceTenant(
        companyName: String,
        contact: String,
        actor: Actor.Operator,
        ip: String? = null,
    ): UUID = add(companyName, Subdomain.serviceTenant(), contact, actor, ip)

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
    ) {
        TenantContext.with(TenantContext(tenant.id, tenant.kekUri)) {
            outbox.publish(dsl, NewEvent(TenancyEvents.AGGREGATE, tenant.id, version, type))
        }
    }

    private fun Tenant.view() = TenantView(id, name, status, version, provisioningStep, kekUri)

    private fun TenantApplication.view() = TenantApplicationView(id, tenantId, companyName, subdomain.value, contactMask, status.name)
}
