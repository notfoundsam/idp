package idp.sharedkernel.crypto

private val KEY_ALIAS = Regex("[A-Za-z0-9_-]{1,128}")

interface KeyManagementService {
    /** Returns the existing key for a taken alias, so a repeated provisioning step never creates a second one. */
    fun createKey(alias: String): String

    fun wrap(
        kekUri: String,
        plaintext: ByteArray,
    ): ByteArray

    fun unwrap(
        kekUri: String,
        wrapped: ByteArray,
    ): ByteArray

    /** Repeating it is harmless. The alias becomes free at once, even where the KMS delays the deletion itself. */
    fun destroy(kekUri: String)
}

sealed class KeyManagementException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

class KeyNotFoundException(
    kekUri: String,
    cause: Throwable? = null,
) : KeyManagementException("Key not found: $kekUri", cause)

/** The material was wrapped by another key or altered after wrapping. KMS APIs do not always tell these apart. */
class WrongKeyException(
    kekUri: String,
    cause: Throwable? = null,
) : KeyManagementException("Material does not belong to key: $kekUri", cause)

class KeyManagementUnavailableException(
    message: String,
    cause: Throwable? = null,
) : KeyManagementException(message, cause)

internal fun requireKeyAlias(alias: String): String {
    require(KEY_ALIAS.matches(alias)) { "Invalid key alias" }
    return alias
}
