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
