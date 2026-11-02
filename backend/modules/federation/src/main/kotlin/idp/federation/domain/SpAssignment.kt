package idp.federation.domain

import java.time.Instant
import java.util.UUID

/**
 * An aggregate of its own, apart from `ServiceProvider`: each assignment would otherwise bump the version of the SP, and two admins
 * assigning people at once would conflict.
 */
internal data class SpAssignment(
    val spId: UUID,
    val userId: UUID,
    val assignedAt: Instant,
)
