package idp.audience.operator.application.query

import idp.sharedkernel.bus.MessageHandler
import idp.sharedkernel.bus.Query
import idp.sharedkernel.bus.TransactionScope
import idp.tenancy.application.TenancyNotFound
import idp.tenancy.application.TenancyService
import java.util.UUID

data class GetApplication(
    val id: UUID,
) : Query<ApplicationResponse> {
    override val transaction = TransactionScope.PLATFORM
}

data class ApplicationResponse(
    val id: UUID,
    val tenantId: UUID,
    val companyName: String,
    val subdomain: String,
    val contactMask: String?,
    val status: String,
)

class GetApplicationHandler(
    private val tenancy: TenancyService,
) : MessageHandler<GetApplication, ApplicationResponse> {
    override val messageType = GetApplication::class

    override fun handle(message: GetApplication): ApplicationResponse {
        val application = tenancy.application(message.id) ?: throw TenancyNotFound(message.id)
        return ApplicationResponse(
            application.id,
            application.tenantId,
            application.companyName,
            application.subdomain,
            application.contactMask,
            application.status,
        )
    }
}
