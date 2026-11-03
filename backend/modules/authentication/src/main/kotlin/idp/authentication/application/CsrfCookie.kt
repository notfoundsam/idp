package idp.authentication.application

import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private const val HMAC = "HmacSHA256"
private const val ATTRIBUTES = "Path=/; Secure; SameSite=Strict"
private val PURPOSE = "csrf".toByteArray()

/**
 * The token is keyed by the session token, so it needs no storage or key of its own, and nobody can compute it without the cookie a
 * forged request cannot read. Without `HttpOnly`, because the page has to echo it in [HEADER].
 */
object CsrfCookie {
    const val NAME = "__Host-csrf"
    const val HEADER = "X-CSRF-Token"

    fun issue(sessionToken: String): String = "$NAME=${tokenFor(sessionToken)}; $ATTRIBUTES"

    fun clear(): String = "$NAME=; Max-Age=0; $ATTRIBUTES"

    fun tokenFor(sessionToken: String): String {
        val mac = Mac.getInstance(HMAC).apply { init(SecretKeySpec(sessionToken.toByteArray(), HMAC)) }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(PURPOSE))
    }

    fun matches(
        sessionToken: String,
        presented: String?,
    ): Boolean = presented != null && MessageDigest.isEqual(tokenFor(sessionToken).toByteArray(), presented.toByteArray())
}
