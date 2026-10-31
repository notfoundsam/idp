package idp.worker.seed

import idp.sharedkernel.crypto.KeyHierarchy
import idp.sharedkernel.crypto.adapter.OpenBaoKeyManagementService
import idp.sharedkernel.migration.TenantMigrationRunner
import idp.sharedkernel.migration.TenantSchemaFactory
import idp.sharedkernel.tenant.TenantContext
import idp.sharedkernel.tenant.TenantTransaction
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.stereotype.Component
import java.net.URI
import java.time.Duration
import java.util.UUID

private val TENANTS =
    mapOf(
        "acme.idp.test" to UUID.fromString("00000000-0000-4000-8000-000000000001"),
        "globex.idp.test" to UUID.fromString("00000000-0000-4000-8000-000000000002"),
    )

private val KEY_CACHE_TTL: Duration = Duration.ofMinutes(1)

@Component
@Profile("local")
class LocalSeed(
    @Value("\${idp.seed.url}") private val url: String,
    @Value("\${idp.seed.username}") private val username: String,
    @Value("\${idp.seed.password}") private val password: String,
    @Value("\${idp.seed.openbao.address}") private val openBaoAddress: URI,
    @Value("\${idp.seed.openbao.token}") private val openBaoToken: String,
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        val dataSource = DriverManagerDataSource(url, username, password)
        val jdbc = JdbcTemplate(dataSource)
        val kms = OpenBaoKeyManagementService(openBaoAddress, openBaoToken)
        val keys = KeyHierarchy(kms, TenantTransaction(dataSource), KEY_CACHE_TTL)
        TENANTS.forEach { (domain, id) ->
            jdbc.update(
                "INSERT INTO platform.tenants (id, name, status) VALUES (?, ?, 'ACTIVE') ON CONFLICT DO NOTHING",
                id,
                domain.substringBefore('.'),
            )
            jdbc.update("INSERT INTO platform.tenant_domains (domain, tenant_id) VALUES (?, ?) ON CONFLICT DO NOTHING", domain, id)
            TenantSchemaFactory(dataSource).create(id)
            TenantMigrationRunner(dataSource).migrateTenant(id)
            val kekUri = kms.createKey("tenant-$id")
            jdbc.update("UPDATE platform.tenants SET kek_uri = ? WHERE id = ?", kekUri, id)
            TenantContext.with(TenantContext(id, kekUri)) { keys.createTenantKey() }
        }
    }
}
