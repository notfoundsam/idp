package idp.sharedkernel.testing

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import org.yaml.snakeyaml.Yaml
import tools.jackson.databind.JsonNode
import java.io.File

private const val API_PREFIX = "/api/"
private const val JSON = "application/json"
private val HTTP_METHODS = setOf("get", "post", "put", "patch", "delete")

@Suppress("UNCHECKED_CAST")
fun Any?.asMap() = this as Map<String, Any?>

/** Reads the contract as data, so a drift on either side fails here and not in a client. */
class OpenApiContract(
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

    fun responseHeaders(
        method: String,
        path: String,
        status: Int,
    ): Set<String> =
        resolve(operation(method, path)["responses"].asMap()[status.toString()].asMap())["headers"]
            ?.asMap()
            ?.keys
            .orEmpty()

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

    fun shouldMatch(
        body: JsonNode,
        schema: Map<String, Any?>,
    ) {
        val properties = schema["properties"].asMap()
        body.propertyNames().toList().shouldContainExactlyInAnyOrder(properties.keys)
        (schema["required"] as List<*>).toSet() shouldBe properties.keys
        properties.forEach { (name, property) ->
            val resolved = resolve(property.asMap())
            typesOf(resolved) shouldContain typeOf(body.get(name))
            (resolved["enum"] as List<*>?)?.let { values -> values shouldContain body.get(name).asString() }
        }
    }

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
}

/** An integer is a number too, as JSON Schema has it. */
private fun typesOf(property: Map<String, Any?>): Set<String> {
    val types =
        when (val type = property["type"]) {
            is String -> setOf(type)
            is List<*> -> type.map { it as String }.toSet()
            else -> error("The contract gives every field a type: $property")
        }
    return if ("number" in types) types + "integer" else types
}

private fun typeOf(value: JsonNode): String =
    when {
        value.isNull -> "null"
        value.isString -> "string"
        value.isIntegralNumber -> "integer"
        value.isNumber -> "number"
        value.isBoolean -> "boolean"
        value.isArray -> "array"
        else -> "object"
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
