/** The answers that read the same in the login and the console. */
export const REJECTED = 'That did not work. Check what you entered and try again.'
export const OUTDATED = 'This page is out of date. Reload it to go on.'
export const UNREACHABLE = 'The server could not be reached. Check your connection and try again.'
export const UNEXPECTED = 'Something went wrong. Try again.'

export function throttled(retryAfter: number | null): string {
  return retryAfter === null ? 'Too many attempts. Wait a moment and try again.' : `Too many attempts. Try again in ${retryAfter} s.`
}
