package idp.authentication.application

private const val ATTRIBUTES = "Path=/; Secure; HttpOnly; SameSite=Lax"

/**
 * `__Host-` makes a browser refuse the cookie unless it is `Secure`, has `Path=/` and has no `Domain`, so a neighbouring tenant
 * cannot plant one. No `Max-Age`: the server decides how long a session lives.
 */
object SessionCookie {
    const val NAME = "__Host-session"

    fun issue(token: String): String = "$NAME=$token; $ATTRIBUTES"

    fun clear(): String = "$NAME=; Max-Age=0; $ATTRIBUTES"
}
