package idp.migrator

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Bean

class MigratorApplicationTest :
    StringSpec({
        "a successful run exits with 0" {
            runMigrator() shouldBe 0
        }

        "a failed run exits with a non-zero code" {
            runMigrator(sources = arrayOf(MigratorApplication::class.java, FailingJob::class.java)) shouldBe 1
        }
    })

private class FailingJob {
    @Bean
    fun failingRunner() = ApplicationRunner { error("migration failed") }
}
