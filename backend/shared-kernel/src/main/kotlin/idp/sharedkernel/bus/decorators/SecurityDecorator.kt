package idp.sharedkernel.bus.decorators

import idp.sharedkernel.audit.Actor
import idp.sharedkernel.audit.AuditLog
import idp.sharedkernel.audit.AuditRecord
import idp.sharedkernel.bus.Message
import idp.sharedkernel.security.AccessDeniedException
import idp.sharedkernel.security.Principal
import idp.sharedkernel.security.SecurityConfig
import idp.sharedkernel.security.SecurityContext
import idp.sharedkernel.web.ClientIp
import kotlin.reflect.KClass

private const val ACCESS_DENIED = "access.denied"
private const val MESSAGE = "message"

/** Runs before Transaction, so a denial needs no second connection and survives whatever happens next. */
class SecurityDecorator(
    configs: List<SecurityConfig>,
    private val audit: AuditLog,
) : BusDecorator {
    private val owners: Map<KClass<out Message<*>>, SecurityConfig> =
        configs.fold(emptyMap()) { all, config ->
            val repeated = all.keys intersect config.permissions.keys
            require(repeated.isEmpty()) { "One SecurityConfig owns a message: $repeated" }
            all + config.permissions.keys.associateWith { config }
        }

    val messages: Set<KClass<out Message<*>>> = owners.keys

    override fun <R> around(
        message: Message<R>,
        next: () -> R,
    ): R {
        val principal = SecurityContext.currentOrNull()
        if (allowed(message, principal)) return next()
        audit.recordDenial(
            AuditRecord(
                actor = principal?.actor ?: Actor.Anonymous,
                action = ACCESS_DENIED,
                ip = ClientIp.currentOrNull()?.address,
                details = mapOf(MESSAGE to message.javaClass.name),
            ),
        )
        throw AccessDeniedException(authenticated = principal != null)
    }

    private fun allowed(
        message: Message<*>,
        principal: Principal?,
    ): Boolean {
        val config = owners[message::class] ?: return false
        val permission = config.permissions[message::class] ?: return true
        return principal != null && permission in config.roles[principal.role].orEmpty()
    }
}
