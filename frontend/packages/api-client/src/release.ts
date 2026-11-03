const RELOADED_AT = 'idp:reloaded-at'

/** What a page knows about the release it runs against: the backend names its version on every response. */
export class Release {
  newer = false
  required = false

  constructor(readonly version: string) {}

  observe(appVersion: string): void {
    if (appVersion !== this.version) this.newer = true
  }

  upgradeRequired(): void {
    this.newer = true
    this.required = true
  }
}

export interface Page {
  reload(): void
  storage(): Storage
  now(): number
}

export function browserPage(window: Window): Page {
  return { reload: () => window.location.reload(), storage: () => window.sessionStorage, now: () => Date.now() }
}

/**
 * Reloads the page at most once in [interval], so a release whose bundle still fails cannot loop it. Without session storage there
 * is no such guard, so it does not reload at all.
 */
export function createReloader(page: Page, interval = 30_000): () => boolean {
  return () => {
    const now = page.now()
    try {
      const storage = page.storage()
      const last = Number(storage.getItem(RELOADED_AT))
      if (Number.isFinite(last) && last > 0 && now - last < interval) return false
      storage.setItem(RELOADED_AT, String(now))
    } catch {
      return false
    }
    page.reload()
    return true
  }
}

/** A chunk that fails to load is taken for a page of a release that is gone, and a reload fetches the live one. */
export function reloadOnChunkError(target: EventTarget, reload: () => boolean): void {
  target.addEventListener('vite:preloadError', (event) => {
    if (reload()) event.preventDefault()
  })
}
