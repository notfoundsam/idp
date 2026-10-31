package idp.sharedkernel.audit

import java.util.UUID

data class AuditTarget(
    val type: String,
    val id: UUID,
) {
    companion object {
        const val USER = "user"
        const val TENANT = "tenant"

        fun user(id: UUID) = AuditTarget(USER, id)

        fun tenant(id: UUID) = AuditTarget(TENANT, id)
    }
}

/** `ip` and `details` outlive the target, so `details` must not carry personal data of anyone but the actor. */
data class AuditRecord(
    val actor: Actor,
    val action: String,
    val target: AuditTarget? = null,
    val ip: String? = null,
    val details: Map<String, String> = emptyMap(),
)
