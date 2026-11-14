package idp.sharedkernel.testing

import io.lettuce.core.RedisClient
import io.lettuce.core.ScanCursor
import io.lettuce.core.codec.ByteArrayCodec
import org.testcontainers.containers.GenericContainer

private const val VALKEY_PORT = 6379

class ValkeyServer internal constructor() : AutoCloseable {
    private val container: GenericContainer<*> =
        GenericContainer(System.getProperty("idp.valkey.image"))
            .withExposedPorts(VALKEY_PORT)
            .also { it.start() }

    val url: String = "redis://${container.host}:${container.getMappedPort(VALKEY_PORT)}"

    /** A paused server keeps its connections open and stops answering, which is what a hung Valkey looks like. */
    fun pause() {
        container.dockerClient.pauseContainerCmd(container.containerId).exec()
    }

    fun unpause() {
        container.dockerClient.unpauseContainerCmd(container.containerId).exec()
    }

    /** Every key with what it holds, for a test that proves what the server keeps. The limits store strings alone. */
    fun entries(): List<Pair<ByteArray, ByteArray>> {
        val client = RedisClient.create(url)
        try {
            client.connect(ByteArrayCodec.INSTANCE).use { connection ->
                val commands = connection.sync()
                val keys = mutableListOf<ByteArray>()
                var cursor: ScanCursor = ScanCursor.INITIAL
                do {
                    val page = commands.scan(cursor)
                    keys += page.keys
                    cursor = page
                } while (!page.isFinished)
                return keys.map { key ->
                    check(commands.type(key) == "string") { "A key of another type than string, which the scan cannot read" }
                    key to commands.get(key)
                }
            }
        } finally {
            client.shutdown()
        }
    }

    override fun close() = container.stop()
}

object TestValkey {
    val shared: ValkeyServer by lazy { ValkeyServer() }

    fun start() = ValkeyServer()
}
