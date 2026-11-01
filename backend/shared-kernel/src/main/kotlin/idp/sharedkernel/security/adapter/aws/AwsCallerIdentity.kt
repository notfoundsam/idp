package idp.sharedkernel.security.adapter.aws

import idp.sharedkernel.security.CallerIdentity
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sts.StsClient
import java.net.URI
import java.time.Duration

private val CALL_TIMEOUT: Duration = Duration.ofSeconds(10)
private val ATTEMPT_TIMEOUT: Duration = Duration.ofSeconds(3)

class AwsCallerIdentity(
    private val sts: StsClient,
) : CallerIdentity {
    override fun name(): String = sts.getCallerIdentity().arn()

    companion object {
        /** Credentials come from the task role. The signature holds no SDK type, so code outside this package can call it. */
        fun create(region: String): AwsCallerIdentity = create(region, endpoint = null, credentials = null)

        fun create(
            region: String,
            endpoint: URI?,
            credentials: AwsCredentialsProvider?,
        ): AwsCallerIdentity {
            val client =
                StsClient
                    .builder()
                    .region(Region.of(region))
                    .overrideConfiguration { it.apiCallTimeout(CALL_TIMEOUT).apiCallAttemptTimeout(ATTEMPT_TIMEOUT) }
                    .apply {
                        endpoint?.let { endpointOverride(it) }
                        credentials?.let { credentialsProvider(it) }
                    }.build()
            return AwsCallerIdentity(client)
        }
    }
}
