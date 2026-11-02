package idp.federation.adapters.crypto

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

class SigningKeyGeneratorTest :
    FunSpec({
        val now = Instant.now().truncatedTo(ChronoUnit.SECONDS)
        val material = SigningKeyGenerator().generate("acme.idp.test", now)

        test("the key is RSA of 3072 bits") {
            material.keyPair.public.algorithm shouldBe "RSA"
            (material.keyPair.public as RSAPublicKey).modulus.bitLength() shouldBe 3072
        }

        test("the certificate is self-signed for the host and holds the key") {
            val certificate = material.certificate

            certificate.subjectX500Principal.name shouldBe "CN=acme.idp.test"
            certificate.issuerX500Principal shouldBe certificate.subjectX500Principal
            certificate.publicKey shouldBe material.keyPair.public
            certificate.verify(material.keyPair.public)
        }

        test("the certificate is valid from now for five years") {
            material.certificate.notBefore.toInstant() shouldBe now
            material.certificate.notAfter.toInstant() shouldBe now.atOffset(ZoneOffset.UTC).plusYears(5).toInstant()
        }

        test("two keys share nothing, serial numbers included") {
            val other = SigningKeyGenerator().generate("acme.idp.test", now)

            other.keyPair.public shouldNotBe material.keyPair.public
            other.certificate.serialNumber shouldNotBe material.certificate.serialNumber
        }
    })
