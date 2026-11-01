package idp.worker

import idp.sharedkernel.crypto.adapter.OpenBaoKeyManagementService
import idp.sharedkernel.migration.TenantMigrationRunner
import idp.sharedkernel.testing.TestDatabase
import idp.sharedkernel.testing.TestOpenBao
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID

const val BASE_DOMAIN = "idp.test"

/** A migrated database of its own and everything `worker` needs to start on it. */
class WorkerEnvironment {
    val database = TestDatabase.create()
    val owner = database.owner().also { TenantMigrationRunner(it).migrateAll() }
    val jdbc = JdbcTemplate(owner)
    val kms = OpenBaoKeyManagementService(TestOpenBao.address, TestOpenBao.TOKEN)
    val platformKekUri = kms.createKey("platform-${UUID.randomUUID()}")

    init {
        database.role("worker_runtime")
        database.role("provisioner")
    }

    val arguments =
        arrayOf(
            "--server.port=0",
            "--DB_URL=${database.pooledUrl()}",
            "--DB_USERNAME=worker_runtime",
            "--DB_PASSWORD=worker_runtime",
            "--PROVISIONER_DB_URL=${database.url}?options=-c%20role%3Didp_owner",
            "--PROVISIONER_DB_USERNAME=provisioner",
            "--PROVISIONER_DB_PASSWORD=provisioner",
            "--TENANTS_BASE_DOMAIN=$BASE_DOMAIN",
            "--MAIL_FROM=no-reply@$BASE_DOMAIN",
            "--KMS_NEW_KEYS=hcvault",
            "--PLATFORM_KEK_URI=$platformKekUri",
            "--OPENBAO_ADDRESS=${TestOpenBao.address}",
            "--OPENBAO_TOKEN=${TestOpenBao.TOKEN}",
            "--idp.outbox.poll-interval=PT0.05S",
        )
}
