package idp.federation.adapters.saml

import idp.federation.domain.AcsEndpoint
import idp.federation.domain.AcsUrl
import idp.federation.domain.MetadataNotValid
import idp.federation.domain.MetadataTooLarge
import idp.federation.domain.SpDescription
import net.shibboleth.shared.xml.XMLParserException
import org.opensaml.core.xml.io.UnmarshallingException
import org.opensaml.saml.common.xml.SAMLConstants
import org.opensaml.saml.saml2.metadata.AssertionConsumerService
import org.opensaml.saml.saml2.metadata.EntityDescriptor
import org.opensaml.saml.saml2.metadata.SPSSODescriptor
import org.opensaml.security.credential.UsageType
import org.opensaml.xmlsec.keyinfo.KeyInfoSupport
import org.xml.sax.SAXException

/** A Service Provider rarely needs more than a few kilobytes, and an aggregate of many entities is not accepted anyway. */
const val MAX_METADATA_BYTES = 128 * 1024

/** Reads a file the tenant admin uploads. Only content arrives here: the upload's file name is never used. */
internal class MetadataImporter(
    private val maxBytes: Int = MAX_METADATA_BYTES,
) {
    fun read(metadata: ByteArray): SpDescription {
        if (metadata.size > maxBytes) throw MetadataTooLarge(maxBytes)
        val descriptor =
            try {
                val document = OpenSaml.parse(metadata)
                OpenSaml.validate(document)
                OpenSaml.unmarshall(document.documentElement)
            } catch (e: XMLParserException) {
                throw MetadataNotValid(e)
            } catch (e: SAXException) {
                throw MetadataNotValid(e)
            } catch (e: UnmarshallingException) {
                throw MetadataNotValid(e)
            }
        val entity = descriptor as? EntityDescriptor ?: throw MetadataNotValid()
        val sp = entity.getSPSSODescriptor(SAMLConstants.SAML20P_NS) ?: throw MetadataNotValid()
        val certificates = signingCertificates(sp)
        val authnRequestsSigned = sp.isAuthnRequestsSigned == true
        if (authnRequestsSigned && certificates.isEmpty()) throw MetadataNotValid()
        return SpDescription(
            entityId = entity.entityID?.takeIf { it.isNotBlank() } ?: throw MetadataNotValid(),
            acs = postEndpoints(sp),
            signingCertificates = certificates,
            authnRequestsSigned = authnRequestsSigned,
        )
    }

    /** The Response travels by HTTP-POST only. The default follows SAML metadata 2.2.3: the first marked, then the first not unmarked. */
    private fun postEndpoints(sp: SPSSODescriptor): List<AcsEndpoint> {
        val post = sp.assertionConsumerServices.filter { it.binding == SAMLConstants.SAML2_POST_BINDING_URI }
        if (post.isEmpty() || post.map { it.index }.toSet().size != post.size) throw MetadataNotValid()
        val default =
            post.firstOrNull { it.isDefaultXSBoolean?.value == true } ?: post.firstOrNull { it.isDefaultXSBoolean == null } ?: post.first()
        return post.map { endpoint(it, it === default) }
    }

    private fun endpoint(
        acs: AssertionConsumerService,
        isDefault: Boolean,
    ) = AcsEndpoint(
        index = acs.index ?: throw MetadataNotValid(),
        url = AcsUrl.of(acs.location ?: throw MetadataNotValid()),
        isDefault = isDefault,
    )

    private fun signingCertificates(sp: SPSSODescriptor): List<ByteArray> =
        sp.keyDescriptors
            .filter { it.use == null || it.use == UsageType.SIGNING || it.use == UsageType.UNSPECIFIED }
            .flatMap { descriptor ->
                // A broken DER fails with an unchecked exception of the JDK, not only with CertificateException.
                try {
                    descriptor.keyInfo?.let(KeyInfoSupport::getCertificates).orEmpty()
                } catch (e: Exception) {
                    throw MetadataNotValid(e)
                }
            }.map { it.encoded }
}
