package idp.sharedkernel.testing

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

    override fun close() = container.stop()
}

object TestValkey {
    val shared: ValkeyServer by lazy { ValkeyServer() }

    fun start() = ValkeyServer()
}
