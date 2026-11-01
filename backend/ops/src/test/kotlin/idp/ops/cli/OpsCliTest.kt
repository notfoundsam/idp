package idp.ops.cli

import idp.audience.operator.OperatorSecurityConfig
import idp.audience.operator.application.command.BootstrapServiceTenant
import idp.ops.CONTACT
import idp.ops.MASK
import idp.ops.OpsApplication
import idp.ops.OpsEnvironment
import idp.sharedkernel.audit.Actor
import idp.sharedkernel.bus.CommandBus
import idp.sharedkernel.bus.QueryBus
import idp.sharedkernel.security.AccessDeniedException
import idp.sharedkernel.security.CallerIdentity
import idp.sharedkernel.security.Principal
import idp.sharedkernel.security.SecurityContext
import idp.sharedkernel.tenant.TenantStatus
import idp.tenancy.application.TenancyEvents
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.UUID

private const val IAM_ACTOR = "arn:aws:sts::123456789012:assumed-role/ops-cli/alice"

class OpsCliTest :
    FunSpec({
        val environment = OpsEnvironment()
        val application =
            SpringApplicationBuilder(OpsApplication::class.java)
                .web(WebApplicationType.NONE)
                .run(*environment.settings, "--spring.profiles.active=local")
        val commands = application.getBean(CommandBus::class.java)
        val queries = application.getBean(QueryBus::class.java)
        val sql = environment.sql
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val cli = OpsCli(commands, queries, CallerIdentity { IAM_ACTOR }, PrintStream(out), PrintStream(err))

        afterSpec { application.close() }

        beforeTest {
            out.reset()
            err.reset()
            sql.update("DELETE FROM platform.operator_audit")
        }

        fun printed() = out.toString().trim()

        fun submit(subdomain: String): UUID {
            cli.run(listOf("submit", "Acme", subdomain, CONTACT)) shouldBe 0
            return UUID.fromString(printed().substringAfterLast(' ')).also { out.reset() }
        }

        fun tenantOf(application: UUID): UUID =
            sql.queryForObject("SELECT tenant_id FROM platform.tenant_applications WHERE id = ?", UUID::class.java, application)!!

        fun statusOf(tenant: UUID): String? =
            sql.queryForObject("SELECT status FROM platform.tenants WHERE id = ?", String::class.java, tenant)

        context("operations") {
            test("submit, approve, suspend and resume work and are audited with the IAM actor") {
                val id = submit("cli")
                val tenant = tenantOf(id)

                cli.run(listOf("approve", id.toString())) shouldBe 0
                sql.update("UPDATE platform.tenants SET status = 'ACTIVE', version = version + 1 WHERE id = ?", tenant)
                cli.run(listOf("suspend", tenant.toString())) shouldBe 0
                statusOf(tenant) shouldBe TenantStatus.SUSPENDED.name
                cli.run(listOf("resume", tenant.toString())) shouldBe 0
                statusOf(tenant) shouldBe TenantStatus.ACTIVE.name

                val records = environment.audit()
                records.map { it["action"] }.shouldContainExactly(
                    "tenant.application.submitted",
                    "tenant.approved",
                    "tenant.suspended",
                    "tenant.resumed",
                )
                records.forEach {
                    it["actor"] shouldBe IAM_ACTOR
                    it["tenant_id"] shouldBe tenant
                }
            }

            test("reads print the same data as the API, with the mask only") {
                val id = submit("read")

                cli.run(listOf("application", id.toString())) shouldBe 0
                printed() shouldContain MASK
                printed() shouldNotContain CONTACT
                out.reset()

                cli.run(listOf("tenant", tenantOf(id).toString())) shouldBe 0
                printed() shouldContain "status=REQUESTED"
                printed() shouldNotContain "hcvault"
            }
        }

        context("failures") {
            test("a broken rule exits with 1 and names the rule, not the data") {
                submit("broken")

                cli.run(listOf("submit", "Acme", "broken", CONTACT)) shouldBe 1

                err.toString().trim() shouldBe "refused: SubdomainTaken"
            }

            test("a missing tenant exits with 1") {
                cli.run(listOf("suspend", UUID.randomUUID().toString())) shouldBe 1

                err.toString().trim() shouldBe "refused: TenancyNotFound"
            }

            test("a rejected contact does not reach the output") {
                cli.run(listOf("submit", "Acme", "contact", "secret-person")) shouldBe 1

                err.toString() shouldNotContain "secret-person"
            }

            listOf(
                emptyList(),
                listOf("delete-everything"),
                listOf("approve"),
                listOf("approve", "not-an-id"),
                listOf("approve", UUID.randomUUID().toString(), "extra"),
            ).forEach { arguments ->
                test("'${arguments.joinToString(" ")}' exits with 2 and prints the usage") {
                    cli.run(arguments) shouldBe 2

                    err.toString() shouldContain "usage"
                    environment.audit().shouldBeEmpty()
                }
            }
        }

        context("identity") {
            test("a failure to prove the actor stops the operation") {
                val anonymous = OpsCli(commands, queries, { error("STS is down") }, PrintStream(out), PrintStream(err))

                shouldThrow<IllegalStateException> { anonymous.run(listOf("submit", "Acme", "unproven", CONTACT)) }

                sql.queryForObject(
                    "SELECT count(*) FROM platform.tenant_applications WHERE requested_subdomain = 'unproven'",
                    Int::class.java,
                ) shouldBe 0
            }
        }

        context("bootstrap") {
            val fresh = OpsEnvironment()
            val other =
                SpringApplicationBuilder(OpsApplication::class.java)
                    .web(WebApplicationType.NONE)
                    .run(*fresh.settings, "--spring.profiles.active=local")
            val bootstrap =
                OpsCli(
                    other.getBean(CommandBus::class.java),
                    other.getBean(QueryBus::class.java),
                    CallerIdentity { IAM_ACTOR },
                    PrintStream(out),
                    PrintStream(err),
                )

            afterSpec { other.close() }

            test("is refused for the role of the API") {
                shouldThrow<AccessDeniedException> {
                    SecurityContext.with(Principal(Actor.Operator("oncall"), OperatorSecurityConfig.OPERATOR)) {
                        other.getBean(CommandBus::class.java).dispatch(BootstrapServiceTenant("Platform", CONTACT))
                    }
                }

                fresh.audit().map { it["action"] } shouldBe listOf("access.denied")
                fresh.sql.queryForObject("SELECT count(*) FROM platform.tenants", Int::class.java) shouldBe 0
            }

            test("applies and approves the service tenant the usual way, and a second run is refused") {
                fresh.sql.update("DELETE FROM platform.operator_audit")

                bootstrap.run(listOf("bootstrap", "Platform", CONTACT)) shouldBe 0

                val tenant = fresh.sql.queryForObject("SELECT tenant_id FROM platform.tenant_domains", UUID::class.java)!!
                fresh.sql.queryForObject(
                    "SELECT domain FROM platform.tenant_domains WHERE tenant_id = ?",
                    String::class.java,
                    tenant,
                ) shouldBe "ops-sso.idp.test"
                fresh.events(tenant).shouldContainExactly(TenancyEvents.APPROVED)
                fresh.audit().map { it["action"] }.shouldContainExactlyInAnyOrder("tenant.application.submitted", "tenant.approved")
                fresh.audit().forEach { it["actor"] shouldBe IAM_ACTOR }

                bootstrap.run(listOf("bootstrap", "Platform", CONTACT)) shouldBe 1

                err.toString().trim() shouldBe "refused: SubdomainTaken"
                fresh.events(tenant).shouldHaveSize(1)
                fresh.sql.queryForObject("SELECT count(*) FROM platform.tenants", Int::class.java) shouldBe 1
            }
        }

        context("one-off run") {
            test("needs no server, and the local profile acts as the fixed operator") {
                val fresh = OpsEnvironment()

                runCli(listOf("submit", "Acme", "oneoff", CONTACT), *fresh.settings, "--spring.profiles.active=local") shouldBe 0

                fresh.audit().map { it["actor"] } shouldBe listOf("local-operator")
            }
        }
    })
