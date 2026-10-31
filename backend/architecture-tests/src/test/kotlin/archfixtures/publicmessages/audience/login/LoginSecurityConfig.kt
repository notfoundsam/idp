package archfixtures.publicmessages.audience.login

import idp.sharedkernel.bus.Message
import idp.sharedkernel.bus.Query
import idp.sharedkernel.security.Permission
import idp.sharedkernel.security.Role
import idp.sharedkernel.security.SecurityConfig
import kotlin.reflect.KClass

class Violation : Query<Unit>

object LoginSecurityConfig : SecurityConfig {
    override val permissions = mapOf<KClass<out Message<*>>, Permission?>(Violation::class to null)
    override val roles = emptyMap<Role, Set<Permission>>()
}
