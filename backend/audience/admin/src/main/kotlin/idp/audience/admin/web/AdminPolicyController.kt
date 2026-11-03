package idp.audience.admin.web

import idp.audience.admin.application.command.SavePolicy
import idp.audience.admin.application.query.GetPolicy
import idp.authentication.application.PolicyChange
import idp.sharedkernel.bus.CommandBus
import idp.sharedkernel.bus.QueryBus
import idp.sharedkernel.persistence.VersionConflictException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.Duration

private val ETAG = Regex("\"(\\d{1,18})\"")

/** MFA for `TENANT_ADMIN` and an expiry of the password have no field, so a body that names one is refused. */
data class PolicyRequest(
    val mfaForUsers: Boolean,
    val passkeyRequired: Boolean,
    val sessionIdleSeconds: Long,
    val sessionAbsoluteSeconds: Long,
)

data class PolicyResponse(
    val mfaForUsers: Boolean,
    val passkeyRequired: Boolean,
    val sessionIdleSeconds: Long,
    val sessionAbsoluteSeconds: Long,
)

private fun etag(version: Long) = "\"$version\""

/** A save without the version it read could undo a change of another admin unseen, so it is refused rather than let through. */
private fun versionOf(ifMatch: String?): Long {
    ifMatch ?: throw ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED)
    val version = ETAG.matchEntire(ifMatch.trim())?.groupValues?.get(1)
    return version?.toLong() ?: throw ResponseStatusException(HttpStatus.PRECONDITION_FAILED)
}

/** The policy is read and replaced as a whole, so its version travels as the `ETag` and comes back in `If-Match`. */
@RestController
@RequestMapping("/api/admin/policy")
class AdminPolicyController(
    private val commands: CommandBus,
    private val queries: QueryBus,
) {
    @GetMapping
    fun policy(): ResponseEntity<PolicyResponse> {
        val policy = queries.ask(GetPolicy())
        val response =
            PolicyResponse(
                policy.mfaForUsers,
                policy.passkeyRequired,
                policy.sessionIdleTimeout.seconds,
                policy.sessionAbsoluteTimeout.seconds,
            )
        return ResponseEntity.ok().eTag(etag(policy.version)).body(response)
    }

    @PutMapping
    fun save(
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
        @RequestBody request: PolicyRequest,
    ): ResponseEntity<Void> {
        val version = versionOf(ifMatch)
        val change =
            PolicyChange(
                request.mfaForUsers,
                request.passkeyRequired,
                Duration.ofSeconds(request.sessionIdleSeconds),
                Duration.ofSeconds(request.sessionAbsoluteSeconds),
            )
        val saved =
            try {
                commands.dispatch(SavePolicy(change, version))
            } catch (_: VersionConflictException) {
                throw ResponseStatusException(HttpStatus.PRECONDITION_FAILED)
            }
        return ResponseEntity.noContent().eTag(etag(saved)).build()
    }
}
