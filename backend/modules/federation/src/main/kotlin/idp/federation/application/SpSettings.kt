package idp.federation.application

import idp.identity.application.UserClaim
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

/** An attribute a Response may carry, named by its LDAP OID as most SPs expect, and the claim of the user it comes from. */
enum class SamlAttribute(
    val oid: String,
    val friendlyName: String,
    val claim: UserClaim,
) {
    EMAIL("urn:oid:0.9.2342.19200300.100.1.3", "mail", UserClaim.EMAIL),
    DISPLAY_NAME("urn:oid:2.16.840.1.113730.3.1.241", "displayName", UserClaim.NAME),
    PHONE("urn:oid:2.5.4.20", "telephoneNumber", UserClaim.PHONE),
    ;

    companion object {
        const val NAME_FORMAT = "urn:oasis:names:tc:SAML:2.0:attrname-format:uri"
    }
}

/**
 * The defaults reveal the least: a NameID that means nothing outside the SP, no attributes, and an approved algorithm. The email, the
 * attributes and the legacy algorithm never come from metadata, an admin picks them for one SP.
 */
data class SpSettings(
    val nameIdFormat: NameIdFormat = NameIdFormat.PERSISTENT,
    val signedPart: SignedPart = SignedPart.ASSERTION,
    val signatureAlgorithm: SignatureAlgorithm = SignatureAlgorithm.RSA_PSS_SHA256,
    val attributes: Set<SamlAttribute> = emptySet(),
)
