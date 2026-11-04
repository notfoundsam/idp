package idp.architecture

import com.tngtech.archunit.core.importer.ClassFileImporter
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class ArchitectureRulesTest :
    FunSpec({
        val code = ClassFileImporter().importPackages("idp")
        val fixtures = ClassFileImporter().importPackages("archfixtures")

        ArchitectureRules.all.forEach { (name, rule) ->
            test("$name holds for the code") {
                rule("idp").check(code)
            }

            test("$name catches its fixture") {
                val failure = shouldThrow<AssertionError> { rule("archfixtures.$name").check(fixtures) }
                failure.message shouldContain "archfixtures.$name"
                failure.message shouldContain "Violation"
            }
        }

        test("the SAML adapter is the only allowed controller in a context") {
            val failure =
                shouldThrow<AssertionError> {
                    ArchitectureRules.all.getValue("nocontrollers")("archfixtures.nocontrollers").check(fixtures)
                }
            failure.message shouldNotContain "SsoEndpoint"
        }

        test("a context type is caught as a response, inside a generic one, in a field of a response and as a request") {
            val failure =
                shouldThrow<AssertionError> {
                    ArchitectureRules.all.getValue("audienceresponses")("archfixtures.audienceresponses").check(fixtures)
                }
            listOf("Violation.user()", "Violation.users()", "Violation.wrapped()", "Violation.save(").forEach {
                failure.message shouldContain it
            }
            failure.message shouldNotContain "Violation.allowed()"
        }

        test("app may wire the SAML adapter and nothing else inside a context, and nobody else may") {
            val failure =
                shouldThrow<AssertionError> {
                    ArchitectureRules.all.getValue("contextapi")("archfixtures.contextapi").check(fixtures)
                }
            failure.message shouldNotContain "SamlWiring"
            failure.message shouldContain "archfixtures.contextapi.app.Violation"
            failure.message shouldContain "archfixtures.contextapi.audience.admin.SamlViolation"
        }
    })
