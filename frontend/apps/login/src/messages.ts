import { ApiError, UPGRADE_REQUIRED } from '@idp/api-client'
import { OUTDATED, REJECTED, throttled, UNEXPECTED, UNREACHABLE } from '@idp/ui'
import type { Problem } from './login'

/** The API refuses a bad link and a weak password with the same empty answer, so the page names both. */
export const PASSWORD_LINK_REFUSED =
  'The link has expired or was already used, or the password was refused. Check the password against the rules above, or ask for a new link.'

export const LINK_REFUSED = 'The link has expired or was already used.'

export interface Trouble {
  problem: Problem
  retryAfter: number | null
}

/** What a failed call means to the user, for the answers that read the same on every page. */
export function troubleOf(error: unknown): Trouble {
  if (!(error instanceof ApiError)) return { problem: error instanceof DOMException ? 'rejected' : 'unreachable', retryAfter: null }
  switch (error.status) {
    case 422:
      return { problem: 'rejected', retryAfter: null }
    case 429:
      return { problem: 'throttled', retryAfter: error.retryAfter }
    case UPGRADE_REQUIRED:
      return { problem: 'outdated', retryAfter: null }
    default:
      return { problem: 'unexpected', retryAfter: null }
  }
}

export function messageOf(problem: Problem, retryAfter: number | null): string {
  switch (problem) {
    case 'rejected':
      return REJECTED
    case 'throttled':
      return throttled(retryAfter)
    case 'outdated':
      return OUTDATED
    case 'unreachable':
      return UNREACHABLE
    case 'broken-link':
      return 'This link is incomplete. Open it again from the mail, or copy the whole address into the browser.'
    case 'unexpected':
      return UNEXPECTED
  }
}
