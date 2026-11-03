package idp.audience.account.application.command

import idp.authentication.application.LoginFactor
import idp.identity.application.TotpService
import idp.sharedkernel.audit.Actor
import idp.sharedkernel.audit.AuditLog
import idp.sharedkernel.audit.AuditRecord
import idp.sharedkernel.audit.AuditTarget
import idp.sharedkernel.domain.DomainRuleViolation
import idp.sharedkernel.security.SecurityContext
import idp.sharedkernel.web.ClientIp

private const val CREDENTIAL_REFUSED = "account.credential_refused"
private const val FACTOR = "factor"

/** One refusal for a wrong current password, a wrong TOTP code and a passkey that did not verify. */
class CredentialRejected : DomainRuleViolation("The credential is not valid, or the next attempt has to wait")

/** Recovery codes stand in for a second factor, so without one there is nothing they could replace. */
class NoSecondFactor : DomainRuleViolation("The user has no second factor")

/** Not a data class: `toString` must not print the codes. Empty when the user kept the codes they had. */
class IssuedCodes(
    val recoveryCodes: List<String>,
) {
    override fun toString(): String = "IssuedCodes(***)"
}

internal fun clientIp(): String? = ClientIp.currentOrNull()?.address

/** Every message here has a permission, so Security let it through only with the user of a live session. */
internal fun currentUser(): Actor.User = checkNotNull(SecurityContext.currentOrNull()).actor as Actor.User

internal fun AuditLog.credentialRefused(
    user: Actor.User,
    factor: LoginFactor,
) = recordDenial(AuditRecord(user, CREDENTIAL_REFUSED, AuditTarget.user(user.id), clientIp(), mapOf(FACTOR to factor.name)))

/** A new factor issues codes only to a user without any, so adding a second factor does not void codes saved for the first. */
internal fun TotpService.codesForNewFactor(user: Actor.User): IssuedCodes =
    IssuedCodes(if (recoveryCodesLeft(user.id) == 0) issueRecoveryCodes(user.id, user, clientIp()) else emptyList())
