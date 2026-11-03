package idp.ops

import idp.federation.base64
import idp.federation.rsaKeyPair
import idp.federation.selfSigned
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
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPair
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.time.Duration
import java.time.Instant
import java.util.UUID

const val OPERATOR_EMAIL = "operator@platform.example"
const val PASSKEY = "urn:idp:ac:classes:passkey"
const val MFA = "https://refeds.org/profile/mfa"
const val OPS_ACS = "https://ops.idp.test/login/saml2/sso/ops-sso"
const val OPS_ENTITY_ID = "https://ops.idp.test/saml2/metadata"
const val IDP_ENTITY_ID = "https://ops-sso.idp.test/saml/metadata"
const val RSA_PSS_SHA256 = "http://www.w3.org/2007/05/xmldsig-more#sha256-rsa-MGF1"
const val BEARER = "urn:oasis:names:tc:SAML:2.0:cm:bearer"
const val HOLDER_OF_KEY = "urn:oasis:names:tc:SAML:2.0:cm:holder-of-key"
const val EMAIL_FORMAT = "urn:oasis:names:tc:SAML:1.1:nameid-format:emailAddress"

private val opensaml: Unit by lazy { InitializationService.initialize() }
private val parsers by lazy { BasicParserPool().apply { initialize() } }

/** What a test can bend in a Response. The defaults are what the service tenant sends `ops` after a passkey login. */
data class Login(
    val inResponseTo: String?,
    val nameId: String = OPERATOR_EMAIL,
    val nameIdFormat: String = EMAIL_FORMAT,
    val contextClass: String = PASSKEY,
    val sessionNotOnOrAfter: Instant? = Instant.now().plus(Duration.ofHours(1)),
    val issuer: String = IDP_ENTITY_ID,
    val audience: String = OPS_ENTITY_ID,
    val destination: String = OPS_ACS,
    val recipient: String = OPS_ACS,
    val confirmationMethod: String = BEARER,
    val issuedAt: Instant = Instant.now(),
    val validFor: Duration = Duration.ofMinutes(5),
    val assertionId: String = "_${UUID.randomUUID()}",
    val signAssertion: Boolean = true,
    val signResponse: Boolean = false,
    val algorithm: String = RSA_PSS_SHA256,
    val digest: String = SignatureConstants.ALGO_ID_DIGEST_SHA256,
    val doctype: Boolean = false,
)

/** Stands in for the service tenant: the metadata `ops` is given, and Responses signed with the key in it or with another one. */
class FakeIdp(
    entityId: String = IDP_ENTITY_ID,
) {
    val keyPair: KeyPair = rsaKeyPair(2048)
    private val certificate = selfSignedCertificate(keyPair)

    val metadataFile: Path =
        Files.createTempFile("idp-metadata", ".xml").also {
            Files.writeString(
                it,
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <md:EntityDescriptor xmlns:md="urn:oasis:names:tc:SAML:2.0:metadata" xmlns:ds="http://www.w3.org/2000/09/xmldsig#"
                    entityID="$entityId">
                  <md:IDPSSODescriptor protocolSupportEnumeration="urn:oasis:names:tc:SAML:2.0:protocol">
                    <md:KeyDescriptor use="signing">
                      <ds:KeyInfo><ds:X509Data><ds:X509Certificate>${base64(
                    certificate.encoded,
                )}</ds:X509Certificate></ds:X509Data></ds:KeyInfo>
                    </md:KeyDescriptor>
                    <md:NameIDFormat>urn:oasis:names:tc:SAML:2.0:nameid-format:persistent</md:NameIDFormat>
                    <md:SingleSignOnService Binding="urn:oasis:names:tc:SAML:2.0:bindings:HTTP-Redirect"
                        Location="https://ops-sso.idp.test/saml/sso"/>
                    <md:SingleSignOnService Binding="urn:oasis:names:tc:SAML:2.0:bindings:HTTP-POST"
                        Location="https://ops-sso.idp.test/saml/sso"/>
                  </md:IDPSSODescriptor>
                </md:EntityDescriptor>
                """.trimIndent(),
            )
        }

    /** The value of the `SAMLResponse` field, signed with [key], by default the one in the metadata. */
    fun response(
        login: Login,
        key: KeyPair = keyPair,
    ): String {
        val now = login.issuedAt
        val until = now.plus(login.validFor)
        val inResponseTo = login.inResponseTo?.let { """ InResponseTo="$it"""" }.orEmpty()
        val sessionEnd = login.sessionNotOnOrAfter?.let { """ SessionNotOnOrAfter="$it"""" }.orEmpty()
        val assertion =
            """
            <saml:Assertion xmlns:saml="urn:oasis:names:tc:SAML:2.0:assertion" ID="${login.assertionId}" IssueInstant="$now" Version="2.0">
              <saml:Issuer>${login.issuer}</saml:Issuer>
              <saml:Subject>
                <saml:NameID Format="${login.nameIdFormat}">${login.nameId}</saml:NameID>
                <saml:SubjectConfirmation Method="${login.confirmationMethod}">
                  <saml:SubjectConfirmationData$inResponseTo NotOnOrAfter="$until" Recipient="${login.recipient}"/>
                </saml:SubjectConfirmation>
              </saml:Subject>
              <saml:Conditions NotBefore="${now.minusSeconds(1)}" NotOnOrAfter="$until">
                <saml:AudienceRestriction><saml:Audience>${login.audience}</saml:Audience></saml:AudienceRestriction>
              </saml:Conditions>
              <saml:AuthnStatement AuthnInstant="$now" SessionIndex="_session"$sessionEnd>
                <saml:AuthnContext><saml:AuthnContextClassRef>${login.contextClass}</saml:AuthnContextClassRef></saml:AuthnContext>
              </saml:AuthnStatement>
            </saml:Assertion>
            """.trimIndent()
        val signedAssertion = if (login.signAssertion) sign(assertion, login, key) else assertion
        val response =
            """
            <samlp:Response xmlns:samlp="urn:oasis:names:tc:SAML:2.0:protocol" xmlns:saml="urn:oasis:names:tc:SAML:2.0:assertion"
                ID="_${UUID.randomUUID()}" Version="2.0" IssueInstant="$now" Destination="${login.destination}"$inResponseTo>
              <saml:Issuer>${login.issuer}</saml:Issuer>
              <samlp:Status><samlp:StatusCode Value="urn:oasis:names:tc:SAML:2.0:status:Success"/></samlp:Status>
              $signedAssertion
            </samlp:Response>
            """.trimIndent()
        val signed = if (login.signResponse) sign(response, login, key) else response
        val document = if (login.doctype) """<!DOCTYPE samlp:Response [<!ENTITY x "x">]>$signed""" else signed
        return base64(document.toByteArray())
    }

    private fun sign(
        xml: String,
        login: Login,
        key: KeyPair,
    ): String {
        opensaml
        val root = parsers.parse(xml.byteInputStream()).documentElement
        val signable =
            XMLObjectProviderRegistrySupport.getUnmarshallerFactory().getUnmarshaller(root)!!.unmarshall(root) as SignableSAMLObject
        val signature =
            (XMLObjectSupport.buildXMLObject(Signature.DEFAULT_ELEMENT_NAME) as Signature).apply {
                signingCredential = BasicX509Credential(certificateOf(key), key.private)
                signatureAlgorithm = login.algorithm
                canonicalizationAlgorithm = SignatureConstants.ALGO_ID_C14N_EXCL_OMIT_COMMENTS
            }
        signable.signature = signature
        (signature.contentReferences.single() as SAMLObjectContentReference).digestAlgorithm = login.digest
        XMLObjectSupport.marshall(signable)
        Signer.signObject(signature)
        return SerializeSupport.nodeToString(signable.dom!!).substringAfter("?>").trimStart()
    }

    private fun certificateOf(key: KeyPair): X509Certificate = if (key === keyPair) certificate else selfSignedCertificate(key)
}

private fun selfSignedCertificate(key: KeyPair): X509Certificate =
    CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(selfSigned(key))) as X509Certificate
