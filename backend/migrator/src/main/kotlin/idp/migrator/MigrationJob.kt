package idp.migrator

import idp.sharedkernel.migration.TenantMigrationRunner
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import javax.sql.DataSource

@Component
@Order(1)
class MigrationJob(
    private val dataSource: DataSource,
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        TenantMigrationRunner(dataSource).migrateAll()
    }
}
