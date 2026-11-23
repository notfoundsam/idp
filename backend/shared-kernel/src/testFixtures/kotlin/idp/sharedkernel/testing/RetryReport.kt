package idp.sharedkernel.testing

import io.kotest.core.listeners.AfterTestListener
import io.kotest.core.listeners.BeforeInvocationListener
import io.kotest.core.test.TestCase
import io.kotest.engine.test.TestResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Records every test that needed more than one attempt under `config(retries = n)`, one line per test in a file per spec, which
 * CI shows in the summary of the job. Kotest hands a listener no failure of an attempt, so a line names the test, the attempts and
 * the outcome. The failure of the last failed attempt is in the JUnit report.
 */
class RetryReport(
    private val directory: Path = Path.of("build/test-results/retries"),
) : BeforeInvocationListener,
    AfterTestListener {
    private val attempts = ConcurrentHashMap<String, AtomicInteger>()

    override suspend fun beforeInvocation(
        testCase: TestCase,
        iteration: Int,
    ) {
        attempts.computeIfAbsent(testCase.descriptor.path().value) { AtomicInteger() }.incrementAndGet()
    }

    override suspend fun afterTest(
        testCase: TestCase,
        result: TestResult,
    ) {
        val count = attempts.remove(testCase.descriptor.path().value)?.get() ?: return
        if (count < 2) return
        val outcome = if (result.isSuccess) "passed" else "failed"
        val spec = testCase.spec::class.qualifiedName
        Files.createDirectories(directory)
        Files.writeString(
            directory.resolve("$spec.txt"),
            "$spec | ${testCase.name.name} | $count attempts | $outcome\n",
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND,
        )
    }
}
