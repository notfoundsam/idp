package idp.federation.adapters.crypto

import idp.federation.domain.MIN_RSA_BITS
import org.bouncycastle.asn1.x500.X500NameBuilder
import org.bouncycastle.asn1.x500.style.BCStyle
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.time.Instant
import java.time.Period
import java.time.ZoneOffset
import java.util.Date

private const val SERIAL_BITS = 128

/** SPs pin the certificate from metadata, so nobody checks its own signature or its dates. They are there for SPs that insist. */
private const val CERTIFICATE_SIGNATURE = "SHA256withRSA"
private val VALIDITY: Period = Period.ofYears(5)

internal class SigningMaterial(
    val keyPair: KeyPair,
    val certificate: X509Certificate,
)

internal class SigningKeyGenerator(
    private val random: SecureRandom = SecureRandom(),
) {
    fun generate(
        host: String,
        now: Instant,
    ): SigningMaterial {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(MIN_RSA_BITS, random) }.generateKeyPair()
        val subject = X500NameBuilder(BCStyle.INSTANCE).addRDN(BCStyle.CN, host).build()
        val notAfter = now.atOffset(ZoneOffset.UTC).plus(VALIDITY).toInstant()
        val holder =
            JcaX509v3CertificateBuilder(
                subject,
                BigInteger(SERIAL_BITS, random),
                Date.from(now),
                Date.from(notAfter),
                subject,
                keyPair.public,
            ).build(JcaContentSignerBuilder(CERTIFICATE_SIGNATURE).build(keyPair.private))
        return SigningMaterial(keyPair, JcaX509CertificateConverter().getCertificate(holder))
    }
}
