package idp.ops

import idp.sharedkernel.tenant.TenantStatus
import idp.tenancy.application.TenancyEvents
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.springframework.boot.runApplication
import java.time.Duration
import java.time.Instant
import java.util.UUID

class OpsApiTest :
    FunSpec({
        val environment = OpsEnvironment()
        val application = runApplication<OpsApplication>(*environment.arguments)
        val api = OpsClient(application.environment.getProperty("local.server.port")).also { it.login(environment.idp) }
        val sql = environment.sql

        afterSpec { application.close() }

        beforeTest {
            sql.update("DELETE FROM platform.operator_audit")
        }

        fun activate(tenant: UUID) {
            sql.update(
                "UPDATE platform.tenants SET status = 'ACTIVE', kek_uri = 'hcvault://tenant', version = version + 1 WHERE id = ?",
                tenant,
            )
        }

        fun statusOf(tenant: UUID) =
            api
                .get("/api/tenants/$tenant")
                .json()
                .get("status")
                .asString()

        context("operator actions") {
            test("an application waits as a requested tenant") {
                val id = api.submit("requested")

                val tenant = api.tenantOf(id)
                statusOf(tenant) shouldBe TenantStatus.REQUESTED.name
                api
                    .get("/api/applications/$id")
                    .json()
                    .get("status")
                    .asString() shouldBe "SUBMITTED"
            }

            test("approving leads to the provisioning event") {
                val id = api.submit("approved")

                api.post("/api/applications/$id/approve").status shouldBe 204

                val tenant = api.tenantOf(id)
                statusOf(tenant) shouldBe TenantStatus.APPROVED.name
                environment.events(tenant).shouldContainExactly(TenancyEvents.APPROVED)
            }

            test("an active tenant is suspended and resumed") {
                val id = api.submit("suspended")
                api.post("/api/applications/$id/approve")
                val tenant = api.tenantOf(id)
                activate(tenant)

                api.post("/api/tenants/$tenant/suspend").status shouldBe 204
                statusOf(tenant) shouldBe TenantStatus.SUSPENDED.name

                api.post("/api/tenants/$tenant/resume").status shouldBe 204
                statusOf(tenant) shouldBe TenantStatus.ACTIVE.name
            }

            test("a suspended tenant starts offboarding, waits for the period and is cancelled") {
                val id = api.submit("offboarded")
                api.post("/api/applications/$id/approve")
                val tenant = api.tenantOf(id)
                activate(tenant)
                api.post("/api/tenants/$tenant/suspend")

                val started = api.post("/api/tenants/$tenant/offboarding")

                started.status shouldBe 202
                val after = Instant.parse(started.json().get("deletionAfter").asString())
                Duration.between(Instant.now(), after).toDays() shouldBe 29
                api.get("/api/tenants/$tenant").json().run {
                    get("status").asString() shouldBe TenantStatus.OFFBOARDING.name
                    Instant.parse(get("offboardingAfter").asString()) shouldBe after
                }
                environment.events(tenant).last() shouldBe TenancyEvents.OFFBOARDING_STARTED

                api.delete("/api/tenants/$tenant/offboarding").status shouldBe 204
                statusOf(tenant) shouldBe TenantStatus.SUSPENDED.name
                api
                    .get("/api/tenants/$tenant")
                    .json()
                    .get("offboardingAfter")
                    .isNull shouldBe true
            }

            test("an active tenant cannot start offboarding, and one that is not offboarding cannot cancel") {
                val id = api.submit("still-active")
                api.post("/api/applications/$id/approve")
                val tenant = api.tenantOf(id)
                activate(tenant)

                api.post("/api/tenants/$tenant/offboarding").status shouldBe 422
                api.delete("/api/tenants/$tenant/offboarding").status shouldBe 422
                statusOf(tenant) shouldBe TenantStatus.ACTIVE.name
            }

            test("a broken rule answers 422 and changes nothing") {
                val id = api.submit("twice")
                api.post("/api/applications/$id/approve")

                api.post("/api/applications/$id/approve").status shouldBe 422

                environment.events(api.tenantOf(id)).shouldContainExactly(TenancyEvents.APPROVED)
            }

            test("a taken subdomain answers 422") {
                api.submit("taken")

                api
                    .post("/api/applications", """{"companyName":"Acme","subdomain":"taken","contact":"$CONTACT"}""")
                    .status shouldBe 422
            }

            test("a stale version answers 409") {
                val id = api.submit("stale")
                api.post("/api/applications/$id/approve")
                val tenant = api.tenantOf(id)
                activate(tenant)
                sql.update(
                    """
                    CREATE FUNCTION platform.skip_update() RETURNS trigger LANGUAGE plpgsql AS
                    $$ BEGIN RETURN NULL; END $$
                    """,
                )
                sql.update(
                    "CREATE TRIGGER skip_update BEFORE UPDATE ON platform.tenants FOR EACH ROW EXECUTE FUNCTION platform.skip_update()",
                )

                try {
                    api.post("/api/tenants/$tenant/suspend").status shouldBe 409
                } finally {
                    sql.update("DROP FUNCTION platform.skip_update CASCADE")
                }
            }

            test("an unknown id answers 404") {
                val unknown = UUID.randomUUID()

                api.get("/api/applications/$unknown").status shouldBe 404
                api.get("/api/tenants/$unknown").status shouldBe 404
                api.post("/api/applications/$unknown/approve").status shouldBe 404
                api.post("/api/tenants/$unknown/suspend").status shouldBe 404
                api.post("/api/tenants/$unknown/resume").status shouldBe 404
                api.post("/api/tenants/$unknown/offboarding").status shouldBe 404
                api.delete("/api/tenants/$unknown/offboarding").status shouldBe 404
            }

            test("a malformed id answers 400") {
                api.get("/api/tenants/nonsense").status shouldBe 400
            }
        }

        context("responses") {
            test("every one carries the security headers, a refusal too") {
                val application = api.get("/api/applications/${api.submit("headers")}")
                val refusal = api.get("/api/tenants/${UUID.randomUUID()}")

                listOf(application, refusal).forEach {
                    it.header("Strict-Transport-Security") shouldBe "max-age=31536000; includeSubDomains"
                    it.header("X-Content-Type-Options") shouldBe "nosniff"
                    it.header("Referrer-Policy") shouldBe "no-referrer"
                    it.header("Content-Security-Policy").orEmpty() shouldContain "frame-ancestors 'none'"
                    it.header("Cache-Control") shouldBe "no-store"
                }
            }

            test("TRACE is refused") {
                api.trace("/api/tenants/${UUID.randomUUID()}").status shouldBe 405
            }

            test("a body names its charset") {
                api.get("/api/applications/${api.submit("charset")}").header("Content-Type") shouldBe "application/json;charset=UTF-8"
            }

            test("an unknown field is refused") {
                api
                    .post("/api/applications", """{"companyName":"Acme","subdomain":"extra","contact":"$CONTACT","plan":"gold"}""")
                    .status shouldBe 400
            }
        }

        context("audit") {
            test("submitting, approving, suspending, resuming, offboarding and cancelling each write a record") {
                val id = api.submit("audited")
                api.post("/api/applications/$id/approve")
                val tenant = api.tenantOf(id)
                activate(tenant)
                api.post("/api/tenants/$tenant/suspend")
                api.post("/api/tenants/$tenant/resume")
                api.post("/api/tenants/$tenant/suspend")
                api.post("/api/tenants/$tenant/offboarding")
                api.delete("/api/tenants/$tenant/offboarding")

                val records = environment.audit()

                records.map { it["action"] }.shouldContainExactly(
                    "tenant.application.submitted",
                    "tenant.approved",
                    "tenant.suspended",
                    "tenant.resumed",
                    "tenant.suspended",
                    "tenant.offboarding_started",
                    "tenant.offboarding_cancelled",
                )
                records.forEach {
                    it["actor"] shouldBe OPERATOR_EMAIL
                    it["tenant_id"] shouldBe tenant
                    it["details"].toString() shouldContain "\"ip\": \"127.0.0.1\""
                }
            }

            test("a refused action leaves no record of a change") {
                val id = api.submit("refused")
                api.post("/api/applications/$id/approve")
                sql.update("DELETE FROM platform.operator_audit")

                api.post("/api/applications/$id/approve")

                environment.audit().shouldBeEmpty()
            }

            test("reading writes no record") {
                val id = api.submit("read")
                sql.update("DELETE FROM platform.operator_audit")

                api.get("/api/applications/$id")

                environment.audit().shouldBeEmpty()
            }
        }

        context("principal") {
            test("a request without a SAML session is refused and recorded, in the local profile too") {
                val other = OpsEnvironment()
                runApplication<OpsApplication>(*other.arguments, "--spring.profiles.active=local").use { context ->
                    val anonymous = OpsClient(context.environment.getProperty("local.server.port"))

                    val answers =
                        listOf(
                            anonymous.post("/api/applications", """{"companyName":"Acme","subdomain":"acme","contact":"$CONTACT"}"""),
                            anonymous.post("/api/applications/${UUID.randomUUID()}/approve"),
                            anonymous.post("/api/tenants/${UUID.randomUUID()}/suspend"),
                            anonymous.post("/api/tenants/${UUID.randomUUID()}/resume"),
                            anonymous.get("/api/tenants/${UUID.randomUUID()}"),
                            anonymous.get("/api/applications/${UUID.randomUUID()}"),
                        )

                    answers.map { it.status } shouldBe List(6) { 401 }
                    other.audit().map { it["actor"] } shouldBe List(6) { "anonymous" }
                    other.sql.queryForObject("SELECT count(*) FROM platform.tenants", Int::class.java) shouldBe 0
                }
            }

            listOf("prod", "staging").forEach { profile ->
                test("the $profile profile refuses to start beside the local one") {
                    val failure =
                        shouldThrow<Exception> {
                            runApplication<OpsApplication>(*environment.arguments, "--spring.profiles.active=local,$profile")
                        }

                    generateSequence(failure as Throwable) { it.cause }.last().message.orEmpty() shouldContain
                        "refused beside the $profile one"
                }
            }
        }
    })
