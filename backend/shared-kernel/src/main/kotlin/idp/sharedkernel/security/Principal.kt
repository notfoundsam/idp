package idp.sharedkernel.security

import idp.sharedkernel.audit.Actor
import java.time.Instant

data class Role(
    val name: String,
)

/** [authenticatedAt] is empty for a caller who did not log in here, and such a caller never counts as freshly authenticated. */
data class Principal(
    val actor: Actor,
    val role: Role,
    val authenticatedAt: Instant? = null,
)
