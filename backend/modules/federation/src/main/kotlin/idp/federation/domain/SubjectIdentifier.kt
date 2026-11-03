package idp.federation.domain

import java.security.SecureRandom
import java.util.Base64

private const val VALUE_BYTES = 32

/**
 * The persistent NameID of one user at one SP. Random rather than derived, so the value says nothing about the user, two SPs cannot
 * compute each other's, and nothing about it changes with the email.
 */
internal object SubjectIdentifier {
    fun generate(random: SecureRandom): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(VALUE_BYTES).also(random::nextBytes))
}
