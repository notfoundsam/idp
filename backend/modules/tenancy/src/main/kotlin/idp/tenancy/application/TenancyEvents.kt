package idp.tenancy.application

object TenancyEvents {
    const val AGGREGATE = "tenant"
    const val REINVITE_AGGREGATE = "admin_reinvite"
    const val APPROVED = "tenant.approved"
    const val SUSPENDED = "tenant.suspended"

    /** Waits in the outbox until the end of the waiting period. */
    const val OFFBOARDING_STARTED = "tenant.offboarding_started"
    const val ADMIN_REINVITE_REQUESTED = "tenant.admin_reinvite_requested"
    const val SERVICE_TENANT_BOOTSTRAPPED = "tenant.service_tenant_bootstrapped"
}
