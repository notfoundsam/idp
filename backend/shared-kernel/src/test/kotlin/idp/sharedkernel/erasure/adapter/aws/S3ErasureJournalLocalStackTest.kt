package idp.sharedkernel.erasure.adapter.aws

import idp.sharedkernel.erasure.Erasure
import idp.sharedkernel.erasure.erasureJournalContract
import idp.sharedkernel.testing.TestLocalStack
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.BucketLocationConstraint
import software.amazon.awssdk.services.s3.model.ObjectLockRetentionMode
import java.net.URI
import java.util.UUID

private const val REGION = "eu-west-1"

private val CREDENTIALS = StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"))

private fun s3(endpoint: URI = TestLocalStack.endpoint) =
    S3Client
        .builder()
        .region(Region.of(REGION))
        .endpointOverride(endpoint)
        .forcePathStyle(true)
        .credentialsProvider(CREDENTIALS)
        .build()

/** As production sets it up: Object Lock with a default retention, which also makes every put carry a checksum. */
private fun bucket(): String {
    val name = "erasures-${UUID.randomUUID()}"
    s3().run {
        createBucket {
            it
                .bucket(name)
                .objectLockEnabledForBucket(true)
                .createBucketConfiguration { config -> config.locationConstraint(BucketLocationConstraint.EU_WEST_1) }
        }
        putObjectLockConfiguration {
            it.bucket(name).objectLockConfiguration { config ->
                config.objectLockEnabled("Enabled").rule { rule ->
                    rule.defaultRetention { retention -> retention.mode(ObjectLockRetentionMode.COMPLIANCE).days(1) }
                }
            }
        }
    }
    return name
}

private fun journal(
    bucket: String,
    endpoint: URI = TestLocalStack.endpoint,
) = S3ErasureJournal.create(REGION, bucket, endpoint, CREDENTIALS)

class S3ErasureJournalLocalStackTest :
    FunSpec({
        include(
            erasureJournalContract(
                storage = ::bucket,
                open = { journal(it) },
                unreachable = { journal("erasures", URI("http://127.0.0.1:1")) },
            ),
        )

        test("an erasure is an empty object named by its IDs") {
            val bucket = bucket()
            val tenant = UUID.randomUUID()
            val user = UUID.randomUUID()

            journal(bucket).record(Erasure.User(tenant, user))

            val objects = s3().listObjectsV2 { it.bucket(bucket) }.contents()
            objects.map { it.key() }.shouldContainExactly("users/$tenant/$user")
            objects.single().size() shouldBe 0
        }

        test("a stray object fails the read instead of passing for an erasure") {
            val bucket = bucket()
            s3().putObject({ it.bucket(bucket).key("notes.txt") }, RequestBody.fromString("hello"))

            shouldThrow<IllegalStateException> { journal(bucket).entries() }
        }

        test("Object Lock keeps an entry from being deleted") {
            val bucket = bucket()
            val user = Erasure.User(UUID.randomUUID(), UUID.randomUUID())
            journal(bucket).record(user)
            val version =
                s3()
                    .listObjectVersions { it.bucket(bucket) }
                    .versions()
                    .single()
                    .versionId()

            val key = "users/${user.tenantId}/${user.userId}"

            shouldThrow<Exception> { s3().deleteObject { it.bucket(bucket).key(key).versionId(version) } }

            journal(bucket).entries().shouldContainExactly(user)
        }
    })
