package idp.tenancy.application

object TenancyEvents {
    const val AGGREGATE = "tenant"
    const val REINVITE_AGGREGATE = "admin_reinvite"
    const val APPROVED = "tenant.approved"
    const val SUSPENDED = "tenant.suspended"
    const val ADMIN_REINVITE_REQUESTED = "tenant.admin_reinvite_requested"
}
