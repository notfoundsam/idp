package idp.sharedkernel.crypto.adapter.aws

import idp.sharedkernel.crypto.KeyManagementService
import idp.sharedkernel.crypto.KeyManagementUnavailableException
import idp.sharedkernel.crypto.KeyNotFoundException
import idp.sharedkernel.crypto.WrongKeyException
import idp.sharedkernel.crypto.requireKeyAlias
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.core.exception.SdkException
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.kms.KmsClient
import software.amazon.awssdk.services.kms.model.AlreadyExistsException
import software.amazon.awssdk.services.kms.model.IncorrectKeyException
import software.amazon.awssdk.services.kms.model.InvalidCiphertextException
import software.amazon.awssdk.services.kms.model.KmsInvalidStateException
import software.amazon.awssdk.services.kms.model.NotFoundException
import java.net.URI
import java.time.Duration

private const val URI_PREFIX = "aws-kms://"
private const val ALIAS_PREFIX = "alias/"
private const val DELETION_WINDOW_DAYS = 7
private val CALL_TIMEOUT: Duration = Duration.ofSeconds(10)
private val ATTEMPT_TIMEOUT: Duration = Duration.ofSeconds(3)

/** The policy comes with every `CreateKey`: tenant KEKs appear at runtime, so Terraform cannot attach it. */
class AwsKeyManagementService(
    private val kms: KmsClient,
    private val keyPolicy: String,
) : KeyManagementService {
    override fun createKey(alias: String): String =
        guarded(alias) {
            val name = ALIAS_PREFIX + requireKeyAlias(alias)
            URI_PREFIX + (findByAlias(name) ?: createWithAlias(name))
        }

    override fun wrap(
        kekUri: String,
        plaintext: ByteArray,
    ): ByteArray =
        guarded(kekUri) {
            kms
                .encrypt { it.keyId(arnOf(kekUri)).plaintext(SdkBytes.fromByteArray(plaintext)) }
                .ciphertextBlob()
                .asByteArray()
        }

    override fun unwrap(
        kekUri: String,
        wrapped: ByteArray,
    ): ByteArray =
        guarded(kekUri) {
            kms
                .decrypt { it.keyId(arnOf(kekUri)).ciphertextBlob(SdkBytes.fromByteArray(wrapped)) }
                .plaintext()
                .asByteArray()
        }

    override fun destroy(kekUri: String) {
        val arn = arnOf(kekUri)
        try {
            guarded(kekUri) {
                kms.listAliases { it.keyId(arn) }.aliases().forEach { alias ->
                    kms.deleteAlias { it.aliasName(alias.aliasName()) }
                }
                kms.scheduleKeyDeletion { it.keyId(arn).pendingWindowInDays(DELETION_WINDOW_DAYS) }
            }
        } catch (_: KeyNotFoundException) {
            return
        }
    }

    private fun findByAlias(name: String): String? =
        try {
            kms.describeKey { it.keyId(name) }.keyMetadata().arn()
        } catch (_: NotFoundException) {
            null
        }

    private fun createWithAlias(name: String): String {
        val arn = kms.createKey { it.policy(keyPolicy).description(name) }.keyMetadata().arn()
        return try {
            kms.createAlias { it.aliasName(name).targetKeyId(arn) }
            arn
        } catch (_: AlreadyExistsException) {
            kms.scheduleKeyDeletion { it.keyId(arn).pendingWindowInDays(DELETION_WINDOW_DAYS) }
            kms.describeKey { it.keyId(name) }.keyMetadata().arn()
        }
    }

    private fun arnOf(kekUri: String): String {
        require(kekUri.startsWith(URI_PREFIX)) { "Not an AWS KMS key: $kekUri" }
        return kekUri.removePrefix(URI_PREFIX)
    }

    private inline fun <T> guarded(
        key: String,
        call: () -> T,
    ): T =
        try {
            call()
        } catch (e: NotFoundException) {
            throw KeyNotFoundException(key, e)
        } catch (e: KmsInvalidStateException) {
            throw KeyNotFoundException(key, e)
        } catch (e: IncorrectKeyException) {
            throw WrongKeyException(key, e)
        } catch (e: InvalidCiphertextException) {
            throw WrongKeyException(key, e)
        } catch (e: SdkException) {
            throw KeyManagementUnavailableException("AWS KMS call failed", e)
        }

    companion object {
        /** Credentials come from the task role. The signature holds no SDK type, so code outside this package can call it. */
        fun create(
            region: String,
            keyPolicy: String,
        ): AwsKeyManagementService = create(region, keyPolicy, endpoint = null, credentials = null)

        /** The AWS SDK stays inside this package, so callers cannot build the client themselves. */
        fun create(
            region: String,
            keyPolicy: String,
            endpoint: URI?,
            credentials: AwsCredentialsProvider?,
        ): AwsKeyManagementService {
            val client =
                KmsClient
                    .builder()
                    .region(Region.of(region))
                    .overrideConfiguration { it.apiCallTimeout(CALL_TIMEOUT).apiCallAttemptTimeout(ATTEMPT_TIMEOUT) }
                    .apply {
                        endpoint?.let { endpointOverride(it) }
                        credentials?.let { credentialsProvider(it) }
                    }.build()
            return AwsKeyManagementService(client, keyPolicy)
        }
    }
}
