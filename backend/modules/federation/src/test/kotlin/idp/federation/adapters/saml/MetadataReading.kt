package idp.federation.adapters.saml

import org.opensaml.saml.common.xml.SAMLConstants
import org.opensaml.saml.saml2.metadata.EntityDescriptor
import org.opensaml.security.credential.UsageType
import org.opensaml.xmlsec.keyinfo.KeyInfoSupport
import java.security.cert.X509Certificate

/** Reads published metadata the way an SP would, schema check included. */
fun parseEntity(xml: String): EntityDescriptor {
    val document = OpenSaml.parse(xml.toByteArray())
    OpenSaml.validate(document)
    return OpenSaml.unmarshall(document.documentElement) as EntityDescriptor
}

fun signingCertificatesOf(entity: EntityDescriptor): List<X509Certificate> =
    entity
        .getIDPSSODescriptor(SAMLConstants.SAML20P_NS)!!
        .keyDescriptors
        .filter { it.use == UsageType.SIGNING }
        .flatMap { KeyInfoSupport.getCertificates(it.keyInfo!!) }
