package idp.sharedkernel.domain

/** Lives here for the same reason as `DomainRuleViolation`: the shared web layer answers for it without knowing the context. */
open class NotFound(
    message: String,
) : RuntimeException(message)
