package idp.federation.application

import idp.sharedkernel.domain.DomainRuleViolation

class WeakSignatureAlgorithm : DomainRuleViolation("SHA-1 and unknown signature algorithms are not accepted")

enum class NameIdFormat(
    val uri: String,
) {
    EMAIL("urn:oasis:names:tc:SAML:1.1:nameid-format:emailAddress"),
    PERSISTENT("urn:oasis:names:tc:SAML:2.0:nameid-format:persistent"),
}

enum class SignedPart {
    ASSERTION,
    RESPONSE,
    BOTH,
}

/** No SHA-1 member: a setting cannot name what the system refuses to produce. The digest is always SHA-256. */
enum class SignatureAlgorithm(
    val uri: String,
) {
    RSA_PSS_SHA256("http://www.w3.org/2007/05/xmldsig-more#sha256-rsa-MGF1"),

    /** RSASSA-PKCS1-v1_5, for an SP that verifies nothing else. See V11.6.1 in `docs/security/asvs-l2.md`. */
    RSA_SHA256("http://www.w3.org/2001/04/xmldsig-more#rsa-sha256"),
    ;

    companion object {
        const val DIGEST_URI = "http://www.w3.org/2001/04/xmlenc#sha256"

        fun ofUri(uri: String): SignatureAlgorithm = entries.firstOrNull { it.uri == uri } ?: throw WeakSignatureAlgorithm()
    }
}

/**
 * The defaults reveal the least: a NameID that means nothing outside the SP, and an approved algorithm. The email and the legacy
 * algorithm never come from metadata, an admin picks them for one SP.
 */
data class SpSettings(
    val nameIdFormat: NameIdFormat = NameIdFormat.PERSISTENT,
    val signedPart: SignedPart = SignedPart.ASSERTION,
    val signatureAlgorithm: SignatureAlgorithm = SignatureAlgorithm.RSA_PSS_SHA256,
)
