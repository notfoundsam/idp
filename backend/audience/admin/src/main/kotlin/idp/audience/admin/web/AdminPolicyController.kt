package idp.audience.admin.web

import idp.audience.admin.application.command.SavePolicy
import idp.audience.admin.application.query.GetPolicy
import idp.authentication.application.PolicyChange
import idp.sharedkernel.bus.CommandBus
import idp.sharedkernel.bus.QueryBus
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Duration

/** Read and saved alike. MFA for `TENANT_ADMIN` and an expiry of the password have no field, so a body that names one is refused. */
data class PolicyBody(
    val mfaForUsers: Boolean,
    val passkeyRequired: Boolean,
    val sessionIdleSeconds: Long,
    val sessionAbsoluteSeconds: Long,
)

/** The policy is read and replaced as a whole, so its version travels as the `ETag` and comes back in `If-Match`. */
@RestController
@RequestMapping("/api/admin/policy")
class AdminPolicyController(
    private val commands: CommandBus,
    private val queries: QueryBus,
) {
    @GetMapping
    fun policy(): ResponseEntity<PolicyBody> {
        val policy = queries.ask(GetPolicy())
        val body =
            PolicyBody(
                policy.mfaForUsers,
                policy.passkeyRequired,
                policy.sessionIdleTimeout.seconds,
                policy.sessionAbsoluteTimeout.seconds,
            )
        return ResponseEntity.ok().eTag(etag(policy.version)).body(body)
    }

    @PutMapping
    fun save(
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
        @RequestBody request: PolicyBody,
    ): ResponseEntity<Void> {
        val version = versionOf(ifMatch)
        val change =
            PolicyChange(
                request.mfaForUsers,
                request.passkeyRequired,
                Duration.ofSeconds(request.sessionIdleSeconds),
                Duration.ofSeconds(request.sessionAbsoluteSeconds),
            )
        return ResponseEntity.noContent().eTag(etag(commands.dispatch(SavePolicy(change, version)))).build()
    }
}
