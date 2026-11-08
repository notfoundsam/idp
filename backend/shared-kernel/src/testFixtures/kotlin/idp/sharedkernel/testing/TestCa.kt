package idp.sharedkernel.testing

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.Date
import java.util.concurrent.atomic.AtomicLong

private val serials = AtomicLong()

private fun pem(
    type: String,
    der: ByteArray,
) = "-----BEGIN $type-----\n" + Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der) + "\n-----END $type-----\n"

private fun ecKeys(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()

/** A CA of a test, valid for a day, that issues server certificates the way the internal CA and the AWS ones do. */
class TestCa(
    name: String,
) {
    private val keys = ecKeys()
    private val subject = X500Name("CN=$name")

    val pem: String =
        pem(
            "CERTIFICATE",
            sign(subject, keys, BasicConstraints(true)) {
                it.addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.keyCertSign or KeyUsage.cRLSign))
            },
        )

    /** A server certificate for the names and its PKCS #8 key. */
    fun issue(vararg names: String): Pair<String, String> {
        val leaf = ecKeys()
        val der =
            sign(X500Name("CN=${names.first()}"), leaf, BasicConstraints(false)) {
                it.addExtension(
                    Extension.subjectAlternativeName,
                    false,
                    GeneralNames(names.map { name -> GeneralName(GeneralName.dNSName, name) }.toTypedArray()),
                )
                it.addExtension(Extension.extendedKeyUsage, false, ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth))
            }
        return pem("CERTIFICATE", der) to pem("PRIVATE KEY", leaf.private.encoded)
    }

    private fun sign(
        name: X500Name,
        owner: KeyPair,
        constraints: BasicConstraints,
        extend: (X509v3CertificateBuilder) -> Unit,
    ): ByteArray {
        val now = Instant.now()
        val builder =
            JcaX509v3CertificateBuilder(
                subject,
                BigInteger.valueOf(serials.incrementAndGet()),
                Date.from(now - Duration.ofHours(1)),
                Date.from(now + Duration.ofDays(1)),
                name,
                owner.public,
            ).addExtension(Extension.basicConstraints, true, constraints)
        extend(builder)
        return builder.build(JcaContentSignerBuilder("SHA256withECDSA").build(keys.private)).encoded
    }
}
