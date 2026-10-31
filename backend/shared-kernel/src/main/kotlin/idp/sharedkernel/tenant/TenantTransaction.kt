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
        val schema = schema()
        return transaction.execute(schema, setup(schema), block)
    }

    /** For work that must share the fate of the caller's transaction and would be wrong in one of its own. */
    fun <T> join(block: (DSLContext) -> T): T = transaction.join(schema(), block)

    /** Takes a second connection while the caller's waits, so it commits whatever happens to the caller's. */
    fun <T> executeSeparately(block: (DSLContext) -> T): T {
        val schema = schema()
        return transaction.executeSeparately(schema, setup(schema), block)
    }

    private fun schema(): String = TenantSchema(TenantContext.current().tenantId).name

    private fun setup(schema: String) = listOf("SET LOCAL ROLE $schema", "SET LOCAL search_path TO $schema")
}
