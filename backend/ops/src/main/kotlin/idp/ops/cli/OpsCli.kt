package idp.ops.cli

import idp.audience.operator.OperatorSecurityConfig
import idp.audience.operator.application.command.ApproveApplication
import idp.audience.operator.application.command.BootstrapServiceTenant
import idp.audience.operator.application.command.ResumeTenant
import idp.audience.operator.application.command.SubmitApplication
import idp.audience.operator.application.command.SuspendTenant
import idp.audience.operator.application.query.GetApplication
import idp.audience.operator.application.query.GetTenant
import idp.ops.OpsApplication
import idp.sharedkernel.audit.Actor
import idp.sharedkernel.bus.CommandBus
import idp.sharedkernel.bus.QueryBus
import idp.sharedkernel.domain.DomainRuleViolation
import idp.sharedkernel.domain.NotFound
import idp.sharedkernel.persistence.VersionConflictException
import idp.sharedkernel.security.AccessDeniedException
import idp.sharedkernel.security.CallerIdentity
import idp.sharedkernel.security.Principal
import idp.sharedkernel.security.SecurityContext
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import java.io.PrintStream
import java.util.UUID

const val CLI_COMMAND = "cli"

private const val SUCCESS = 0
private const val REFUSED = 1
private const val USAGE = 2

private class Operation(
    val arguments: String,
    val run: (List<String>) -> String,
) {
    val arity = arguments.split(' ').size
}

private class BadId : RuntimeException()

/**
 * IAM admits whoever starts this container, so it works while the IdP is down.
 * It takes the same way through the bus as the API: Security decides, and handlers audit under the actor proved by IAM.
 */
class OpsCli(
    private val commands: CommandBus,
    private val queries: QueryBus,
    private val identity: CallerIdentity,
    private val out: PrintStream = System.out,
    private val err: PrintStream = System.err,
) {
    private val operations =
        mapOf(
            "submit" to
                Operation("<company> <subdomain> <contact>") { (company, subdomain, contact) ->
                    "application ${commands.dispatch(SubmitApplication(company, subdomain, contact))}"
                },
            "approve" to
                Operation("<application-id>") { (id) ->
                    commands.dispatch(ApproveApplication(idOf(id)))
                    "approved $id"
                },
            "suspend" to
                Operation("<tenant-id>") { (id) ->
                    commands.dispatch(SuspendTenant(idOf(id)))
                    "suspended $id"
                },
            "resume" to
                Operation("<tenant-id>") { (id) ->
                    commands.dispatch(ResumeTenant(idOf(id)))
                    "resumed $id"
                },
            "tenant" to
                Operation("<tenant-id>") { (id) ->
                    val tenant = queries.ask(GetTenant(idOf(id)))
                    "id=${tenant.id} name=${tenant.name} status=${tenant.status} provisioningStep=${tenant.provisioningStep}"
                },
            "application" to
                Operation("<application-id>") { (id) ->
                    val application = queries.ask(GetApplication(idOf(id)))
                    "id=${application.id} tenantId=${application.tenantId} company=${application.companyName} " +
                        "subdomain=${application.subdomain} contact=${application.contactMask} status=${application.status}"
                },
            "bootstrap" to
                Operation("<company> <contact>") { (company, contact) ->
                    "application ${commands.dispatch(BootstrapServiceTenant(company, contact))}"
                },
        )

    fun run(args: List<String>): Int {
        val operation = operations[args.firstOrNull()]
        if (operation == null || args.size - 1 != operation.arity) return usage()
        return try {
            val principal = Principal(Actor.Operator(identity.name()), OperatorSecurityConfig.OPERATOR_CLI)
            out.println(SecurityContext.with(principal) { operation.run(args.drop(1)) })
            SUCCESS
        } catch (_: BadId) {
            usage()
        } catch (failure: RuntimeException) {
            if (!failure.isRefusal()) throw failure
            err.println("refused: ${failure.javaClass.simpleName}")
            REFUSED
        }
    }

    private fun idOf(value: String): UUID =
        try {
            UUID.fromString(value)
        } catch (_: IllegalArgumentException) {
            throw BadId()
        }

    private fun usage(): Int {
        err.println("usage:")
        operations.forEach { (name, operation) -> err.println("  $CLI_COMMAND $name ${operation.arguments}") }
        return USAGE
    }

    /** Exception texts can carry personal data, so the output names the type alone. */
    private fun RuntimeException.isRefusal() =
        this is DomainRuleViolation || this is NotFound || this is VersionConflictException || this is AccessDeniedException
}

fun runCli(
    operation: List<String>,
    vararg settings: String,
): Int =
    SpringApplicationBuilder(OpsApplication::class.java)
        .web(WebApplicationType.NONE)
        .run(*settings)
        .use { it.getBean(OpsCli::class.java).run(operation) }
