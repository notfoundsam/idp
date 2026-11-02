package idp.authentication.application

private const val ATTRIBUTES = "Path=/; Secure; HttpOnly; SameSite=Strict"

/** Holds the secret that binds flows to this browser. Parallel tabs share it, each with a flow of its own. */
object FlowCookie {
    const val NAME = "__Host-flow"

    fun issue(browserSecret: String): String = "$NAME=$browserSecret; $ATTRIBUTES"
}
