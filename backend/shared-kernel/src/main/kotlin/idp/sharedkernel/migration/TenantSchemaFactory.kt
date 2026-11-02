package idp.sharedkernel.migration

import java.util.UUID
import javax.sql.DataSource

private val TENANT_MEMBERS = listOf("app_runtime", "worker_runtime", "idp_owner")

/** Idempotent, so a failed provisioning or offboarding step can run again. */
class TenantSchemaFactory(
    private val dataSource: DataSource,
) {
    private val lock = SchemaLock(dataSource)

    fun create(tenantId: UUID): TenantSchema {
        val schema = TenantSchema(tenantId)
        lock.shared { execute(createStatements(schema.name)) }
        return schema
    }

    /** Under the same lock as [create], so the migrator never runs on a schema that disappears under it. */
    fun drop(tenantId: UUID) {
        lock.shared { execute(dropStatements(TenantSchema(tenantId).name)) }
    }

    fun exists(tenantId: UUID): Boolean =
        dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT 1 FROM pg_namespace WHERE nspname = ?").use { statement ->
                statement.setString(1, TenantSchema(tenantId).name)
                statement.executeQuery().use { it.next() }
            }
        }

    private fun execute(statements: List<String>) {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            connection.createStatement().use { statement -> statements.forEach(statement::execute) }
            connection.commit()
        }
    }

    /** A role keeps its grants outside the schema, and Postgres refuses to drop a role that still holds any. */
    private fun dropStatements(name: String): List<String> =
        listOf(
            "DROP SCHEMA IF EXISTS $name CASCADE",
            """
            DO $$
            BEGIN
                IF EXISTS (SELECT FROM pg_roles WHERE rolname = '$name') THEN
                    REVOKE ALL ON platform.outbox FROM $name;
                    REVOKE ALL ON SCHEMA platform FROM $name;
                    DROP ROLE $name;
                END IF;
            END
            $$
            """,
        )

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
