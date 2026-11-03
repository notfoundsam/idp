import type { Problem } from './login'

export function messageOf(problem: Problem, retryAfter: number | null): string {
  switch (problem) {
    case 'rejected':
      return 'That did not work. Check what you entered and try again.'
    case 'throttled':
      return retryAfter === null ? 'Too many attempts. Wait a moment and try again.' : `Too many attempts. Try again in ${retryAfter} s.`
    case 'outdated':
      return 'This page is out of date. Reload it to go on.'
    case 'unreachable':
      return 'The server could not be reached. Check your connection and try again.'
    case 'unexpected':
      return 'Something went wrong. Try again.'
  }
}
