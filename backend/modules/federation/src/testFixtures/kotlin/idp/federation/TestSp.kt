package idp.federation

import net.shibboleth.shared.xml.SerializeSupport
import net.shibboleth.shared.xml.impl.BasicParserPool
import org.opensaml.core.config.InitializationService
import org.opensaml.core.xml.config.XMLObjectProviderRegistrySupport
import org.opensaml.core.xml.util.XMLObjectSupport
import org.opensaml.saml.common.SAMLObjectContentReference
import org.opensaml.saml.common.SignableSAMLObject
import org.opensaml.security.x509.BasicX509Credential
import org.opensaml.xmlsec.signature.Signature
import org.opensaml.xmlsec.signature.support.SignatureConstants
import org.opensaml.xmlsec.signature.support.Signer
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.security.KeyPair
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

const val SP_ENTITY_ID = "https://sp.example/saml"
const val SP_ACS = "https://sp.example/saml/acs"
const val IDP_SSO = "https://acme.idp.test/saml/sso"

const val RSA_SHA256 = SignatureConstants.ALGO_ID_SIGNATURE_RSA_SHA256
const val RSA_SHA1 = SignatureConstants.ALGO_ID_SIGNATURE_RSA_SHA1
const val PPT = "urn:oasis:names:tc:SAML:2.0:ac:classes:PasswordProtectedTransport"
const val REFEDS_MFA = "https://refeds.org/profile/mfa"
const val PASSKEY_CLASS = "urn:idp:ac:classes:passkey"

private val opensaml: Unit by lazy { InitializationService.initialize() }
private val parsers by lazy { BasicParserPool().apply { initialize() } }

private fun encoded(value: String): String = URLEncoder.encode(value, Charsets.UTF_8)

fun base64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

fun deflated(bytes: ByteArray): ByteArray {
    val out = ByteArrayOutputStream()
    DeflaterOutputStream(out, Deflater(Deflater.DEFAULT_COMPRESSION, true)).use { it.write(bytes) }
    return out.toByteArray()
}

/** Strips the declaration, so the XML can sit inside another document. */
fun String.withoutDeclaration(): String = if (startsWith("<?xml")) substringAfter("?>").trimStart() else this

fun requestedContext(
    vararg classes: String,
    comparison: String? = null,
): String {
    val attribute = comparison?.let { """ Comparison="$it"""" }.orEmpty()
    val refs = classes.joinToString("") { "<saml:AuthnContextClassRef>$it</saml:AuthnContextClassRef>" }
    return "<samlp:RequestedAuthnContext$attribute>$refs</samlp:RequestedAuthnContext>"
}

/** An AuthnRequest written out by hand, so a test can bend any part of it. */
fun authnRequest(
    issuer: String? = SP_ENTITY_ID,
    id: String = "_${UUID.randomUUID()}",
    destination: String? = IDP_SSO,
    issueInstant: Instant = Instant.now(),
    acsUrl: String? = null,
    acsIndex: Int? = null,
    protocolBinding: String? = null,
    forceAuthn: Boolean? = null,
    isPassive: Boolean? = null,
    requestedContext: String? = null,
    extensions: String? = null,
    nameIdFormat: String? = null,
    subject: String? = null,
): String {
    val attributes =
        listOfNotNull(
            destination?.let { """Destination="$it"""" },
            acsUrl?.let { """AssertionConsumerServiceURL="$it"""" },
            acsIndex?.let { """AssertionConsumerServiceIndex="$it"""" },
            protocolBinding?.let { """ProtocolBinding="$it"""" },
            forceAuthn?.let { """ForceAuthn="$it"""" },
            isPassive?.let { """IsPassive="$it"""" },
        ).joinToString(" ")
    return """<samlp:AuthnRequest xmlns:samlp="urn:oasis:names:tc:SAML:2.0:protocol" """ +
        """xmlns:saml="urn:oasis:names:tc:SAML:2.0:assertion" ID="$id" Version="2.0" IssueInstant="$issueInstant" $attributes>""" +
        issuer?.let { "<saml:Issuer>$it</saml:Issuer>" }.orEmpty() +
        extensions?.let { "<samlp:Extensions>$it</samlp:Extensions>" }.orEmpty() +
        subject?.let { "<saml:Subject><saml:NameID>$it</saml:NameID></saml:Subject>" }.orEmpty() +
        nameIdFormat?.let { """<samlp:NameIDPolicy Format="$it" AllowCreate="true"/>""" }.orEmpty() +
        requestedContext.orEmpty() +
        "</samlp:AuthnRequest>"
}

/** An SP with a key of its own. Each instance is a different SP key, the EntityID stays the same unless given. */
class TestSp(
    val entityId: String = SP_ENTITY_ID,
    val acs: List<Acs> = listOf(Acs(SP_ACS, 0, true), Acs("https://sp.example/saml/acs2", 1)),
) {
    val keyPair: KeyPair = rsaKeyPair(2048)
    val certificate: X509Certificate =
        CertificateFactory.getInstance("X.509").generateCertificate(selfSigned(keyPair).inputStream()) as X509Certificate

    fun metadata(authnRequestsSigned: Boolean? = null): String =
        spMetadata(entityId, acs, authnRequestsSigned, certificate = base64(certificate.encoded))

    /** HTTP-Redirect: deflated, then signed over the query as sent when [signed]. */
    fun redirectQuery(
        xml: String,
        relayState: String? = null,
        signed: Boolean = false,
        algorithm: String = RSA_SHA256,
        key: KeyPair = keyPair,
    ): String {
        val parts =
            listOfNotNull(
                "SAMLRequest=${encoded(base64(deflated(xml.toByteArray())))}",
                relayState?.let { "RelayState=${encoded(it)}" },
                if (signed) "SigAlg=${encoded(algorithm)}" else null,
            )
        val query = parts.joinToString("&")
        if (!signed) return query
        val javaAlgorithm = if (algorithm == RSA_SHA1) "SHA1withRSA" else "SHA256withRSA"
        val signature =
            java.security.Signature.getInstance(javaAlgorithm).run {
                initSign(key.private)
                update(query.toByteArray())
                sign()
            }
        return "$query&Signature=${encoded(base64(signature))}"
    }

    /** An enveloped signature over the root, as the POST binding carries it. */
    fun signed(
        xml: String,
        algorithm: String = RSA_SHA256,
        digest: String = SignatureConstants.ALGO_ID_DIGEST_SHA256,
        key: KeyPair = keyPair,
    ): String {
        opensaml
        val document = parsers.parse(xml.byteInputStream())
        val root = document.documentElement
        val request =
            XMLObjectProviderRegistrySupport.getUnmarshallerFactory().getUnmarshaller(root)!!.unmarshall(root) as SignableSAMLObject
        val signature =
            (XMLObjectSupport.buildXMLObject(Signature.DEFAULT_ELEMENT_NAME) as Signature).apply {
                signingCredential = BasicX509Credential(certificate, key.private)
                signatureAlgorithm = algorithm
                canonicalizationAlgorithm = SignatureConstants.ALGO_ID_C14N_EXCL_OMIT_COMMENTS
            }
        request.signature = signature
        (signature.contentReferences.single() as SAMLObjectContentReference).digestAlgorithm = digest
        XMLObjectSupport.marshall(request)
        Signer.signObject(signature)
        return SerializeSupport.nodeToString(request.dom!!).withoutDeclaration()
    }
}

/** The fields of an HTTP-POST form, before the browser encodes them. */
fun postForm(
    xml: String,
    relayState: String? = null,
): Map<String, List<String>> =
    buildMap {
        put("SAMLRequest", listOf(base64(xml.toByteArray())))
        relayState?.let { put("RelayState", listOf(it)) }
    }

fun formBody(fields: Map<String, List<String>>): String =
    fields.entries.joinToString("&") { (name, values) -> values.joinToString("&") { "${encoded(name)}=${encoded(it)}" } }
