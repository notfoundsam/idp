package idp.migrator

import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import kotlin.system.exitProcess

@SpringBootApplication
class MigratorApplication

fun main(args: Array<String>) {
    exitProcess(runMigrator(*args))
}

fun runMigrator(
    vararg args: String,
    sources: Array<Class<*>> = arrayOf(MigratorApplication::class.java),
): Int =
    try {
        SpringApplication.exit(SpringApplication(*sources).run(*args))
    } catch (_: Exception) {
        // SpringApplication has already logged the failure. The release pipeline only reads the exit code.
        1
    }
