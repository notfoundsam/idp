package idp.sharedkernel.security

/** Lives here because an audience has to name these and sees no `domain` package of `identity`. */
enum class UserRole {
    TENANT_ADMIN,
    USER,
    ;

    val role: Role get() = Role(name)

    companion object {
        /** Null for a role that no user of a tenant holds, such as an operator's. */
        fun of(role: Role): UserRole? = entries.firstOrNull { it.role == role }
    }
}

enum class UserStatus {
    INVITED,
    ACTIVE,
    BLOCKED,
}
