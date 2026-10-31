package idp.sharedkernel.audit

import java.util.UUID

sealed interface Actor {
    data class User(
        val id: UUID,
    ) : Actor

    data class Operator(
        val name: String,
    ) : Actor

    data object System : Actor

    /** Nobody has proven who they are, as in a failed login: the user named there is the target, not the actor. */
    data object Anonymous : Actor
}
