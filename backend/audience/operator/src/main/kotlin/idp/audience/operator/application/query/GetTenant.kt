package idp.audience.operator.application.query

import idp.sharedkernel.bus.MessageHandler
import idp.sharedkernel.bus.Query
import idp.sharedkernel.bus.TransactionScope
import idp.tenancy.application.TenancyNotFound
import idp.tenancy.application.TenancyService
import java.util.UUID

data class GetTenant(
    val id: UUID,
) : Query<TenantResponse> {
    override val transaction = TransactionScope.PLATFORM
}

data class TenantResponse(
    val id: UUID,
    val name: String,
    val status: String,
    val provisioningStep: String?,
)

class GetTenantHandler(
    private val tenancy: TenancyService,
) : MessageHandler<GetTenant, TenantResponse> {
    override val messageType = GetTenant::class

    override fun handle(message: GetTenant): TenantResponse {
        val tenant = tenancy.tenant(message.id) ?: throw TenancyNotFound(message.id)
        return TenantResponse(tenant.id, tenant.name, tenant.status.name, tenant.provisioningStep)
    }
}
