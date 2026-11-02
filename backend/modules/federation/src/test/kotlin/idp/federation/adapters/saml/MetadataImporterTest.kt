package idp.federation.adapters.saml

import idp.federation.ARTIFACT
import idp.federation.Acs
import idp.federation.SP_CERTIFICATE
import idp.federation.application.NameIdFormat
import idp.federation.domain.AcsUrlNotAllowed
import idp.federation.domain.MetadataNotValid
import idp.federation.domain.MetadataTooLarge
import idp.federation.spMetadata
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import java.nio.file.Files
import java.util.Base64

private val importer = MetadataImporter()

private fun read(xml: String) = importer.read(xml.toByteArray())

private val EMAIL_FORMAT = NameIdFormat.EMAIL.uri
private const val TRANSIENT_FORMAT = "urn:oasis:names:tc:SAML:2.0:nameid-format:transient"

class MetadataImporterTest :
    FunSpec({
        context("a valid file") {
            test("gives the EntityID, the ACS and the signing certificate") {
                val sp = read(spMetadata(authnRequestsSigned = true))

                sp.entityId shouldBe "https://sp.example/saml"
                sp.acs.map { it.url.value }.shouldContainExactly("https://sp.example/saml/acs")
                sp.signingCertificates.map { Base64.getEncoder().encodeToString(it) }.shouldContainExactly(SP_CERTIFICATE)
                sp.authnRequestsSigned shouldBe true
            }

            test("keeps a certificate without a use and drops one for encryption only") {
                read(spMetadata(keyUse = null)).signingCertificates shouldHaveSize 1
                read(spMetadata(keyUse = "encryption")).signingCertificates.shouldBeEmpty()
            }

            test("does not need signed requests or a certificate") {
                val sp = read(spMetadata(certificate = null))

                sp.authnRequestsSigned shouldBe false
                sp.signingCertificates.shouldBeEmpty()
            }

            test("may list NameID formats, which pick nothing") {
                read(spMetadata(nameIdFormats = listOf(TRANSIENT_FORMAT, EMAIL_FORMAT))).entityId shouldBe "https://sp.example/saml"
            }

            test("keeps only HTTP-POST endpoints") {
                val sp =
                    read(
                        spMetadata(
                            acs = listOf(Acs("https://sp.example/artifact", 0, binding = ARTIFACT), Acs("https://sp.example/post", 1)),
                        ),
                    )

                sp.acs.map { it.index }.shouldContainExactly(1)
            }
        }

        context("the default ACS") {
            fun defaultOf(vararg acs: Acs) = read(spMetadata(acs = acs.toList())).acs.single { it.isDefault }.index

            test("is the first one marked") {
                defaultOf(
                    Acs("https://sp.example/a", 0),
                    Acs("https://sp.example/b", 1, true),
                    Acs("https://sp.example/c", 2, true),
                ) shouldBe
                    1
            }

            test("is the first one not unmarked without a mark") {
                defaultOf(Acs("https://sp.example/a", 0, false), Acs("https://sp.example/b", 1), Acs("https://sp.example/c", 2)) shouldBe 1
            }

            test("is the first one when all are unmarked") {
                defaultOf(Acs("https://sp.example/a", 3, false), Acs("https://sp.example/b", 1, false)) shouldBe 3
            }
        }

        context("an ACS URL") {
            listOf(
                "http://sp.example/acs",
                "javascript:alert(document.cookie)",
                "data:text/html;base64,PHNjcmlwdD5hbGVydCgxKTwvc2NyaXB0Pg==",
            ).forEach { location ->
                test("refuses $location") {
                    shouldThrow<AcsUrlNotAllowed> { read(spMetadata(acs = listOf(Acs(location)))) }
                }
            }

            test("refuses the file when any POST endpoint is not https") {
                shouldThrow<AcsUrlNotAllowed> {
                    read(spMetadata(acs = listOf(Acs("https://sp.example/acs", 0), Acs("http://sp.example/acs", 1))))
                }
            }
        }

        context("a hostile file") {
            test("with an external entity is refused before the entity is read") {
                val secret = Files.createTempFile("secret", ".txt").also { Files.writeString(it, "TOP-SECRET") }
                val xml =
                    """
                    <?xml version="1.0"?>
                    <!DOCTYPE md:EntityDescriptor [<!ENTITY xxe SYSTEM "${secret.toUri()}">]>
                    ${spMetadata(entityId = "&xxe;").substringAfter("?>")}
                    """.trimIndent()

                val error = shouldThrow<MetadataNotValid> { read(xml) }

                error.message shouldNotContain "TOP-SECRET"
                generateSequence<Throwable>(error) { it.cause }.forEach { (it.message ?: "") shouldNotContain "TOP-SECRET" }
            }

            test("with an external DTD is refused") {
                val xml = """<?xml version="1.0"?><!DOCTYPE x SYSTEM "http://127.0.0.1:1/evil.dtd">""" + spMetadata().substringAfter("?>")

                shouldThrow<MetadataNotValid> { read(xml) }
            }

            test("with nested entities is refused") {
                val entities = (1..9).joinToString("") { level -> """<!ENTITY e$level "&e${level - 1};&e${level - 1};&e${level - 1};">""" }
                val xml =
                    """<?xml version="1.0"?><!DOCTYPE x [<!ENTITY e0 "lol">$entities]>""" +
                        spMetadata(entityId = "&e9;").substringAfter("?>")

                shouldThrow<MetadataNotValid> { read(xml) }
            }

            test("with XInclude does not pull in another file") {
                val xml =
                    spMetadata().replace(
                        "<md:SPSSODescriptor",
                        """<xi:include xmlns:xi="http://www.w3.org/2001/XInclude" href="file:///etc/passwd" parse="text"/><md:SPSSODescriptor""",
                    )

                shouldThrow<MetadataNotValid> { read(xml) }
            }
        }

        context("a file of another schema") {
            test("in a foreign namespace is refused") {
                shouldThrow<MetadataNotValid> { read("""<html xmlns="http://www.w3.org/1999/xhtml"><body/></html>""") }
            }

            test("without a namespace is refused") {
                shouldThrow<MetadataNotValid> { read("""<EntityDescriptor entityID="https://sp.example"/>""") }
            }

            test("that is a SAML message and not metadata is refused") {
                val response =
                    """
                    <samlp:Response xmlns:samlp="urn:oasis:names:tc:SAML:2.0:protocol" ID="_1" Version="2.0"
                        IssueInstant="2026-01-01T00:00:00Z">
                      <samlp:Status><samlp:StatusCode Value="urn:oasis:names:tc:SAML:2.0:status:Success"/></samlp:Status>
                    </samlp:Response>
                    """.trimIndent()

                shouldThrow<MetadataNotValid> { read(response) }
            }

            test("that breaks the metadata schema is refused") {
                shouldThrow<MetadataNotValid> { read(spMetadata().replace("<md:SPSSODescriptor", "<md:Unknown/><md:SPSSODescriptor")) }
            }

            test("with an aggregate of entities is refused") {
                val aggregate =
                    """<md:EntitiesDescriptor xmlns:md="urn:oasis:names:tc:SAML:2.0:metadata">""" +
                        spMetadata().substringAfter("?>") +
                        "</md:EntitiesDescriptor>"

                shouldThrow<MetadataNotValid> { read(aggregate) }
            }

            test("of an IdP is refused") {
                val idp =
                    spMetadata()
                        .replace("SPSSODescriptor", "IDPSSODescriptor")
                        .replace(
                            Regex("<md:AssertionConsumerService[^>]*/>"),
                            """<md:SingleSignOnService Binding="$ARTIFACT" Location="https://x"/>""",
                        )

                shouldThrow<MetadataNotValid> { read(idp) }
            }

            test("that is not XML is refused") {
                shouldThrow<MetadataNotValid> { read("{\"entityID\": \"https://sp.example\"}") }
                shouldThrow<MetadataNotValid> { importer.read(ByteArray(0)) }
            }
        }

        context("an incomplete file") {
            test("without an HTTP-POST endpoint is refused") {
                shouldThrow<MetadataNotValid> { read(spMetadata(acs = listOf(Acs("https://sp.example/acs", binding = ARTIFACT)))) }
            }

            test("with two endpoints of one index is refused") {
                shouldThrow<MetadataNotValid> {
                    read(spMetadata(acs = listOf(Acs("https://sp.example/a", 1), Acs("https://sp.example/b", 1))))
                }
            }

            test("that wants signed requests without a certificate is refused") {
                shouldThrow<MetadataNotValid> { read(spMetadata(authnRequestsSigned = true, certificate = null)) }
            }

            test("with a broken certificate is refused") {
                shouldThrow<MetadataNotValid> { read(spMetadata(certificate = Base64.getEncoder().encodeToString(ByteArray(64)))) }
            }
        }

        context("the size") {
            test("over the limit is refused before parsing") {
                val small = MetadataImporter(maxBytes = 100)

                shouldThrow<MetadataTooLarge> { small.read(spMetadata().toByteArray()) }
            }

            test("of a real SP file fits the default limit") {
                read(spMetadata()).entityId shouldBe "https://sp.example/saml"
            }

            test("one byte over the default limit is refused") {
                shouldThrow<MetadataTooLarge> { importer.read(ByteArray(MAX_METADATA_BYTES + 1)) }
            }
        }
    })
