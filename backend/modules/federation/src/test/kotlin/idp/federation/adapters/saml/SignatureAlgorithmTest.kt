package idp.federation.adapters.saml

import idp.federation.adapters.crypto.SigningKeyGenerator
import idp.federation.application.SignatureAlgorithm
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import net.shibboleth.shared.xml.SerializeSupport
import org.opensaml.core.xml.util.XMLObjectSupport
import org.opensaml.saml.common.SAMLObjectContentReference
import org.opensaml.saml.common.SAMLVersion
import org.opensaml.saml.saml2.core.Assertion
import org.opensaml.saml.saml2.core.Issuer
import org.opensaml.security.x509.BasicX509Credential
import org.opensaml.xmlsec.signature.Signature
import org.opensaml.xmlsec.signature.support.SignatureConstants
import org.opensaml.xmlsec.signature.support.SignatureException
import org.opensaml.xmlsec.signature.support.SignatureValidator
import org.opensaml.xmlsec.signature.support.Signer
import java.time.Instant

private val material by lazy { SigningKeyGenerator().generate("acme.idp.test", Instant.now()) }

/** Unmarshalled from its own XML, as an SP receives it, so nothing of the signing objects is reused. */
private fun signedAssertion(algorithm: SignatureAlgorithm): Assertion {
    val assertion =
        OpenSaml.build<Assertion>(Assertion.DEFAULT_ELEMENT_NAME).apply {
            id = "_assertion"
            version = SAMLVersion.VERSION_20
            issueInstant = Instant.now()
            issuer = OpenSaml.build<Issuer>(Issuer.DEFAULT_ELEMENT_NAME).apply { value = "https://acme.idp.test/saml/metadata" }
        }
    val signature =
        OpenSaml.build<Signature>(Signature.DEFAULT_ELEMENT_NAME).apply {
            signingCredential = BasicX509Credential(material.certificate, material.keyPair.private)
            signatureAlgorithm = algorithm.uri
            canonicalizationAlgorithm = SignatureConstants.ALGO_ID_C14N_EXCL_OMIT_COMMENTS
        }
    assertion.signature = signature
    (signature.contentReferences.single() as SAMLObjectContentReference).digestAlgorithm = SignatureAlgorithm.DIGEST_URI
    XMLObjectSupport.marshall(assertion)
    Signer.signObject(signature)
    val xml = SerializeSupport.nodeToString(assertion.dom!!)
    return OpenSaml.unmarshall(OpenSaml.parse(xml.toByteArray()).documentElement) as Assertion
}

class SignatureAlgorithmTest :
    FunSpec({
        SignatureAlgorithm.entries.forEach { algorithm ->
            test("OpenSAML signs with $algorithm and verifies it with the published certificate") {
                val assertion = signedAssertion(algorithm)

                assertion.signature!!.signatureAlgorithm shouldBe algorithm.uri
                SignatureValidator.validate(assertion.signature!!, BasicX509Credential(material.certificate))
            }
        }

        test("a signature does not verify with another key") {
            val other = SigningKeyGenerator().generate("globex.idp.test", Instant.now())

            shouldThrow<SignatureException> {
                SignatureValidator.validate(
                    signedAssertion(SignatureAlgorithm.RSA_PSS_SHA256).signature!!,
                    BasicX509Credential(other.certificate),
                )
            }
        }
    })
