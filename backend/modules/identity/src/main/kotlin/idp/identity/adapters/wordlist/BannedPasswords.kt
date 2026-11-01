package idp.identity.adapters.wordlist

private const val RESOURCE = "/idp/identity/banned-passwords.txt"

/** Lies in the build, so checking a password calls no outside service. */
internal object BannedPasswords {
    fun load(): Set<String> =
        checkNotNull(javaClass.getResourceAsStream(RESOURCE)) { "No $RESOURCE on the classpath" }
            .bufferedReader()
            .useLines { it.toHashSet() }
}
