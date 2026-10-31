package idp.sharedkernel.crypto.adapter

import com.sun.net.httpserver.HttpServer
import idp.sharedkernel.crypto.KeyManagementUnavailableException
import idp.sharedkernel.crypto.keyManagementServiceContract
import idp.sharedkernel.testing.TestOpenBao
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldStartWith
import java.net.InetSocketAddress
import java.net.URI
import java.util.UUID

private val CLOSED_PORT = URI("http://127.0.0.1:1")

private fun openBao() = OpenBaoKeyManagementService(TestOpenBao.address, TestOpenBao.TOKEN)

private fun <T> answering(
    status: Int,
    body: String = """{"errors":["answer"]}""",
    block: (OpenBaoKeyManagementService) -> T,
): T {
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/") { exchange ->
        exchange.responseHeaders.add("Content-Type", "application/json")
        val bytes = body.toByteArray()
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
    server.start()
    try {
        return block(OpenBaoKeyManagementService(URI("http://127.0.0.1:${server.address.port}"), "token"))
    } finally {
        server.stop(0)
    }
}

class OpenBaoKeyManagementServiceTest :
    FunSpec({
        include(
            keyManagementServiceContract(
                kms = ::openBao,
                unknownKey = { "hcvault://transit/unknown-${UUID.randomUUID()}" },
                unreachable = { OpenBaoKeyManagementService(CLOSED_PORT, "token") },
            ),
        )

        test("a key URI names its transit key") {
            openBao().createKey("tenant-acme") shouldStartWith "hcvault://transit/tenant-acme"
        }

        test("a sealed server is unavailable") {
            answering(503) { kms ->
                shouldThrow<KeyManagementUnavailableException> { kms.createKey("tenant") }
            }
        }

        test("a rejected token is unavailable") {
            answering(403) { kms ->
                shouldThrow<KeyManagementUnavailableException> { kms.createKey("tenant") }
            }
        }

        test("a proxy page instead of an OpenBao answer is unavailable") {
            answering(502, body = "<html>Bad Gateway</html>") { kms ->
                shouldThrow<KeyManagementUnavailableException> { kms.createKey("tenant") }
            }
        }

        test("a URI that could escape the transit mount is rejected") {
            shouldThrow<IllegalArgumentException> { openBao().wrap("hcvault://transit/../sys/seal", ByteArray(1)) }
            shouldThrow<IllegalArgumentException> { openBao().destroy("hcvault://cubbyhole/key") }
            shouldThrow<IllegalArgumentException> { openBao().unwrap("aws-kms://key", ByteArray(1)) }
        }
    })
