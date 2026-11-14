package idp.sharedkernel.testing

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.PrintStream

/**
 * Copies what reaches `System.out` while open, which is where the services write their JSON lines. The console appender of
 * Logback looks up `System.out` on every write, so the copy holds exactly what would leave the process.
 */
class ConsoleCapture : AutoCloseable {
    private val original = System.out
    private val copy = ByteArrayOutputStream()

    init {
        val tee =
            object : OutputStream() {
                override fun write(byte: Int) =
                    synchronized(copy) {
                        original.write(byte)
                        copy.write(byte)
                    }

                override fun write(
                    bytes: ByteArray,
                    offset: Int,
                    length: Int,
                ) = synchronized(copy) {
                    original.write(bytes, offset, length)
                    copy.write(bytes, offset, length)
                }

                override fun flush() = original.flush()
            }
        System.setOut(PrintStream(tee, true, Charsets.UTF_8))
    }

    fun text(): String = synchronized(copy) { copy.toString(Charsets.UTF_8) }

    fun lines(): List<String> = text().lines().filter { it.isNotBlank() }

    fun clear() = synchronized(copy) { copy.reset() }

    override fun close() {
        System.setOut(original)
    }
}
