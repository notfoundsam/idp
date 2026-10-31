package idp.sharedkernel.crypto

import java.text.Normalizer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private const val HMAC = "HmacSHA256"

class BlindIndex internal constructor(
    private val key: ByteArray,
) {
    /** Compatibility forms fold too, so a look-alike spelling of an address cannot register as a second user. */
    fun email(value: String): ByteArray {
        val normalized = Normalizer.normalize(value, Normalizer.Form.NFKC).trim().lowercase()
        return Mac.getInstance(HMAC).apply { init(SecretKeySpec(key, HMAC)) }.doFinal(normalized.toByteArray())
    }
}
