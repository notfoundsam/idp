package idp.ops

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.springframework.boot.runApplication
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import org.yaml.snakeyaml.Yaml
import tools.jackson.databind.JsonNode
import java.io.File
import java.util.UUID

private const val API_PREFIX = "/api/"

@Suppress("UNCHECKED_CAST")
private fun Any?.asMap() = this as Map<String, Any?>

/** Reads the contract as data, so a drift on either side fails here and not in a client. */
private class Contract(
    file: File,
) {
    private val document = Yaml().load<Map<String, Any?>>(file.readText())
    private val paths = document["paths"].asMap()

    val operations: Set<String> =
        paths
            .flatMap { (path, item) ->
                item
                    .asMap()
                    .keys
                    .filter { it in HTTP_METHODS }
                    .map { "${it.uppercase()} $path" }
            }.toSet()

    private fun operation(
        method: String,
        path: String,
    ) = paths[path].asMap()[method.lowercase()].asMap()

    fun statuses(
        method: String,
        path: String,
    ): Set<Int> =
        operation(method, path)["responses"]
            .asMap()
            .keys
            .map { it.toInt() }
            .toSet()

    fun responseSchema(
        method: String,
        path: String,
        status: Int,
    ): Map<String, Any?>? {
        val response = resolve(operation(method, path)["responses"].asMap()[status.toString()].asMap())
        return response["content"]
            ?.asMap()
            ?.get(JSON)
            ?.asMap()
            ?.get("schema")
            ?.let { resolve(it.asMap()) }
    }

    fun requestSchema(
        method: String,
        path: String,
    ): Map<String, Any?> =
        resolve(
            operation(method, path)["requestBody"]
                .asMap()["content"]
                .asMap()[JSON]
                .asMap()["schema"]
                .asMap(),
        )

    private fun resolve(node: Map<String, Any?>): Map<String, Any?> {
        val reference = node["\$ref"] as String? ?: return node
        return resolve(
            reference
                .removePrefix("#/")
                .split('/')
                .fold(document as Any?) { current, key -> current.asMap()[key] }
                .asMap(),
        )
    }

    companion object {
        private const val JSON = "application/json"
        private val HTTP_METHODS = setOf("get", "post", "put", "patch", "delete")
    }
}

private fun typesOf(property: Map<String, Any?>): Set<String> =
    when (val type = property["type"]) {
        is String -> setOf(type)
        is List<*> -> type.map { it as String }.toSet()
        else -> error("The contract gives every field a type: $property")
    }

private fun typeOf(value: JsonNode): String =
    when {
        value.isNull -> "null"
        value.isString -> "string"
        value.isNumber -> "number"
        value.isBoolean -> "boolean"
        value.isArray -> "array"
        else -> "object"
    }

private fun shouldMatch(
    body: JsonNode,
    schema: Map<String, Any?>,
) {
    val properties = schema["properties"].asMap()
    body.propertyNames().toList().shouldContainExactlyInAnyOrder(properties.keys)
    (schema["required"] as List<*>).toSet() shouldBe properties.keys
    properties.forEach { (name, property) -> typesOf(property.asMap()) shouldContain typeOf(body.get(name)) }
}

class OpsContractTest :
    FunSpec({
        val contract = Contract(File("../../contracts/openapi/ops.yaml"))
        val environment = OpsEnvironment()
        val application = runApplication<OpsApplication>(*environment.localArguments)
        val api = OpsClient(application.environment.getProperty("local.server.port"))

        afterSpec { application.close() }

        fun OpsResponse.conforms(
            method: String,
            template: String,
        ): OpsResponse {
            contract.statuses(method, template) shouldContain status
            contract.responseSchema(method, template, status)?.let { shouldMatch(json(), it) }
            return this
        }

        fun mappedOperations(context: ConfigurableApplicationContext): Set<String> =
            context
                .getBean("requestMappingHandlerMapping", RequestMappingHandlerMapping::class.java)
                .handlerMethods.keys
                .flatMap { info ->
                    info.patternValues.filter { it.startsWith(API_PREFIX) }.flatMap { path ->
                        info.methodsCondition.methods.map { "${it.name} $path" }
                    }
                }.toSet()

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

        test("a request without a principal answers as the contract says") {
            val other = OpsEnvironment()
            runApplication<OpsApplication>(*other.arguments).use { context ->
                val anonymous = OpsClient(context.environment.getProperty("local.server.port"))

                anonymous.get("/api/tenants/${UUID.randomUUID()}").conforms("GET", "/api/tenants/{id}").status shouldBe 401
            }
        }
    })
