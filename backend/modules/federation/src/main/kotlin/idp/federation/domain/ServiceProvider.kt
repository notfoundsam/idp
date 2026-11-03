package idp.federation.domain

import idp.federation.application.SpSettings
import idp.sharedkernel.domain.DomainRuleViolation
import java.net.URI
import java.net.URISyntaxException
import java.util.UUID

private const val MAX_NAME_LENGTH = 200
private const val MAX_URL_LENGTH = 2048
private const val HTTPS = "https"

class AcsUrlNotAllowed : DomainRuleViolation("An ACS URL must be an absolute https URL")

class InvalidServiceProviderName : DomainRuleViolation("A Service Provider needs a name of up to $MAX_NAME_LENGTH characters")

/** Says nothing of the content: the parser's message may quote it. */
class MetadataNotValid(
    cause: Throwable? = null,
) : DomainRuleViolation("The file is not valid SAML metadata of one Service Provider") {
    init {
        cause?.let(::initCause)
    }
}

class MetadataTooLarge(
    limit: Int,
) : DomainRuleViolation("SAML metadata may not exceed $limit bytes")

/** The browser carries the Response to this address, so anything but https would hand the assertion to a script or the network. */
internal class AcsUrl private constructor(
    val value: String,
) {
    override fun toString(): String = value

    companion object {
        fun of(raw: String): AcsUrl {
            if (raw.length > MAX_URL_LENGTH) throw AcsUrlNotAllowed()
            val uri =
                try {
                    URI(raw)
                } catch (_: URISyntaxException) {
                    throw AcsUrlNotAllowed()
                }
            val allowed =
                uri.scheme.equals(HTTPS, ignoreCase = true) &&
                    !uri.host.isNullOrEmpty() &&
                    uri.rawUserInfo == null &&
                    uri.rawFragment == null
            if (!allowed) throw AcsUrlNotAllowed()
            return AcsUrl(raw)
        }
    }
}

internal data class AcsEndpoint(
    val index: Int,
    val url: AcsUrl,
    val isDefault: Boolean,
)

/** What a metadata file says about an SP. Its NameID formats are ignored, see [SpSettings]. */
internal class SpDescription(
    val entityId: String,
    val acs: List<AcsEndpoint>,
    val signingCertificates: List<ByteArray>,
    val authnRequestsSigned: Boolean,
)

internal class ServiceProvider(
    val id: UUID,
    val entityId: String,
    name: String,
    val acs: List<AcsEndpoint>,
    val signingCertificates: List<ByteArray>,
    val authnRequestsSigned: Boolean,
    settings: SpSettings,
    val version: Long,
) {
    val name: String = validName(name)
    var settings: SpSettings = settings
        private set

    fun changeSettings(settings: SpSettings) {
        this.settings = settings
    }

    companion object {
        fun registered(
            id: UUID,
            name: String,
            description: SpDescription,
        ) = ServiceProvider(
            id,
            description.entityId,
            name,
            description.acs,
            description.signingCertificates,
            description.authnRequestsSigned,
            SpSettings(),
            version = 0,
        )

        private fun validName(name: String): String {
            val trimmed = name.trim()
            if (trimmed.isEmpty() || trimmed.length > MAX_NAME_LENGTH) throw InvalidServiceProviderName()
            return trimmed
        }
    }
}
