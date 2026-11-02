package idp.worker

import java.io.PrintWriter
import java.sql.Connection
import java.sql.SQLException
import javax.sql.DataSource

/** Fails every new connection while [down], so a test can stop a process between two of its steps. */
internal class SwitchableDataSource(
    private val delegate: DataSource,
) : DataSource {
    @Volatile var down = false

    override fun getConnection(): Connection = if (down) throw SQLException("database is down") else delegate.connection

    override fun getConnection(
        username: String?,
        password: String?,
    ): Connection = throw UnsupportedOperationException()

    override fun getLogWriter(): PrintWriter = delegate.logWriter

    override fun setLogWriter(out: PrintWriter?) = throw UnsupportedOperationException()

    override fun setLoginTimeout(seconds: Int) = throw UnsupportedOperationException()

    override fun getLoginTimeout(): Int = delegate.loginTimeout

    override fun getParentLogger(): java.util.logging.Logger = delegate.parentLogger

    override fun <T : Any?> unwrap(iface: Class<T>?): T = throw UnsupportedOperationException()

    override fun isWrapperFor(iface: Class<*>?): Boolean = false
}
