package idp.sharedkernel.domain

/** Lives here for the same reason as `DomainRuleViolation`, and tells a client that the state moved on while it was asking. */
open class Conflict(
    message: String,
) : DomainRuleViolation(message)
