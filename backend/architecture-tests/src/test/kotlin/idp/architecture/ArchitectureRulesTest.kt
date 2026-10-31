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
    })
