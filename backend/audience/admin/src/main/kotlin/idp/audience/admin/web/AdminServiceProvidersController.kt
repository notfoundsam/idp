package idp.audience.admin.web

import idp.audience.admin.application.command.AssignUsers
import idp.audience.admin.application.command.ChangeSpSettings
import idp.audience.admin.application.command.CompleteKeyRotation
import idp.audience.admin.application.command.RegisterServiceProvider
import idp.audience.admin.application.command.RemoveServiceProvider
import idp.audience.admin.application.command.StartKeyRotation
import idp.audience.admin.application.command.UnassignUsers
import idp.audience.admin.application.query.GetAssignedUsers
import idp.audience.admin.application.query.GetServiceProvider
import idp.audience.admin.application.query.ListServiceProviders
import idp.audience.admin.application.query.ListSigningKeys
import idp.federation.application.NameIdFormat
import idp.federation.application.SamlAttribute
import idp.federation.application.ServiceProviderView
import idp.federation.application.SignatureAlgorithm
import idp.federation.application.SignedPart
import idp.federation.application.SpSettings
import idp.sharedkernel.bus.CommandBus
import idp.sharedkernel.bus.QueryBus
import idp.sharedkernel.security.UserRole
import idp.sharedkernel.security.UserStatus
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.server.ResponseStatusException
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

/** About 20 KiB of IDs, below the limit on a body. */
private const val MAX_USERS_PER_CALL = 500

enum class SpNameIdFormat {
    PERSISTENT,
    EMAIL,
}

enum class SpSignedPart {
    ASSERTION,
    RESPONSE,
    BOTH,
}

enum class SpSignatureAlgorithm {
    RSA_PSS_SHA256,
    RSA_SHA256,
}

enum class SpAttribute {
    EMAIL,
    DISPLAY_NAME,
    PHONE,
}

enum class KeyStatus {
    ACTIVE,
    NEXT,
}

/** The elements may be null as far as JSON goes, so they are checked rather than trusted. */
data class SpSettingsRequest(
    val nameIdFormat: SpNameIdFormat,
    val signedPart: SpSignedPart,
    val signatureAlgorithm: SpSignatureAlgorithm,
    val attributes: List<SpAttribute?>,
)

data class SpSettingsResponse(
    val nameIdFormat: SpNameIdFormat,
    val signedPart: SpSignedPart,
    val signatureAlgorithm: SpSignatureAlgorithm,
    val attributes: List<SpAttribute>,
)

data class UserListRequest(
    val userIds: List<UUID?>,
)

data class CreatedResponse(
    val id: UUID,
)

data class ServiceProviderSummaryResponse(
    val id: UUID,
    val entityId: String,
    val name: String,
)

data class ServiceProviderListResponse(
    val serviceProviders: List<ServiceProviderSummaryResponse>,
)

data class AcsResponse(
    val index: Int,
    val url: String,
    val isDefault: Boolean,
)

data class CertificateResponse(
    val sha256Fingerprint: String,
    val notAfter: Instant,
)

data class ServiceProviderResponse(
    val id: UUID,
    val entityId: String,
    val name: String,
    val acs: List<AcsResponse>,
    val authnRequestsSigned: Boolean,
    val signingCertificates: List<CertificateResponse>,
    val settings: SpSettingsResponse,
)

data class AssignedUserResponse(
    val id: UUID,
    val role: UserRole,
    val status: UserStatus,
)

data class AssignedUsersResponse(
    val users: List<AssignedUserResponse>,
)

data class SigningKeyResponse(
    val id: UUID,
    val status: KeyStatus,
    val sha256Fingerprint: String,
    val notAfter: Instant,
    val createdAt: Instant,
)

data class SigningKeysResponse(
    val keys: List<SigningKeyResponse>,
)

private val FINGERPRINT = HexFormat.ofDelimiter(":").withUpperCase()

private fun X509Certificate.response() =
    CertificateResponse(FINGERPRINT.formatHex(MessageDigest.getInstance("SHA-256").digest(encoded)), notAfter.toInstant())

private fun NameIdFormat.response() =
    when (this) {
        NameIdFormat.PERSISTENT -> SpNameIdFormat.PERSISTENT
        NameIdFormat.EMAIL -> SpNameIdFormat.EMAIL
    }

private fun SpNameIdFormat.format() =
    when (this) {
        SpNameIdFormat.PERSISTENT -> NameIdFormat.PERSISTENT
        SpNameIdFormat.EMAIL -> NameIdFormat.EMAIL
    }

private fun SignedPart.response() =
    when (this) {
        SignedPart.ASSERTION -> SpSignedPart.ASSERTION
        SignedPart.RESPONSE -> SpSignedPart.RESPONSE
        SignedPart.BOTH -> SpSignedPart.BOTH
    }

private fun SpSignedPart.part() =
    when (this) {
        SpSignedPart.ASSERTION -> SignedPart.ASSERTION
        SpSignedPart.RESPONSE -> SignedPart.RESPONSE
        SpSignedPart.BOTH -> SignedPart.BOTH
    }

private fun SignatureAlgorithm.response() =
    when (this) {
        SignatureAlgorithm.RSA_PSS_SHA256 -> SpSignatureAlgorithm.RSA_PSS_SHA256
        SignatureAlgorithm.RSA_SHA256 -> SpSignatureAlgorithm.RSA_SHA256
    }

private fun SpSignatureAlgorithm.algorithm() =
    when (this) {
        SpSignatureAlgorithm.RSA_PSS_SHA256 -> SignatureAlgorithm.RSA_PSS_SHA256
        SpSignatureAlgorithm.RSA_SHA256 -> SignatureAlgorithm.RSA_SHA256
    }

private fun SamlAttribute.response() =
    when (this) {
        SamlAttribute.EMAIL -> SpAttribute.EMAIL
        SamlAttribute.DISPLAY_NAME -> SpAttribute.DISPLAY_NAME
        SamlAttribute.PHONE -> SpAttribute.PHONE
    }

private fun SpAttribute.attribute() =
    when (this) {
        SpAttribute.EMAIL -> SamlAttribute.EMAIL
        SpAttribute.DISPLAY_NAME -> SamlAttribute.DISPLAY_NAME
        SpAttribute.PHONE -> SamlAttribute.PHONE
    }

private fun SpSettings.response() =
    SpSettingsResponse(
        nameIdFormat.response(),
        signedPart.response(),
        signatureAlgorithm.response(),
        attributes.map { it.response() }.sorted(),
    )

private fun SpSettingsRequest.settings() =
    SpSettings(
        nameIdFormat.format(),
        signedPart.part(),
        signatureAlgorithm.algorithm(),
        attributes.map { (it ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST)).attribute() }.toSet(),
    )

private fun ServiceProviderView.response() =
    ServiceProviderResponse(
        id,
        entityId,
        name,
        acs.map { AcsResponse(it.index, it.url, it.isDefault) },
        authnRequestsSigned,
        signingCertificates.map { it.response() },
        settings.response(),
    )

private fun UserListRequest.ids(): List<UUID> {
    if (userIds.isEmpty() || userIds.size > MAX_USERS_PER_CALL) throw ResponseStatusException(HttpStatus.BAD_REQUEST)
    return userIds.map { it ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST) }
}

@RestController
@RequestMapping("/api/admin")
class AdminServiceProvidersController(
    private val commands: CommandBus,
    private val queries: QueryBus,
) {
    @GetMapping("/service-providers")
    fun serviceProviders() =
        ServiceProviderListResponse(queries.ask(ListServiceProviders()).map { ServiceProviderSummaryResponse(it.id, it.entityId, it.name) })

    /** The metadata comes as a file of a form. Its file name is never read, so it reaches neither the SP nor the journal. */
    @PostMapping("/service-providers", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @ResponseStatus(HttpStatus.CREATED)
    fun register(
        @RequestParam("name") name: String,
        @RequestPart("metadata") metadata: MultipartFile,
    ) = CreatedResponse(commands.dispatch(RegisterServiceProvider(name, metadata.bytes)))

    /** The settings are read and replaced as a whole, so their version travels as the `ETag`. */
    @GetMapping("/service-providers/{id}")
    fun serviceProvider(
        @PathVariable id: UUID,
    ): ResponseEntity<ServiceProviderResponse> {
        val sp = queries.ask(GetServiceProvider(id))
        return ResponseEntity.ok().eTag(etag(sp.version)).body(sp.response())
    }

    @PutMapping("/service-providers/{id}/settings")
    fun changeSettings(
        @PathVariable id: UUID,
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
        @RequestBody request: SpSettingsRequest,
    ): ResponseEntity<Void> {
        val saved = commands.dispatch(ChangeSpSettings(id, request.settings(), versionOf(ifMatch)))
        return ResponseEntity.noContent().eTag(etag(saved)).build()
    }

    @DeleteMapping("/service-providers/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun remove(
        @PathVariable id: UUID,
    ) = commands.dispatch(RemoveServiceProvider(id))

    /** The email stays out of a list, so a user is named by ID and the card tells who they are. */
    @GetMapping("/service-providers/{id}/users")
    fun assignedUsers(
        @PathVariable id: UUID,
    ) = AssignedUsersResponse(queries.ask(GetAssignedUsers(id)).map { AssignedUserResponse(it.id, it.role, it.status) })

    @PostMapping("/service-providers/{id}/users")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun assign(
        @PathVariable id: UUID,
        @RequestBody request: UserListRequest,
    ) = commands.dispatch(AssignUsers(id, request.ids()))

    @PostMapping("/service-providers/{id}/users/removal")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun unassign(
        @PathVariable id: UUID,
        @RequestBody request: UserListRequest,
    ) = commands.dispatch(UnassignUsers(id, request.ids()))

    @GetMapping("/signing-keys")
    fun signingKeys() =
        SigningKeysResponse(
            queries.ask(ListSigningKeys()).map {
                val certificate = it.certificate.response()
                SigningKeyResponse(
                    it.id,
                    if (it.next) KeyStatus.NEXT else KeyStatus.ACTIVE,
                    certificate.sha256Fingerprint,
                    certificate.notAfter,
                    it.createdAt,
                )
            },
        )

    @PostMapping("/signing-keys/rotation")
    @ResponseStatus(HttpStatus.CREATED)
    fun startRotation() = CreatedResponse(commands.dispatch(StartKeyRotation()))

    @PostMapping("/signing-keys/rotation/completion")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun completeRotation() = commands.dispatch(CompleteKeyRotation())
}
