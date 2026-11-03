package idp.federation.adapters.saml

import idp.federation.application.FederationService
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/** Registered in SAML metadata 4.1. The charset is stated, so no client guesses it. */
val METADATA_TYPE: MediaType = MediaType.parseMediaType("application/samlmetadata+xml;charset=UTF-8")

/** The name is fixed, so nothing in a request reaches the header. */
const val METADATA_FILE_NAME = "idp-metadata.xml"

@RestController
class MetadataEndpoint(
    private val federation: FederationService,
) {
    @GetMapping("/saml/metadata")
    fun metadata(): ResponseEntity<ByteArray> {
        val xml = federation.metadata() ?: return ResponseEntity.notFound().build()
        return ResponseEntity
            .ok()
            .contentType(METADATA_TYPE)
            .header(
                HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition
                    .attachment()
                    .filename(METADATA_FILE_NAME)
                    .build()
                    .toString(),
            ).body(xml.toByteArray(Charsets.UTF_8))
    }
}
