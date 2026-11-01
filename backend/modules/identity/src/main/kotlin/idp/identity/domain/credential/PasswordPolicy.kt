package idp.identity.domain.credential

import idp.sharedkernel.domain.DomainRuleViolation

private const val MIN_LENGTH = 8
private const val MAX_LENGTH = 128
private const val MIN_WORD_LENGTH = 3
private val PRODUCT_WORDS = setOf("idp")
private val WORD_SEPARATORS = Regex("""[^\p{L}\p{N}]+""")

enum class PasswordRejection { TOO_SHORT, TOO_LONG, BANNED, CONTEXT_WORD }

/** Names the reason and never the password: exception texts reach logs. */
class WeakPassword(
    val reason: PasswordRejection,
) : DomainRuleViolation("The password was refused: $reason")

/** Lowercasing here only compares. The password itself is hashed exactly as received. */
internal class PasswordPolicy(
    private val banned: Set<String>,
) {
    /** Argon2id takes any length, so a limit is what stands between a huge body and a slow hash. */
    fun fitsHash(password: String): Boolean = length(password) <= MAX_LENGTH

    fun check(
        password: String,
        context: Set<String>,
    ) {
        checkLength(password)
        val candidate = password.lowercase()
        if (candidate in banned) throw WeakPassword(PasswordRejection.BANNED)
        if (words(context + PRODUCT_WORDS).any(candidate::contains)) throw WeakPassword(PasswordRejection.CONTEXT_WORD)
    }

    fun checkLength(password: String) {
        if (!fitsHash(password)) throw WeakPassword(PasswordRejection.TOO_LONG)
        if (length(password) < MIN_LENGTH) throw WeakPassword(PasswordRejection.TOO_SHORT)
    }

    private fun length(password: String) = password.codePointCount(0, password.length)

    /** A shorter word would turn up in random passwords. */
    private fun words(context: Set<String>): List<String> =
        context.flatMap { it.lowercase().split(WORD_SEPARATORS) }.filter { it.length >= MIN_WORD_LENGTH }

    companion object {
        const val RECOMMENDED_LENGTH = 15
    }
}
