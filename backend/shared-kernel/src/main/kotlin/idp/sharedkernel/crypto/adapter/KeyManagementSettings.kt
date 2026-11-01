package idp.sharedkernel.crypto.adapter

import idp.sharedkernel.crypto.KeyManagementRouter
import idp.sharedkernel.crypto.KeyManagementService
import idp.sharedkernel.crypto.adapter.aws.AwsKeyManagementService
import java.net.URI

private const val OPENBAO = "hcvault"
private const val AWS = "aws-kms"

/** Not a data class: `toString` must not print the token. An adapter with blank settings stays out of the deployment. */
class KeyManagementSettings(
    private val newKeys: String,
    private val openBaoAddress: String,
    private val openBaoToken: String,
    private val awsRegion: String,
    private val awsKeyPolicy: String,
) {
    fun service(): KeyManagementService {
        val adapters =
            buildMap {
                if (openBaoAddress.isNotBlank()) put(OPENBAO, OpenBaoKeyManagementService(URI(openBaoAddress), openBaoToken))
                if (awsRegion.isNotBlank()) put(AWS, AwsKeyManagementService.create(awsRegion, awsKeyPolicy))
            }
        val target = requireNotNull(adapters[newKeys]) { "New keys go to '$newKeys', and no such adapter is configured" }
        return KeyManagementRouter(target, adapters)
    }
}
