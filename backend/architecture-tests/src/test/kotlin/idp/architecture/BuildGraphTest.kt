package idp.architecture

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.io.File

class BuildGraphTest :
    StringSpec({
        fun dependenciesOf(module: String): Set<String> =
            Regex("""project\("(:[^"]+)"\)""")
                .findAll(File("../${module.replace(':', '/')}/build.gradle.kts").readText())
                .map { it.groupValues[1] }
                .toSet()

        fun audiencesOf(module: String) = dependenciesOf(module).filter { it.startsWith(":audience:") }.toSet()

        "app builds every audience except operator" {
            audiencesOf("app") shouldBe setOf(":audience:login", ":audience:account", ":audience:admin")
        }

        "ops builds only the operator audience" {
            audiencesOf("ops") shouldBe setOf(":audience:operator")
        }

        "worker and migrator build no audience" {
            audiencesOf("worker").shouldBeEmpty()
            audiencesOf("migrator").shouldBeEmpty()
        }

        "contexts depend only along the graph" {
            ArchitectureRules.contextGraph.forEach { (context, allowed) ->
                val contexts = dependenciesOf("modules:$context").filter { it.startsWith(":modules:") }
                contexts.toSet() shouldBe allowed.map { ":modules:$it" }.toSet()
            }
        }
    })
