package idp.audience.operator.application.command

import idp.sharedkernel.audit.Actor
import idp.sharedkernel.security.SecurityContext
import idp.sharedkernel.web.ClientIp

/** A handler runs behind Security, so a principal is here, and in this audience it is always an operator. */
internal fun operator(): Actor.Operator = checkNotNull(SecurityContext.currentOrNull()).actor as Actor.Operator

/** Empty in the CLI, which has no request. */
internal fun operatorIp(): String? = ClientIp.currentOrNull()?.address
