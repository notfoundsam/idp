package idp.sharedkernel.tenant

import idp.sharedkernel.migration.TenantSchema
import org.jooq.DSLContext
import javax.sql.DataSource

/** `SET LOCAL` ends with the transaction, so PgBouncer can hand the connection to the next tenant. */
class TenantTransaction(
    dataSource: DataSource,
) {
    private val transaction = DatabaseTransaction(dataSource)

    fun <T> execute(block: (DSLContext) -> T): T {
        val schema = TenantSchema(TenantContext.current().tenantId).name
        return transaction.execute(schema, listOf("SET LOCAL ROLE $schema", "SET LOCAL search_path TO $schema"), block)
    }
}
