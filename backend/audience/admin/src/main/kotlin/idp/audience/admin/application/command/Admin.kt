package idp.audience.admin.application.command

import idp.sharedkernel.audit.Actor
import idp.sharedkernel.security.SecurityContext
import idp.sharedkernel.web.ClientIp
import java.util.UUID

internal fun clientIp(): String? = ClientIp.currentOrNull()?.address

/** Every message here has a permission that only an admin holds, so Security let it through only with the admin of a live session. */
internal fun currentAdmin(): Actor.User = checkNotNull(SecurityContext.currentOrNull()).actor as Actor.User

/** A principal of this audience comes from a session cookie, so it always names its session. */
internal fun currentSession(): UUID = checkNotNull(SecurityContext.currentOrNull()?.sessionId)
