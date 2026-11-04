package idp.audience.admin

import idp.audience.admin.application.command.AssignUsers
import idp.audience.admin.application.command.BlockUser
import idp.audience.admin.application.command.ChangeRole
import idp.audience.admin.application.command.ChangeSpSettings
import idp.audience.admin.application.command.CompleteKeyRotation
import idp.audience.admin.application.command.EndAllSessions
import idp.audience.admin.application.command.EndUserSessions
import idp.audience.admin.application.command.EraseUser
import idp.audience.admin.application.command.InviteUser
import idp.audience.admin.application.command.RegisterServiceProvider
import idp.audience.admin.application.command.RemoveServiceProvider
import idp.audience.admin.application.command.ResendInvitation
import idp.audience.admin.application.command.ResetMfa
import idp.audience.admin.application.command.SavePolicy
import idp.audience.admin.application.command.StartKeyRotation
import idp.audience.admin.application.command.UnassignUsers
import idp.audience.admin.application.command.UnblockUser
import idp.audience.admin.application.query.FindUserByEmail
import idp.audience.admin.application.query.GetAssignedUsers
import idp.audience.admin.application.query.GetPolicy
import idp.audience.admin.application.query.GetServiceProvider
import idp.audience.admin.application.query.GetUserCard
import idp.audience.admin.application.query.ListServiceProviders
import idp.audience.admin.application.query.ListSigningKeys
import idp.sharedkernel.bus.Message
import idp.sharedkernel.security.Permission
import idp.sharedkernel.security.Role
import idp.sharedkernel.security.SecurityConfig
import idp.sharedkernel.security.UserRole
import kotlin.reflect.KClass

private val MANAGE_USERS = Permission("admin.users")
private val MANAGE_POLICY = Permission("admin.policy")
private val MANAGE_SERVICE_PROVIDERS = Permission("admin.service_providers")
private val MANAGE_SIGNING_KEYS = Permission("admin.signing_keys")

/**
 * Only an admin of the tenant of the request. A session belongs to the tenant whose schema holds it, so an admin of another tenant has
 * no session here, and a user of another tenant reads as missing.
 */
object AdminSecurityConfig : SecurityConfig {
    override val permissions: Map<KClass<out Message<*>>, Permission?> =
        mapOf(
            FindUserByEmail::class to MANAGE_USERS,
            GetUserCard::class to MANAGE_USERS,
            InviteUser::class to MANAGE_USERS,
            ResendInvitation::class to MANAGE_USERS,
            ChangeRole::class to MANAGE_USERS,
            BlockUser::class to MANAGE_USERS,
            UnblockUser::class to MANAGE_USERS,
            EraseUser::class to MANAGE_USERS,
            ResetMfa::class to MANAGE_USERS,
            EndUserSessions::class to MANAGE_USERS,
            EndAllSessions::class to MANAGE_USERS,
            GetPolicy::class to MANAGE_POLICY,
            SavePolicy::class to MANAGE_POLICY,
            ListServiceProviders::class to MANAGE_SERVICE_PROVIDERS,
            GetServiceProvider::class to MANAGE_SERVICE_PROVIDERS,
            RegisterServiceProvider::class to MANAGE_SERVICE_PROVIDERS,
            ChangeSpSettings::class to MANAGE_SERVICE_PROVIDERS,
            RemoveServiceProvider::class to MANAGE_SERVICE_PROVIDERS,
            GetAssignedUsers::class to MANAGE_SERVICE_PROVIDERS,
            AssignUsers::class to MANAGE_SERVICE_PROVIDERS,
            UnassignUsers::class to MANAGE_SERVICE_PROVIDERS,
            ListSigningKeys::class to MANAGE_SIGNING_KEYS,
            StartKeyRotation::class to MANAGE_SIGNING_KEYS,
            CompleteKeyRotation::class to MANAGE_SIGNING_KEYS,
        )

    override val roles: Map<Role, Set<Permission>> =
        mapOf(Role(UserRole.TENANT_ADMIN.name) to setOf(MANAGE_USERS, MANAGE_POLICY, MANAGE_SERVICE_PROVIDERS, MANAGE_SIGNING_KEYS))
}
