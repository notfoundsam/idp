package idp.sharedkernel.crypto.adapter

import idp.sharedkernel.crypto.KeyManagementException
import idp.sharedkernel.crypto.KeyManagementService
import idp.sharedkernel.crypto.KeyManagementUnavailableException
import idp.sharedkernel.crypto.KeyNotFoundException
import idp.sharedkernel.crypto.WrongKeyException
import idp.sharedkernel.crypto.requireKeyAlias
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse
import java.net.http.HttpResponse.BodyHandlers
import java.time.Duration
import java.util.Base64

private const val URI_PREFIX = "hcvault://transit/"
private const val TOKEN_HEADER = "X-Vault-Token"
private const val BAD_REQUEST = 400
private const val NOT_FOUND = 404
private val TIMEOUT: Duration = Duration.ofSeconds(5)
private val MISSING_KEY = Regex("not found|no existing key")

class OpenBaoKeyManagementService(
    private val address: URI,
    private val token: String,
) : KeyManagementService {
    private val client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build()
    private val json = JsonMapper.builder().build()

    override fun createKey(alias: String): String {
        val name = requireKeyAlias(alias)
        call("POST", "keys/$name", kekUri = URI_PREFIX + name, body = mapOf("type" to "aes256-gcm96"))
        return URI_PREFIX + name
    }

    override fun wrap(
        kekUri: String,
        plaintext: ByteArray,
    ): ByteArray {
        val name = nameOf(kekUri)
        // transit/encrypt creates a missing key, so the material would end up under a key no tenant owns.
        call("GET", "keys/$name", kekUri)
        val encoded = Base64.getEncoder().encodeToString(plaintext)
        val reply = call("POST", "encrypt/$name", kekUri, mapOf("plaintext" to encoded))
        return reply.at("/data/ciphertext").asString().toByteArray()
    }

    override fun unwrap(
        kekUri: String,
        wrapped: ByteArray,
    ): ByteArray {
        val reply = call("POST", "decrypt/${nameOf(kekUri)}", kekUri, mapOf("ciphertext" to String(wrapped)))
        return Base64.getDecoder().decode(reply.at("/data/plaintext").asString())
    }

    override fun destroy(kekUri: String) {
        val name = nameOf(kekUri)
        try {
            call("POST", "keys/$name/config", kekUri, mapOf("deletion_allowed" to true))
            call("DELETE", "keys/$name", kekUri)
        } catch (_: KeyNotFoundException) {
            return
        }
    }

    private fun nameOf(kekUri: String): String {
        require(kekUri.startsWith(URI_PREFIX)) { "Not an OpenBao transit key: $kekUri" }
        return requireKeyAlias(kekUri.removePrefix(URI_PREFIX))
    }

    private fun call(
        method: String,
        path: String,
        kekUri: String,
        body: Map<String, Any>? = null,
    ): JsonNode {
        val response = send(method, path, body)
        val reply =
            try {
                json.readTree(response.body().ifEmpty { "{}" })
            } catch (e: JacksonException) {
                throw KeyManagementUnavailableException("OpenBao answered ${response.statusCode()} with an unreadable body", e)
            }
        if (response.statusCode() in 200..299) return reply
        throw failure(response.statusCode(), path, kekUri, reply)
    }

    private fun send(
        method: String,
        path: String,
        body: Map<String, Any>?,
    ): HttpResponse<String> {
        val publisher = body?.let { BodyPublishers.ofString(json.writeValueAsString(it)) } ?: BodyPublishers.noBody()
        val request =
            HttpRequest
                .newBuilder(address.resolve("/v1/transit/$path"))
                .timeout(TIMEOUT)
                .header(TOKEN_HEADER, token)
                .method(method, publisher)
                .build()
        return try {
            client.send(request, BodyHandlers.ofString())
        } catch (e: IOException) {
            throw KeyManagementUnavailableException("OpenBao is unreachable", e)
        }
    }

    private fun failure(
        status: Int,
        path: String,
        kekUri: String,
        reply: JsonNode,
    ): KeyManagementException {
        val errors = reply.path("errors").joinToString { it.asString() }
        return when {
            status == NOT_FOUND || (status == BAD_REQUEST && MISSING_KEY.containsMatchIn(errors)) -> KeyNotFoundException(kekUri)
            status == BAD_REQUEST && path.startsWith("decrypt/") -> WrongKeyException(kekUri)
            else -> KeyManagementUnavailableException("OpenBao answered $status: $errors")
        }
    }
}
