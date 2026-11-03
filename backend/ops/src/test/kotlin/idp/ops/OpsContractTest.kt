package idp.ops

import idp.sharedkernel.testing.OpenApiContract
import idp.sharedkernel.testing.asMap
import idp.sharedkernel.testing.mappedOperations
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.springframework.boot.runApplication
import java.io.File
import java.util.UUID

class OpsContractTest :
    FunSpec({
        val contract = OpenApiContract(File("../../contracts/openapi/ops.yaml"))
        val environment = OpsEnvironment()
        val application = runApplication<OpsApplication>(*environment.arguments)
        val api = OpsClient(application.environment.getProperty("local.server.port")).also { it.login(environment.idp) }

        afterSpec { application.close() }

        fun OpsResponse.conforms(
            method: String,
            template: String,
        ): OpsResponse {
            contract.statuses(method, template) shouldContain status
            contract.responseSchema(method, template, status)?.let { contract.shouldMatch(json(), it) }
            return this
        }

        fun body(
            subdomain: String,
            without: String? = null,
        ): String =
            mapOf("companyName" to "Acme", "subdomain" to subdomain, "contact" to CONTACT)
                .filterKeys { it != without }
                .entries
                .joinToString(",", "{", "}") { (name, value) -> "\"$name\":\"$value\"" }

        test("the controllers and the contract list the same operations") {
            mappedOperations(application) shouldBe contract.operations
        }

        test("the request body of an application names what the controller reads") {
            val schema = contract.requestSchema("POST", "/api/applications")

            schema["properties"].asMap().keys shouldBe setOf("companyName", "subdomain", "contact")
            (schema["required"] as List<*>).toSet() shouldBe schema["properties"].asMap().keys
        }

        test("a required field cannot be left out") {
            (contract.requestSchema("POST", "/api/applications")["required"] as List<*>).forEach { field ->
                api
                    .post("/api/applications", body("missing", without = field as String))
                    .conforms("POST", "/api/applications")
                    .status shouldBe 400
            }
        }

        test("the whole path of an application conforms") {
            val id =
                api
                    .post("/api/applications", body("path"))
                    .conforms("POST", "/api/applications")
                    .also { it.status shouldBe 201 }
                    .json()
                    .get("id")
                    .asString()
            val applicationPath = "/api/applications/{id}"

            api.get("/api/applications/$id").conforms("GET", applicationPath).status shouldBe 200
            api.post("/api/applications/$id/approve").conforms("POST", "$applicationPath/approve").status shouldBe 204
            api.post("/api/applications/$id/approve").conforms("POST", "$applicationPath/approve").status shouldBe 422
            val tenant = api.tenantOf(UUID.fromString(id))

            api.get("/api/tenants/$tenant").conforms("GET", "/api/tenants/{id}").status shouldBe 200
            api.post("/api/tenants/$tenant/suspend").conforms("POST", "/api/tenants/{id}/suspend").status shouldBe 422
            api.post("/api/tenants/$tenant/resume").conforms("POST", "/api/tenants/{id}/resume").status shouldBe 422
            environment.sql.update("UPDATE platform.tenants SET status = 'ACTIVE', version = version + 1 WHERE id = ?", tenant)
            api.post("/api/tenants/$tenant/suspend").conforms("POST", "/api/tenants/{id}/suspend").status shouldBe 204
            api.post("/api/tenants/$tenant/resume").conforms("POST", "/api/tenants/{id}/resume").status shouldBe 204
        }

        test("a new invitation for an admin conforms") {
            val reinvite = "/api/tenants/{id}/admin-reinvite"
            val tenant = api.tenantOf(api.submit("reinvited"))
            val body = """{"contact":"$CONTACT"}"""

            contract.requestSchema("POST", reinvite)["properties"].asMap().keys shouldBe setOf("contact")
            api.post("/api/tenants/$tenant/admin-reinvite", body).conforms("POST", reinvite).status shouldBe 422
            environment.sql.update("UPDATE platform.tenants SET status = 'ACTIVE', version = version + 1 WHERE id = ?", tenant)
            api.post("/api/tenants/$tenant/admin-reinvite", body).conforms("POST", reinvite).status shouldBe 202
            api.post("/api/tenants/$tenant/admin-reinvite", "{}").conforms("POST", reinvite).status shouldBe 400
            api
                .post("/api/tenants/$tenant/admin-reinvite", """{"contact":"nonsense"}""")
                .conforms("POST", reinvite)
                .status shouldBe 422
            api.post("/api/tenants/${UUID.randomUUID()}/admin-reinvite", body).conforms("POST", reinvite).status shouldBe 404
        }

        test("offboarding and its cancellation conform") {
            val offboarding = "/api/tenants/{id}/offboarding"
            val tenant = api.tenantOf(api.submit("offboarded"))

            api.post("/api/tenants/$tenant/offboarding").conforms("POST", offboarding).status shouldBe 422
            environment.sql.update("UPDATE platform.tenants SET status = 'SUSPENDED', version = version + 1 WHERE id = ?", tenant)
            api.post("/api/tenants/$tenant/offboarding").conforms("POST", offboarding).status shouldBe 202
            api.get("/api/tenants/$tenant").conforms("GET", "/api/tenants/{id}").status shouldBe 200
            api.delete("/api/tenants/$tenant/offboarding").conforms("DELETE", offboarding).status shouldBe 204
            api.delete("/api/tenants/$tenant/offboarding").conforms("DELETE", offboarding).status shouldBe 422
            api.delete("/api/tenants/${UUID.randomUUID()}/offboarding").conforms("DELETE", offboarding).status shouldBe 404
        }

        test("every failure a client can cause is in the contract") {
            val unknown = UUID.randomUUID()

            api.get("/api/applications/$unknown").conforms("GET", "/api/applications/{id}").status shouldBe 404
            api.get("/api/applications/nonsense").conforms("GET", "/api/applications/{id}").status shouldBe 400
            api.post("/api/applications/$unknown/approve").conforms("POST", "/api/applications/{id}/approve").status shouldBe 404
            api.get("/api/tenants/$unknown").conforms("GET", "/api/tenants/{id}").status shouldBe 404
            api.get("/api/tenants/nonsense").conforms("GET", "/api/tenants/{id}").status shouldBe 400
            api.post("/api/tenants/$unknown/suspend").conforms("POST", "/api/tenants/{id}/suspend").status shouldBe 404
            api.post("/api/tenants/$unknown/resume").conforms("POST", "/api/tenants/{id}/resume").status shouldBe 404
            api.post("/api/applications", body("INVALID!")).conforms("POST", "/api/applications").status shouldBe 422
        }

        test("a change without the CSRF token answers as the contract says") {
            val forged = OpsClient(application.environment.getProperty("local.server.port"))
            forged.login(environment.idp)
            forged.get("/api/tenants/${UUID.randomUUID()}")
            forged.cookies.remove(CSRF_COOKIE)

            forged.post("/api/tenants/${UUID.randomUUID()}/suspend").conforms("POST", "/api/tenants/{id}/suspend").status shouldBe 403
        }

        test("a request without a principal answers as the contract says") {
            val other = OpsEnvironment()
            runApplication<OpsApplication>(*other.arguments).use { context ->
                val anonymous = OpsClient(context.environment.getProperty("local.server.port"))

                anonymous.get("/api/tenants/${UUID.randomUUID()}").conforms("GET", "/api/tenants/{id}").status shouldBe 401
            }
        }
    })
