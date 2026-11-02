package idp.sharedkernel.security

import idp.sharedkernel.bus.Message
import kotlin.reflect.KClass

data class Permission(
    val name: String,
)

class AccessDeniedException(
    val authenticated: Boolean,
) : RuntimeException("Access denied")

class ReauthenticationRequiredException : RuntimeException("Reauthentication required")

/** A message that could take over an account: a session stolen hours ago must not be enough for it. */
interface RequiresFreshAuthentication

/** One per audience, so a role means only what its audience grants. A message missing here is refused. */
interface SecurityConfig {
    /** `null` makes a message explicitly public. */
    val permissions: Map<KClass<out Message<*>>, Permission?>
    val roles: Map<Role, Set<Permission>>
}
