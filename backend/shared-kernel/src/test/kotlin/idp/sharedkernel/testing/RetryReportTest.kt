package idp.sharedkernel.testing

import io.kotest.core.annotation.Condition
import io.kotest.core.annotation.EnabledIf
import io.kotest.core.spec.Spec
import io.kotest.core.spec.SpecRef
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.TestEngineLauncher
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.readLines
import kotlin.reflect.KClass

private val launched = AtomicBoolean(false)
private val firstAttempts = AtomicInteger()

@Volatile
private var reportDirectory: Path = Path.of("build/test-results/retries")

/** The specs below fail on purpose, so only the launch inside [RetryReportTest] runs them. */
internal class LaunchedByTheTest : Condition {
    override fun evaluate(kclass: KClass<out Spec>): Boolean = launched.get()
}

@EnabledIf(LaunchedByTheTest::class)
internal class RetriedSpec :
    FunSpec({
        extension(RetryReport(reportDirectory))

        test("fails once").config(retries = 2) { check(firstAttempts.getAndIncrement() > 0) { "noise" } }

        test("fails every time").config(retries = 2) { error("broken") }

        test("passes at once").config(retries = 2) {}

        test("is not retried") {}
    })

class RetryReportTest :
    FunSpec({
        test("names each test that took more than one attempt, with the attempts and the outcome") {
            reportDirectory = Files.createTempDirectory("retries")
            firstAttempts.set(0)
            launched.set(true)
            try {
                TestEngineLauncher()
                    .withNoOpListener()
                    .withoutEnvFilters()
                    .withSpecRefs(SpecRef.Reference(RetriedSpec::class))
                    .execute()
            } finally {
                launched.set(false)
            }

            val spec = RetriedSpec::class.qualifiedName
            reportDirectory.resolve("$spec.txt").readLines() shouldContainExactlyInAnyOrder
                listOf("$spec | fails once | 2 attempts | passed", "$spec | fails every time | 3 attempts | failed")
        }
    })
