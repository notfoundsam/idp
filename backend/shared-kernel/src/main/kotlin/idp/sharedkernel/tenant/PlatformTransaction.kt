package idp.sharedkernel.tenant

import org.jooq.DSLContext
import javax.sql.DataSource

private const val SCOPE = "platform"
private val SETUP = listOf("SET LOCAL search_path TO platform")

class PlatformTransaction(
    dataSource: DataSource,
) {
    private val transaction = DatabaseTransaction(dataSource)

    fun <T> execute(block: (DSLContext) -> T): T = transaction.execute(SCOPE, SETUP, block)

    fun <T> join(block: (DSLContext) -> T): T = transaction.join(SCOPE, block)

    fun <T> executeSeparately(block: (DSLContext) -> T): T = transaction.executeSeparately(SCOPE, SETUP, block)
}
