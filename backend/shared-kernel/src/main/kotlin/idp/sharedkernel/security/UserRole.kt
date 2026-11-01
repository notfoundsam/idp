package idp.sharedkernel.security

/** Lives here because an audience has to name these and sees no `domain` package of `identity`. */
enum class UserRole {
    TENANT_ADMIN,
    USER,
}

enum class UserStatus {
    INVITED,
    ACTIVE,
    BLOCKED,
}
