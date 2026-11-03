package idp.federation.adapters.saml

import java.net.URI
import java.security.SecureRandom
import java.util.Base64

private const val NONCE_BYTES = 16

/** [csp] replaces the closed default policy, which a page without it keeps. */
internal class SamlPage(
    val status: Int,
    val html: String,
    val csp: String? = null,
)

internal object SamlPages {
    private val random = SecureRandom()

    /** Says nothing of the request, so a refusal neither echoes what was sent nor tells which check failed. */
    fun error(): SamlPage =
        SamlPage(
            400,
            page("Sign-in failed", "<p>The sign-in request could not be accepted. Go back to the application and try again.</p>"),
        )

    /** Shown only after a login, so an anonymous visitor never learns who may reach which SP. */
    fun denied(): SamlPage =
        SamlPage(
            403,
            page("Access denied", "<p>You do not have access to this application. Ask your administrator to give you access.</p>"),
        )

    /**
     * A navigation the page starts itself counts as same-site, unlike the redirect of a cross-site POST, so the `SameSite` cookies of
     * this host come along. A meta refresh needs no script, and the closed policy allows it.
     */
    fun bounce(path: String): SamlPage {
        val target = escape(path)
        return SamlPage(
            200,
            page(
                "Signing in",
                """<p><a href="$target">Continue</a></p>""",
                """<meta http-equiv="refresh" content="0;url=$target">""",
            ),
        )
    }

    /** The policy lets the form go to this ACS only. A browser without scripts shows the button. */
    fun post(
        acsUrl: String,
        samlResponse: String,
        relayState: String?,
    ): SamlPage {
        val nonce = Base64.getEncoder().encodeToString(ByteArray(NONCE_BYTES).also(random::nextBytes))
        val relay = relayState?.let { """<input type="hidden" name="RelayState" value="${escape(it)}">""" }.orEmpty()
        val body =
            """
            <form method="post" action="${escape(acsUrl)}">
            <input type="hidden" name="SAMLResponse" value="${escape(samlResponse)}">
            $relay
            <noscript><button type="submit">Continue</button></noscript>
            </form>
            <script nonce="$nonce">document.forms[0].submit()</script>
            """.trimIndent()
        val csp = "default-src 'none'; script-src 'nonce-$nonce'; form-action ${source(acsUrl)}; base-uri 'none'; frame-ancestors 'none'"
        return SamlPage(200, page("Signing in", body), csp)
    }

    /** CSP matches the path of a source and ignores its query. `;` and `,` would end the source, so they stay encoded. */
    private fun source(url: String): String {
        val uri = URI(url)
        val port = if (uri.port == -1) "" else ":${uri.port}"
        val path =
            uri.rawPath
                .orEmpty()
                .replace(";", "%3B")
                .replace(",", "%2C")
                .ifEmpty { "/" }
        return "${uri.scheme}://${uri.host}$port$path"
    }

    private fun page(
        title: String,
        body: String,
        head: String = "",
    ) = """
        <!doctype html>
        <html lang="en"><head><meta charset="utf-8"><title>$title</title>$head</head>
        <body>$body</body></html>
        """.trimIndent()

    private fun escape(value: String): String =
        buildString {
            value.forEach { char ->
                when (char) {
                    '&' -> append("&amp;")
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '"' -> append("&quot;")
                    '\'' -> append("&#39;")
                    else -> append(char)
                }
            }
        }
}
