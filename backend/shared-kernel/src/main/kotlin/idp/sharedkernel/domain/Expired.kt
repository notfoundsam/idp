package idp.sharedkernel.domain

/** Lives here for the same reason as `DomainRuleViolation`, and tells a client to start over rather than to correct its input. */
open class Expired(
    message: String,
) : DomainRuleViolation(message)
