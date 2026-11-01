package idp.audience.operator

import idp.audience.operator.application.command.ApproveApplication
import idp.audience.operator.application.command.BootstrapServiceTenant
import idp.audience.operator.application.command.ResumeTenant
import idp.audience.operator.application.command.SubmitApplication
import idp.audience.operator.application.command.SuspendTenant
import idp.audience.operator.application.query.GetApplication
import idp.audience.operator.application.query.GetTenant
import idp.sharedkernel.bus.Message
import idp.sharedkernel.security.Permission
import idp.sharedkernel.security.Role
import idp.sharedkernel.security.SecurityConfig
import kotlin.reflect.KClass

private val MANAGE = Permission("tenants.manage")
private val READ = Permission("tenants.read")
private val BOOTSTRAP = Permission("tenants.bootstrap")

object OperatorSecurityConfig : SecurityConfig {
    val OPERATOR = Role("Operator")

    /** Whoever IAM lets start the one-off container. Only this role bootstraps, and the API never holds it. */
    val OPERATOR_CLI = Role("OperatorCli")

    override val permissions: Map<KClass<out Message<*>>, Permission?> =
        mapOf(
            SubmitApplication::class to MANAGE,
            ApproveApplication::class to MANAGE,
            SuspendTenant::class to MANAGE,
            ResumeTenant::class to MANAGE,
            GetTenant::class to READ,
            GetApplication::class to READ,
            BootstrapServiceTenant::class to BOOTSTRAP,
        )

    override val roles: Map<Role, Set<Permission>> =
        mapOf(
            OPERATOR to setOf(MANAGE, READ),
            OPERATOR_CLI to setOf(MANAGE, READ, BOOTSTRAP),
        )
}
