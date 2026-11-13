package idp.sharedkernel.testing

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import tools.jackson.databind.json.JsonMapper
import java.io.File

private val CONTRACT =
    """
    openapi: 3.1.0
    paths: {}
    components:
      schemas:
        Page:
          type: object
          required: [items, owner]
          properties:
            items:
              type: array
              items:
                ${'$'}ref: "#/components/schemas/Item"
            owner:
              oneOf:
                - ${'$'}ref: "#/components/schemas/Owner"
                - type: "null"
        Item:
          type: object
          required: [id, kind]
          properties:
            id:
              type: string
            kind:
              type: string
              enum: [A, B]
        Owner:
          type: object
          required: [name]
          properties:
            name:
              type: string
    """.trimIndent()

class OpenApiContractTest :
    FunSpec({
        val file = File.createTempFile("contract", ".yaml").apply { writeText(CONTRACT) }
        afterSpec { file.delete() }
        val contract = OpenApiContract(file)
        val page = contract.schema("Page")

        fun matches(json: String) = contract.shouldMatch(JsonMapper().readTree(json), page)

        test("a body that matches at every depth passes, a nullable object as null too") {
            matches("""{"items":[{"id":"1","kind":"A"}],"owner":{"name":"Ada"}}""")
            matches("""{"items":[],"owner":null}""")
        }

        test("a drift inside an item of an array fails and names the item") {
            listOf(
                """{"items":[{"id":"1","kind":"A"},{"id":2,"kind":"A"}],"owner":null}""" to "$.items[1].id",
                """{"items":[{"id":"1","kind":"C"}],"owner":null}""" to "$.items[0].kind",
                """{"items":[{"id":"1"}],"owner":null}""" to "$.items[0]",
            ).forEach { (json, path) -> shouldThrow<AssertionError> { matches(json) }.message shouldContain path }
        }

        test("a drift inside a nested object fails and names it") {
            shouldThrow<AssertionError> { matches("""{"items":[],"owner":{"name":"Ada","email":"ada@acme.test"}}""") }
                .message shouldContain "$.owner"
            shouldThrow<AssertionError> { matches("""{"items":[],"owner":{"name":1}}""") }.message shouldContain "$.owner.name"
        }
    })
