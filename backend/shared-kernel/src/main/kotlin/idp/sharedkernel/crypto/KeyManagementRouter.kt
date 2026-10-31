package idp.sharedkernel.crypto

/** A tenant keeps the KMS of its KEK URI, so one deployment can hold keys of two KMS while tenants move between them. */
class KeyManagementRouter(
    private val newKeys: KeyManagementService,
    private val adapters: Map<String, KeyManagementService>,
) : KeyManagementService {
    override fun createKey(alias: String): String = newKeys.createKey(alias)

    override fun wrap(
        kekUri: String,
        plaintext: ByteArray,
    ): ByteArray = adapterOf(kekUri).wrap(kekUri, plaintext)

    override fun unwrap(
        kekUri: String,
        wrapped: ByteArray,
    ): ByteArray = adapterOf(kekUri).unwrap(kekUri, wrapped)

    override fun destroy(kekUri: String) = adapterOf(kekUri).destroy(kekUri)

    private fun adapterOf(kekUri: String): KeyManagementService {
        val scheme = kekUri.substringBefore("://", missingDelimiterValue = "")
        return requireNotNull(adapters[scheme]) { "Unsupported KEK URI scheme: $scheme" }
    }
}
