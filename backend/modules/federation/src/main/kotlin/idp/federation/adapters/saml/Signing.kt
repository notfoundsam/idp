package idp.federation.adapters.saml

import idp.federation.application.SignatureAlgorithm
import idp.federation.application.SigningCredential
import net.shibboleth.shared.xml.SerializeSupport
import org.opensaml.core.xml.util.XMLObjectSupport
import org.opensaml.saml.common.SAMLObjectContentReference
import org.opensaml.saml.common.SignableSAMLObject
import org.opensaml.security.x509.BasicX509Credential
import org.opensaml.xmlsec.signature.Signature
import org.opensaml.xmlsec.signature.support.SignatureConstants
import org.opensaml.xmlsec.signature.support.Signer
import java.security.SecureRandom
import java.util.Base64
import java.util.HexFormat

private const val ID_BYTES = 16

private val random = SecureRandom()

/** An xs:ID must not start with a digit, hence the prefix. */
internal fun samlId(): String = "_" + HexFormat.of().formatHex(ByteArray(ID_BYTES).also(random::nextBytes))

/**
 * Signs [targets] inside [root] and returns the base64 of the whole document. The document is marshalled once with every signature
 * in place, and the inner ones are signed first, so an outer signature covers the finished inner one.
 */
internal fun signedBase64(
    root: SignableSAMLObject,
    targets: List<SignableSAMLObject>,
    credential: SigningCredential,
    algorithm: SignatureAlgorithm,
): String {
    val signatures =
        targets.map { target ->
            OpenSaml
                .build<Signature>(Signature.DEFAULT_ELEMENT_NAME)
                .apply {
                    signingCredential = BasicX509Credential(credential.certificate, credential.privateKey)
                    signatureAlgorithm = algorithm.uri
                    canonicalizationAlgorithm = SignatureConstants.ALGO_ID_C14N_EXCL_OMIT_COMMENTS
                }.also { signature ->
                    target.signature = signature
                    (signature.contentReferences.single() as SAMLObjectContentReference).digestAlgorithm = SignatureAlgorithm.DIGEST_URI
                }
        }
    XMLObjectSupport.marshall(root)
    signatures.forEach(Signer::signObject)
    return Base64.getEncoder().encodeToString(SerializeSupport.nodeToString(root.dom!!).toByteArray())
}
