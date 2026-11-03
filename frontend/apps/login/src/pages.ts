export const LOGIN_PATH = '/login/'
export const FORGOT_PASSWORD_PATH = '/login/forgot-password'

export type Page = 'login' | 'forgot-password' | 'invite' | 'reset-password' | 'confirm-email'

const MAIL_PAGES: ReadonlyMap<string, Page> = new Map<string, Page>([
  ['/invite', 'invite'],
  ['/reset-password', 'reset-password'],
  ['/confirm-email', 'confirm-email'],
])

/** The links in mails lead to their own paths outside `/login/`, and nginx serves this app there too. */
export function pageAt(pathname: string): Page {
  if (pathname === FORGOT_PASSWORD_PATH) return 'forgot-password'
  return MAIL_PAGES.get(pathname) ?? 'login'
}

export interface Address {
  location: { hash: string; pathname: string; search: string }
  history: { state: unknown; replaceState(data: unknown, unused: string, url: string): void }
}

/**
 * A mail keeps its token in the fragment, which no request carries. The page drops the fragment before it calls the API, so the
 * token is not left in the address bar, the history or a bookmark.
 */
export function takeToken({ location, history }: Address): string | null {
  const { hash, pathname, search } = location
  if (hash === '') return null
  history.replaceState(history.state, '', pathname + search)
  const token = new URLSearchParams(hash.slice(1)).get('token')
  return token === null || token === '' ? null : token
}
