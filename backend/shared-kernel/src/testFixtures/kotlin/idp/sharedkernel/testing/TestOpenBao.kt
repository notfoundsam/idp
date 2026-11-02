package idp.sharedkernel.testing

import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import java.net.URI

private const val OPENBAO_PORT = 8200
private const val ROOT_TOKEN = "test-root-token"

private val openbao: GenericContainer<*> by lazy {
    GenericContainer(System.getProperty("idp.openbao.image"))
        .withCommand("server", "-dev", "-dev-root-token-id=$ROOT_TOKEN", "-dev-listen-address=0.0.0.0:$OPENBAO_PORT")
        .withEnv("BAO_ADDR", "http://127.0.0.1:$OPENBAO_PORT")
        .withEnv("BAO_TOKEN", ROOT_TOKEN)
        .withExposedPorts(OPENBAO_PORT)
        .waitingFor(Wait.forHttp("/v1/sys/health"))
        .also {
            it.start()
            val mount = it.execInContainer("bao", "secrets", "enable", "transit")
            check(mount.exitCode == 0) { mount.stderr }
        }
}

object TestOpenBao {
    const val TOKEN = ROOT_TOKEN

    val address: URI by lazy { URI("http://${openbao.host}:${openbao.getMappedPort(OPENBAO_PORT)}") }

    /** What OpenBao does on its own schedule: a new version encrypts, the old ones still decrypt. */
    fun rotate(kekUri: String) {
        val rotation = openbao.execInContainer("bao", "write", "-f", "transit/keys/${keyName(kekUri)}/rotate")
        check(rotation.exitCode == 0) { rotation.stderr }
    }

    fun keyField(
        kekUri: String,
        field: String,
    ): String {
        val read = openbao.execInContainer("bao", "read", "-field=$field", "transit/keys/${keyName(kekUri)}")
        check(read.exitCode == 0) { read.stderr }
        return read.stdout.trim()
    }

    private fun keyName(kekUri: String): String = kekUri.substringAfterLast('/')

    fun tokenWithPolicy(
        name: String,
        rules: String,
    ): String {
        val policy = openbao.execInContainer("sh", "-c", "bao policy write $name - <<'RULES'\n$rules\nRULES")
        check(policy.exitCode == 0) { policy.stderr }
        val token = openbao.execInContainer("bao", "token", "create", "-policy=$name", "-field=token")
        check(token.exitCode == 0) { token.stderr }
        return token.stdout.trim()
    }
}
