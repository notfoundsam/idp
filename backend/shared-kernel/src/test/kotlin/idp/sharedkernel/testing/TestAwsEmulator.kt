package idp.sharedkernel.testing

import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.ImageFromDockerfile
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

private const val PORT = 9297
private const val FILES = "/home/emulator/.aws/emulator/$PORT"

private val emulator: GenericContainer<*> by lazy {
    val image = ImageFromDockerfile("idp-aws-emulator", false).withFileFromPath(".", Path.of(System.getProperty("idp.awsEmulator.dir")))
    GenericContainer(image)
        .withExposedPorts(PORT)
        .waitingFor(Wait.forLogMessage(".*emulator server information.*", 1))
        .also { it.start() }
}

/** AWS Emulator, which serves HTTPS with a certificate and keys of its own run. */
object TestAwsEmulator {
    val endpoint: URI by lazy {
        trustCertificate()
        URI("https://localhost:${emulator.getMappedPort(PORT)}")
    }

    val credentials: AwsCredentialsProvider by lazy {
        val file = emulator.copyFileFromContainer("$FILES/emulator_credentials") { it.readAllBytes().decodeToString() }
        val values =
            file
                .lines()
                .filter { it.contains('=') }
                .associate { line -> line.substringBefore('=').trim() to line.substringAfter('=').trim() }
        StaticCredentialsProvider.create(
            AwsBasicCredentials.create(values.getValue("aws_access_key_id"), values.getValue("aws_secret_access_key")),
        )
    }

    // The AWS SDK takes the default trust store when it builds a client, and these tests reach no other HTTPS server.
    private fun trustCertificate() {
        val store = Files.createTempFile("aws-emulator", ".p12")
        emulator.copyFileFromContainer("$FILES/truststore.p12", store.toString())
        System.setProperty("javax.net.ssl.trustStore", store.toString())
        System.setProperty("javax.net.ssl.trustStoreType", "PKCS12")
    }
}
