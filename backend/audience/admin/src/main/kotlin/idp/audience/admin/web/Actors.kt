package idp.audience.admin.web

import idp.sharedkernel.audit.Actor
import java.util.UUID

enum class ActorType {
    USER,
    SYSTEM,
    ANONYMOUS,
}

/** An operator acts in `platform.operator_audit`, never in the journal of a tenant. */
internal fun Actor.typeAndId(): Pair<ActorType, UUID?> =
    when (this) {
        is Actor.User -> ActorType.USER to id
        Actor.System -> ActorType.SYSTEM to null
        Actor.Anonymous -> ActorType.ANONYMOUS to null
        is Actor.Operator -> error("An operator in the journal of a tenant")
    }
