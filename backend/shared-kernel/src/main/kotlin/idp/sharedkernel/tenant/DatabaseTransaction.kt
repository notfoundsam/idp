package idp.sharedkernel.tenant

import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy
import org.springframework.jdbc.support.JdbcTransactionManager
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import javax.sql.DataSource

internal class DatabaseTransaction(
    dataSource: DataSource,
) {
    private val manager = JdbcTransactionManager(dataSource)
    private val jdbc = JdbcTemplate(dataSource)
    private val dsl = DSL.using(TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES)

    /** A running transaction keeps its role, so joining one from a different scope would run as the wrong tenant. */
    fun <T> execute(
        scope: String,
        setup: List<String>,
        block: (DSLContext) -> T,
    ): T {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            val active = TransactionSynchronizationManager.getCurrentTransactionName()
            check(active == scope) { "A transaction of $active cannot continue as $scope" }
            return block(dsl)
        }
        val template = TransactionTemplate(manager).apply { setName(scope) }
        @Suppress("UNCHECKED_CAST")
        return template.execute {
            setup.forEach(jdbc::execute)
            block(dsl)
        } as T
    }
}
