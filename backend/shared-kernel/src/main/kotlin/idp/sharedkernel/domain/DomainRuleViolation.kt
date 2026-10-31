package idp.sharedkernel.domain

/** Lives here because an audience sees no `domain` package of a context, yet it has to answer for a broken rule. */
open class DomainRuleViolation(
    message: String,
) : RuntimeException(message)
