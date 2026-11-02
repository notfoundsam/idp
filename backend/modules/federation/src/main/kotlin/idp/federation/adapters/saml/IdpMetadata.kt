package idp.federation.adapters.saml

import idp.federation.application.NameIdFormat
import net.shibboleth.shared.xml.SerializeSupport
import org.opensaml.core.xml.util.XMLObjectSupport
import org.opensaml.saml.common.xml.SAMLConstants
import org.opensaml.saml.saml2.metadata.EntityDescriptor
import org.opensaml.saml.saml2.metadata.IDPSSODescriptor
import org.opensaml.saml.saml2.metadata.KeyDescriptor
import org.opensaml.saml.saml2.metadata.NameIDFormat
import org.opensaml.saml.saml2.metadata.SingleSignOnService
import org.opensaml.security.credential.UsageType
import org.opensaml.xmlsec.keyinfo.KeyInfoSupport
import org.opensaml.xmlsec.signature.KeyInfo
import java.security.cert.X509Certificate

private const val SSO_PATH = "/saml/sso"

internal object IdpMetadata {
    /** One signing key descriptor per published certificate, the signing one first. */
    fun write(
        entityId: String,
        host: String,
        certificates: List<X509Certificate>,
    ): String {
        val idp =
            OpenSaml.build<IDPSSODescriptor>(IDPSSODescriptor.DEFAULT_ELEMENT_NAME).apply {
                addSupportedProtocol(SAMLConstants.SAML20P_NS)
                keyDescriptors += certificates.map(::signingKey)
                nameIDFormats +=
                    NameIdFormat.entries.map { format ->
                        OpenSaml.build<NameIDFormat>(NameIDFormat.DEFAULT_ELEMENT_NAME).apply { uri = format.uri }
                    }
                singleSignOnServices +=
                    listOf(SAMLConstants.SAML2_REDIRECT_BINDING_URI, SAMLConstants.SAML2_POST_BINDING_URI).map { binding ->
                        OpenSaml.build<SingleSignOnService>(SingleSignOnService.DEFAULT_ELEMENT_NAME).apply {
                            this.binding = binding
                            location = "https://$host$SSO_PATH"
                        }
                    }
            }
        val entity =
            OpenSaml.build<EntityDescriptor>(EntityDescriptor.DEFAULT_ELEMENT_NAME).apply {
                this.entityID = entityId
                roleDescriptors += idp
            }
        return SerializeSupport.nodeToString(XMLObjectSupport.marshall(entity))
    }

    private fun signingKey(certificate: X509Certificate): KeyDescriptor =
        OpenSaml.build<KeyDescriptor>(KeyDescriptor.DEFAULT_ELEMENT_NAME).apply {
            use = UsageType.SIGNING
            keyInfo =
                OpenSaml.build<KeyInfo>(KeyInfo.DEFAULT_ELEMENT_NAME).also { KeyInfoSupport.addCertificate(it, certificate) }
        }
}
