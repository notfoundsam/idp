package idp.sharedkernel.bus.decorators

import idp.sharedkernel.audit.Actor
import idp.sharedkernel.audit.AuditLog
import idp.sharedkernel.audit.AuditRecord
import idp.sharedkernel.bus.Message
import idp.sharedkernel.security.AccessDeniedException
import idp.sharedkernel.security.Principal
import idp.sharedkernel.security.ReauthenticationRequiredException
import idp.sharedkernel.security.RequiresFreshAuthentication
import idp.sharedkernel.security.SecurityConfig
import idp.sharedkernel.security.SecurityContext
import idp.sharedkernel.web.ClientIp
import java.time.Clock
import java.time.Duration
import kotlin.reflect.KClass

private const val ACCESS_DENIED = "access.denied"
private const val REAUTHENTICATION_REQUIRED = "access.reauthentication_required"
private const val MESSAGE = "message"

/** Runs before Transaction, so a denial needs no second connection and survives whatever happens next. */
class SecurityDecorator(
    configs: List<SecurityConfig>,
    private val audit: AuditLog,
    private val clock: Clock = Clock.systemUTC(),
    private val freshness: Duration = Duration.ofMinutes(10),
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
        if (!allowed(message, principal)) {
            deny(message, principal, ACCESS_DENIED)
            throw AccessDeniedException(authenticated = principal != null)
        }
        if (message is RequiresFreshAuthentication && !isFresh(principal)) {
            deny(message, principal, REAUTHENTICATION_REQUIRED)
            throw ReauthenticationRequiredException()
        }
        return next()
    }

    private fun deny(
        message: Message<*>,
        principal: Principal?,
        action: String,
    ) {
        audit.recordDenial(
            AuditRecord(
                actor = principal?.actor ?: Actor.Anonymous,
                action = action,
                ip = ClientIp.currentOrNull()?.address,
                details = mapOf(MESSAGE to message.javaClass.name),
            ),
        )
    }

    private fun isFresh(principal: Principal?): Boolean =
        principal?.authenticatedAt?.let { clock.instant().isBefore(it.plus(freshness)) } == true

    private fun allowed(
        message: Message<*>,
        principal: Principal?,
    ): Boolean {
        val config = owners[message::class] ?: return false
        val permission = config.permissions[message::class] ?: return true
        return principal != null && permission in config.roles[principal.role].orEmpty()
    }
}
