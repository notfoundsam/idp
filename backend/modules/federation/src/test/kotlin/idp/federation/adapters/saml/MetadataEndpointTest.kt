package idp.federation.adapters.saml

import idp.federation.Federation
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType

class MetadataEndpointTest :
    FunSpec({
        test("serves the metadata as a file of a fixed name with its charset") {
            Federation().run {
                val response = tenant().acting { MetadataEndpoint(this).metadata() }

                response.statusCode shouldBe HttpStatus.OK
                response.headers.contentType shouldBe MediaType.parseMediaType("application/samlmetadata+xml;charset=UTF-8")
                response.headers.getFirst(HttpHeaders.CONTENT_DISPOSITION) shouldBe "attachment; filename=\"idp-metadata.xml\""
                parseEntity(response.body.shouldNotBeNull().decodeToString()).entityID shouldBe "https://acme.idp.test/saml/metadata"
            }
        }

        test("publishes the signing certificate in a schema-valid document") {
            Federation().run {
                val body = tenant().acting { MetadataEndpoint(this).metadata() }.body.shouldNotBeNull()

                signingCertificatesOf(parseEntity(body.decodeToString())) shouldHaveSize 1
            }
        }

        test("answers 404 for a tenant without an identity") {
            Federation().run {
                val response = tenant(host = null).acting { MetadataEndpoint(this).metadata() }

                response.statusCode shouldBe HttpStatus.NOT_FOUND
                response.body.shouldBeNull()
            }
        }
    })
