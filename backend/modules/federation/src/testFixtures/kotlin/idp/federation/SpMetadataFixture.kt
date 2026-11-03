package idp.federation

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.Date

fun rsaKeyPair(bits: Int): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(bits) }.generateKeyPair()

fun selfSigned(
    keyPair: KeyPair,
    algorithm: String = "SHA256withRSA",
): ByteArray {
    val name = X500Name("CN=sp.example")
    val now = Instant.now()
    return JcaX509v3CertificateBuilder(name, BigInteger.ONE, Date.from(now), Date.from(now + Duration.ofDays(1)), name, keyPair.public)
        .build(JcaContentSignerBuilder(algorithm).build(keyPair.private))
        .encoded
}

/** A certificate is only carried here, never checked, so one throwaway certificate serves every SP of the tests. */
val SP_CERTIFICATE: String by lazy { Base64.getEncoder().encodeToString(selfSigned(rsaKeyPair(2048))) }

class Acs(
    val location: String,
    val index: Int = 0,
    val isDefault: Boolean? = null,
    val binding: String = POST,
)

const val POST = "urn:oasis:names:tc:SAML:2.0:bindings:HTTP-POST"
const val ARTIFACT = "urn:oasis:names:tc:SAML:2.0:bindings:HTTP-Artifact"

fun spMetadata(
    entityId: String = "https://sp.example/saml",
    acs: List<Acs> = listOf(Acs("https://sp.example/saml/acs")),
    authnRequestsSigned: Boolean? = null,
    nameIdFormats: List<String> = emptyList(),
    certificate: String? = SP_CERTIFICATE,
    keyUse: String? = "signing",
): String {
    val signed = authnRequestsSigned?.let { """ AuthnRequestsSigned="$it"""" }.orEmpty()
    val use = keyUse?.let { """ use="$it"""" }.orEmpty()
    val key =
        certificate
            ?.let {
                """
                <md:KeyDescriptor$use>
                  <ds:KeyInfo><ds:X509Data><ds:X509Certificate>$it</ds:X509Certificate></ds:X509Data></ds:KeyInfo>
                </md:KeyDescriptor>
                """
            }.orEmpty()
    val formats = nameIdFormats.joinToString("") { "<md:NameIDFormat>$it</md:NameIDFormat>" }
    val endpoints =
        acs.joinToString("") { endpoint ->
            val default = endpoint.isDefault?.let { """ isDefault="$it"""" }.orEmpty()
            """<md:AssertionConsumerService Binding="${endpoint.binding}" Location="${endpoint.location}" index="${endpoint.index}"$default/>"""
        }
    return """
        <?xml version="1.0" encoding="UTF-8"?>
        <md:EntityDescriptor xmlns:md="urn:oasis:names:tc:SAML:2.0:metadata" xmlns:ds="http://www.w3.org/2000/09/xmldsig#"
            entityID="$entityId">
          <md:SPSSODescriptor protocolSupportEnumeration="urn:oasis:names:tc:SAML:2.0:protocol"$signed>
            $key
            $formats
            $endpoints
          </md:SPSSODescriptor>
        </md:EntityDescriptor>
        """.trimIndent()
}
