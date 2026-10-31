package idp.sharedkernel.tenant

import org.jooq.DSLContext
import javax.sql.DataSource

class PlatformTransaction(
    dataSource: DataSource,
) {
    private val transaction = DatabaseTransaction(dataSource)

    fun <T> execute(block: (DSLContext) -> T): T = transaction.execute("platform", listOf("SET LOCAL search_path TO platform"), block)
}
