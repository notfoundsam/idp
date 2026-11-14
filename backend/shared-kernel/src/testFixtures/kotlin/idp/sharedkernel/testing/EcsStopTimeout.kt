package idp.sharedkernel.testing

import java.io.File
import java.time.Duration

private val STOP_TIMEOUT = Regex("""stopTimeout\s*=\s*[^\n]*?(\d+)""")
private val EXIT_MARGIN = Duration.ofSeconds(10)

/** Between SIGTERM and SIGKILL, as `infra/terraform/modules/app` gives it to every service. */
fun ecsStopTimeout(): Duration {
    val services =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "infra/terraform/modules/app/services.tf") }
            .first { it.isFile }
    val seconds = STOP_TIMEOUT.findAll(services.readText()).single()
    return Duration.ofSeconds(seconds.groupValues[1].toLong())
}

/** What a graceful shutdown may take, so that the JVM still exits before ECS kills it. */
fun shutdownBudget(): Duration = ecsStopTimeout().minus(EXIT_MARGIN)
