package idp.sharedkernel.security

import idp.sharedkernel.audit.Actor
import java.time.Instant
import java.util.UUID

data class Role(
    val name: String,
)

/**
 * [authenticatedAt] is empty for a caller who did not log in here, and such a caller never counts as freshly authenticated.
 * [sessionId] names the IdP session the request came with, so the account can tell it from the user's other sessions.
 */
data class Principal(
    val actor: Actor,
    val role: Role,
    val authenticatedAt: Instant? = null,
    val sessionId: UUID? = null,
)
