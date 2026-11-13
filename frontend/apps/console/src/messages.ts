import { ApiError, needsReauthentication, UPGRADE_REQUIRED } from '@idp/api-client'
import { OUTDATED, REJECTED, throttled, UNEXPECTED, UNREACHABLE } from '@idp/ui'

/** The API refuses a wrong current password and a weak new one with the same empty answer, so the form names both. */
export const PASSWORD_REFUSED =
  'The current password is wrong, or the new one was refused. Check both against the rules above and try again.'

/** A wrong code and one that has to wait read the same. */
export const CODE_REFUSED = 'That code did not work. Wait for the next one in the app and try again.'

/** The policy keeps the last factor it asks for, so the replacement comes first. */
export const FACTOR_REQUIRED = 'Your organization requires this kind of sign-in. Add another one first, then remove this one.'

/** What a failed call means to the user. A refusal of the input reads differently on each form, so [rejected] names it there. */
export function messageOf(error: unknown, rejected = REJECTED): string {
  if (needsReauthentication(error)) return 'For this change, sign in again: your last sign-in is too old.'
  if (!(error instanceof ApiError)) return error instanceof DOMException ? 'The browser did not create a passkey. Try again.' : UNREACHABLE
  switch (error.status) {
    case 422:
      return rejected
    case 429:
      return throttled(error.retryAfter)
    case UPGRADE_REQUIRED:
      return OUTDATED
    default:
      return UNEXPECTED
  }
}

const dates = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' })

export function dateOf(iso: string): string {
  return dates.format(new Date(iso))
}
