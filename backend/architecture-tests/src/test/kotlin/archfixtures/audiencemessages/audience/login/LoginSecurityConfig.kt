package archfixtures.audiencemessages.audience.login

import idp.sharedkernel.bus.Command
import idp.sharedkernel.bus.Message
import idp.sharedkernel.security.Permission
import idp.sharedkernel.security.Role
import idp.sharedkernel.security.SecurityConfig
import kotlin.reflect.KClass

class Listed : Command<Unit>

object LoginSecurityConfig : SecurityConfig {
    override val permissions = mapOf<KClass<out Message<*>>, Permission?>(Listed::class to Permission("listed"))
    override val roles = emptyMap<Role, Set<Permission>>()
}
