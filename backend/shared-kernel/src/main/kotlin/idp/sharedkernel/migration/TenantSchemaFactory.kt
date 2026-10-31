package idp.sharedkernel.migration

import java.util.UUID
import javax.sql.DataSource

private val TENANT_MEMBERS = listOf("app_runtime", "worker_runtime", "idp_owner")

/** Idempotent, so a failed provisioning step can run again. */
class TenantSchemaFactory(
    private val dataSource: DataSource,
) {
    private val lock = SchemaLock(dataSource)

    fun create(tenantId: UUID): TenantSchema {
        val schema = TenantSchema(tenantId)
        lock.shared {
            dataSource.connection.use { connection ->
                connection.autoCommit = false
                connection.createStatement().use { statement ->
                    createStatements(schema.name).forEach(statement::execute)
                }
                connection.commit()
            }
        }
        return schema
    }

    private fun createStatements(name: String): List<String> =
        listOf(
            """
            DO $$
            BEGIN
                IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = '$name') THEN
                    CREATE ROLE $name NOLOGIN;
                END IF;
            END
            $$
            """,
            "CREATE SCHEMA IF NOT EXISTS $name AUTHORIZATION idp_owner",
            "GRANT USAGE ON SCHEMA $name TO $name",
            "GRANT USAGE ON SCHEMA platform TO $name",
            "GRANT INSERT ON platform.outbox TO $name",
        ) + TENANT_MEMBERS.map { "GRANT $name TO $it WITH INHERIT FALSE, SET TRUE" }
}
