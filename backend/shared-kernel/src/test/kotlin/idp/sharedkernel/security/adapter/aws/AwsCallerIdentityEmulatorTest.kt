package idp.sharedkernel.security.adapter.aws

import idp.sharedkernel.testing.TestAwsEmulator
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldStartWith
import software.amazon.awssdk.core.exception.SdkException
import java.net.URI

private const val REGION = "eu-west-1"

class AwsCallerIdentityEmulatorTest :
    FunSpec({
        test("names the caller by the ARN that STS proves") {
            AwsCallerIdentity.create(REGION, TestAwsEmulator.endpoint, TestAwsEmulator.credentials).name() shouldStartWith "arn:aws:"
        }

        test("an unreachable STS gives no name") {
            shouldThrow<SdkException> {
                AwsCallerIdentity.create(REGION, URI("http://127.0.0.1:1"), TestAwsEmulator.credentials).name()
            }
        }
    })
