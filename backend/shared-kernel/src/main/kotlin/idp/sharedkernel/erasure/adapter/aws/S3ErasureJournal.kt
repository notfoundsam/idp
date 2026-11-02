package idp.sharedkernel.erasure.adapter.aws

import idp.sharedkernel.erasure.Erasure
import idp.sharedkernel.erasure.ErasureJournal
import idp.sharedkernel.erasure.ErasureJournalUnavailableException
import idp.sharedkernel.erasure.erasureOf
import idp.sharedkernel.erasure.key
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.core.exception.SdkException
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import java.net.URI
import java.time.Duration

private val CALL_TIMEOUT: Duration = Duration.ofSeconds(30)
private val ATTEMPT_TIMEOUT: Duration = Duration.ofSeconds(10)

/**
 * One empty object an erasure, named by its IDs, so a repeated record writes the same name. Object Lock on the bucket
 * keeps every object for its retention, and the task role may put and list but never delete.
 */
class S3ErasureJournal(
    private val s3: S3Client,
    private val bucket: String,
) : ErasureJournal {
    override fun record(erasure: Erasure) {
        guarded { s3.putObject({ it.bucket(bucket).key(erasure.key()) }, RequestBody.empty()) }
    }

    override fun entries(): Set<Erasure> =
        guarded {
            s3
                .listObjectsV2Paginator { it.bucket(bucket) }
                .contents()
                .map { erasureOf(it.key()) }
                .toSet()
        }

    private inline fun <T> guarded(call: () -> T): T =
        try {
            call()
        } catch (e: SdkException) {
            throw ErasureJournalUnavailableException("S3 call to the erasure journal failed", e)
        }

    companion object {
        /** Credentials come from the task role. The signature holds no SDK type, so code outside this package can call it. */
        fun create(
            region: String,
            bucket: String,
        ): S3ErasureJournal = create(region, bucket, endpoint = null, credentials = null)

        /** LocalStack serves buckets by path, not by host name. */
        fun create(
            region: String,
            bucket: String,
            endpoint: URI?,
            credentials: AwsCredentialsProvider?,
        ): S3ErasureJournal {
            val client =
                S3Client
                    .builder()
                    .region(Region.of(region))
                    .overrideConfiguration { it.apiCallTimeout(CALL_TIMEOUT).apiCallAttemptTimeout(ATTEMPT_TIMEOUT) }
                    .apply {
                        endpoint?.let {
                            endpointOverride(it)
                            forcePathStyle(true)
                        }
                        credentials?.let { credentialsProvider(it) }
                    }.build()
            return S3ErasureJournal(client, bucket)
        }
    }
}
