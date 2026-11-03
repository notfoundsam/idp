package idp.audience.account

import idp.audience.account.application.command.BeginPasskeySetup
import idp.audience.account.application.command.BeginTotpSetup
import idp.audience.account.application.command.ChangePassword
import idp.audience.account.application.command.ConfirmTotpSetup
import idp.audience.account.application.command.FinishPasskeySetup
import idp.audience.account.application.command.RemovePasskey
import idp.audience.account.application.command.RemoveTotp
import idp.audience.account.application.command.RenewRecoveryCodes
import idp.audience.account.application.query.GetFactors
import idp.sharedkernel.bus.Message
import idp.sharedkernel.security.Permission
import idp.sharedkernel.security.Role
import idp.sharedkernel.security.SecurityConfig
import idp.sharedkernel.security.UserRole
import kotlin.reflect.KClass

private val OWN_ACCOUNT = Permission("account.own")

/** Every user manages their own account, whatever their role. No message names a user: each handler takes the one of the session. */
object AccountSecurityConfig : SecurityConfig {
    override val permissions: Map<KClass<out Message<*>>, Permission?> =
        mapOf(
            GetFactors::class to OWN_ACCOUNT,
            ChangePassword::class to OWN_ACCOUNT,
            BeginTotpSetup::class to OWN_ACCOUNT,
            ConfirmTotpSetup::class to OWN_ACCOUNT,
            RemoveTotp::class to OWN_ACCOUNT,
            BeginPasskeySetup::class to OWN_ACCOUNT,
            FinishPasskeySetup::class to OWN_ACCOUNT,
            RemovePasskey::class to OWN_ACCOUNT,
            RenewRecoveryCodes::class to OWN_ACCOUNT,
        )

    override val roles: Map<Role, Set<Permission>> = UserRole.entries.associate { Role(it.name) to setOf(OWN_ACCOUNT) }
}
