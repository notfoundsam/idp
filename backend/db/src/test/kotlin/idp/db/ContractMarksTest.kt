package idp.db

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readLines

private val MARK = Regex("""^-- contract of V(\d+): \S""")
private val VERSION = Regex("""^V(\d+)__""")

private fun versionOf(script: Path) = VERSION.find(script.name)!!.groupValues[1].toInt()

private fun expandOf(line: String): Int? {
    val mark = MARK.find(line) ?: return null
    return mark.groupValues[1].toInt()
}

private fun mentionsContract(line: String) = line.trimStart().startsWith("--") && "contract" in line.lowercase()

/** What is wrong with the contract marks of [script], given the [versions] of its location. */
private fun problems(
    script: Path,
    versions: Set<Int>,
): List<String> =
    script.readLines().withIndex().filter { mentionsContract(it.value) }.mapNotNull { (index, line) ->
        val expand = expandOf(line)
        when {
            index != 0 || expand == null -> "${script.name}:${index + 1} is no mark on the first line: $line"
            expand !in versions || expand >= versionOf(script) -> "${script.name} names V$expand, which is no earlier migration"
            else -> null
        }
    }

/** `TenantMigrationRunner` reads the mark from the first line only, so a mark anywhere else would be ignored in silence. */
class ContractMarksTest :
    FunSpec({
        listOf("platform", "tenant").forEach { area ->
            test("every contract mark of $area opens its migration and names an earlier migration of $area") {
                val scripts = Path.of("src/main/resources/db/$area").listDirectoryEntries("V*.sql")
                val versions = scripts.map(::versionOf).toSet()

                scripts.flatMap { problems(it, versions) }.shouldBeEmpty()
            }
        }
    })
