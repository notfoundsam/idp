package idp.sharedkernel.testing

import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import java.net.URI

private const val LOCALSTACK_PORT = 4566

/** Later images stop at startup without a LocalStack license. */
private const val LOCALSTACK_IMAGE = "localstack/localstack:4.14.0"

private val localstack: GenericContainer<*> by lazy {
    GenericContainer(LOCALSTACK_IMAGE)
        .withEnv("SERVICES", "kms")
        .withExposedPorts(LOCALSTACK_PORT)
        .waitingFor(Wait.forLogMessage(".*Ready\\.\\n", 1))
        .also { it.start() }
}

object TestLocalStack {
    val endpoint: URI by lazy { URI("http://${localstack.host}:${localstack.getMappedPort(LOCALSTACK_PORT)}") }
}
