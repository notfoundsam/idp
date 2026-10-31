package idp.sharedkernel.crypto.adapter.aws

import idp.sharedkernel.crypto.keyManagementServiceContract
import idp.sharedkernel.testing.TestLocalStack
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.date.shouldBeBetween
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.kms.KmsClient
import software.amazon.awssdk.services.kms.model.KeyState
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.util.UUID

private const val REGION = "eu-west-1"
private const val KEY_POLICY =
    """{"Version":"2012-10-17","Statement":[{"Sid":"Root","Effect":"Allow","Principal":{"AWS":"arn:aws:iam::000000000000:root"},"Action":"kms:*","Resource":"*"}]}"""

private val CREDENTIALS = StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"))

private fun aws(endpoint: URI = TestLocalStack.endpoint) = AwsKeyManagementService.create(REGION, KEY_POLICY, endpoint, CREDENTIALS)

private fun admin() =
    KmsClient
        .builder()
        .region(Region.of(REGION))
        .endpointOverride(TestLocalStack.endpoint)
        .credentialsProvider(CREDENTIALS)
        .build()

class AwsKeyManagementServiceLocalStackTest :
    FunSpec({
        include(
            keyManagementServiceContract(
                kms = ::aws,
                unknownKey = { "aws-kms://arn:aws:kms:$REGION:000000000000:key/${UUID.randomUUID()}" },
                unreachable = { aws(URI("http://127.0.0.1:1")) },
            ),
        )

        test("a key URI holds the key ARN") {
            aws().createKey("tenant-${UUID.randomUUID()}") shouldStartWith "aws-kms://arn:aws:kms:$REGION:"
        }

        test("a new key carries the configured key policy") {
            val key = aws().createKey("tenant-${UUID.randomUUID()}")

            val policy = admin().getKeyPolicy { it.keyId(key.removePrefix("aws-kms://")).policyName("default") }.policy()

            policy shouldBe KEY_POLICY
        }

        test("a destroyed key waits for the shortest deletion window") {
            val key = aws().createKey("tenant-${UUID.randomUUID()}")

            aws().destroy(key)

            val metadata = admin().describeKey { it.keyId(key.removePrefix("aws-kms://")) }.keyMetadata()
            metadata.keyState() shouldBe KeyState.PENDING_DELETION
            metadata.deletionDate().shouldBeBetween(
                Instant.now().plus(Duration.ofDays(6)),
                Instant.now().plus(Duration.ofDays(8)),
            )
        }

        test("a URI of another scheme is rejected") {
            shouldThrow<IllegalArgumentException> { aws().wrap("hcvault://transit/key", ByteArray(1)) }
        }
    })
