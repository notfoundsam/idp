package idp.sharedkernel.throttle

import idp.sharedkernel.audit.Actor
import idp.sharedkernel.security.Principal
import idp.sharedkernel.security.SecurityContext
import idp.sharedkernel.tenant.TenantContext
import idp.sharedkernel.web.ClientIp
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private const val HMAC = "HmacSHA256"
private const val WHOLE_TENANT = "*"

/** The secret is shared by all instances, because they count in one bucket per address. */
class ThrottleKeys(
    private val secret: ByteArray,
) {
    fun key(
        scope: String,
        dimension: ThrottleDimension?,
    ): String {
        val tenant = TenantContext.current().tenantId
        val principal = SecurityContext.currentOrNull()
        return when {
            dimension == ThrottleDimension.TENANT -> key(tenant, ThrottleDimension.TENANT, scope, WHOLE_TENANT)
            dimension != ThrottleDimension.IP && principal != null -> key(tenant, ThrottleDimension.USER, scope, subject(principal))
            else -> key(tenant, ThrottleDimension.IP, scope, address(tenant))
        }
    }

    private fun key(
        tenant: UUID,
        dimension: ThrottleDimension,
        scope: String,
        subject: String,
    ) = "throttle:$tenant:$dimension:$scope:$subject"

    private fun subject(principal: Principal): String =
        when (val actor = principal.actor) {
            is Actor.User -> actor.id.toString()
            is Actor.Operator -> actor.name
            Actor.System, Actor.Anonymous -> error("A principal is somebody who proved who they are")
        }

    private fun address(tenant: UUID): String {
        val address = checkNotNull(ClientIp.currentOrNull()) { "No client address in the current scope" }.address
        val mac = Mac.getInstance(HMAC).apply { init(SecretKeySpec(secret, HMAC)) }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal("$tenant/$address".toByteArray()))
    }
}
