package idp.sharedkernel.security

import idp.sharedkernel.audit.Actor

data class Role(
    val name: String,
)

data class Principal(
    val actor: Actor,
    val role: Role,
)
