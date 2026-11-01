package idp.sharedkernel.security.adapter.aws

import idp.sharedkernel.testing.TestLocalStack
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldStartWith
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.exception.SdkException
import java.net.URI

private const val REGION = "eu-west-1"
private val CREDENTIALS = StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"))

class AwsCallerIdentityLocalStackTest :
    FunSpec({
        test("names the caller by the ARN that STS proves") {
            AwsCallerIdentity.create(REGION, TestLocalStack.endpoint, CREDENTIALS).name() shouldStartWith "arn:aws:"
        }

        test("an unreachable STS gives no name") {
            shouldThrow<SdkException> { AwsCallerIdentity.create(REGION, URI("http://127.0.0.1:1"), CREDENTIALS).name() }
        }
    })
