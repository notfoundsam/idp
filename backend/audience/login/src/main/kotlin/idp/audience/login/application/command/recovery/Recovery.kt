package idp.audience.login.application.command.recovery

import idp.audience.login.application.command.clientIp
import idp.sharedkernel.audit.Actor
import idp.sharedkernel.audit.AuditLog
import idp.sharedkernel.audit.AuditRecord
import idp.sharedkernel.tenant.TenantResolver

private const val TOKEN_REFUSED = "recovery.token_refused"
private const val KIND = "kind"

internal const val INVITATION = "invitation"
internal const val PASSWORD_RESET = "password_reset"
internal const val EMAIL_CHANGE = "email_change"

/** The token stays out of the record, and so does a target: a refused token names nobody. */
internal fun AuditLog.tokenRefused(kind: String) =
    recordDenial(AuditRecord(Actor.Anonymous, TOKEN_REFUSED, ip = clientIp(), details = mapOf(KIND to kind)))

/** The name and the subdomain of the tenant. The filter resolved this host for the same request, so this reads its cache. */
internal fun TenantResolver.wordsOf(host: String): Set<String> = setOfNotNull(resolve(host)?.name, host.substringBefore('.'))
