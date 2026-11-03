import { describe, expect, test } from 'vitest'
import { createReloader, Release, reloadOnChunkError, type Page } from './release'

class MemoryStorage implements Storage {
  private readonly items = new Map<string, string>()
  get length() {
    return this.items.size
  }
  clear() {
    this.items.clear()
  }
  getItem(key: string) {
    return this.items.get(key) ?? null
  }
  key(index: number) {
    return [...this.items.keys()][index] ?? null
  }
  removeItem(key: string) {
    this.items.delete(key)
  }
  setItem(key: string, value: string) {
    this.items.set(key, value)
  }
}

function fakePage(storage: () => Storage = () => new MemoryStorage()) {
  const kept = storage()
  const page = { reloads: 0, time: 1_000_000, reload: () => page.reloads++, storage: () => kept, now: () => page.time }
  return page
}

describe('Release', () => {
  test('the same version as the client is no news', () => {
    const release = new Release('1.4.0')
    release.observe('1.4.0')
    expect(release.newer).toBe(false)
    expect(release.required).toBe(false)
  })

  test('another version means a new release, which the page need not load yet', () => {
    const release = new Release('1.4.0')
    release.observe('1.5.0')
    expect(release.newer).toBe(true)
    expect(release.required).toBe(false)
  })

  test('a rollback is a new release too', () => {
    const release = new Release('1.4.0')
    release.observe('1.3.9')
    expect(release.newer).toBe(true)
  })

  test('426 requires the new release', () => {
    const release = new Release('1.4.0')
    release.upgradeRequired()
    expect(release.newer).toBe(true)
    expect(release.required).toBe(true)
  })
})

describe('reloads', () => {
  test('the first reload goes through', () => {
    const page = fakePage()
    expect(createReloader(page)()).toBe(true)
    expect(page.reloads).toBe(1)
  })

  test('a second reload within the interval is refused, so a broken release cannot loop the page', () => {
    const page = fakePage()
    const reload = createReloader(page, 30_000)
    reload()
    page.time += 29_999
    expect(reload()).toBe(false)
    expect(page.reloads).toBe(1)
  })

  test('after the interval the page reloads again', () => {
    const page = fakePage()
    const reload = createReloader(page, 30_000)
    reload()
    page.time += 30_000
    expect(reload()).toBe(true)
    expect(page.reloads).toBe(2)
  })

  test('the guard survives the reload, because it is kept in the session', () => {
    const storage = new MemoryStorage()
    const before = fakePage(() => storage)
    createReloader(before)()
    const after = fakePage(() => storage)
    expect(createReloader(after)()).toBe(false)
  })

  test('without session storage the page does not reload', () => {
    const page: Page & { reloads: number } = {
      reloads: 0,
      reload: () => page.reloads++,
      storage: () => {
        throw new DOMException('denied', 'SecurityError')
      },
      now: () => 1,
    }
    expect(createReloader(page)()).toBe(false)
    expect(page.reloads).toBe(0)
  })

  test('a chunk that fails to load reloads the page and stops the error', () => {
    const target = new EventTarget()
    let reloads = 0
    reloadOnChunkError(target, () => ++reloads > 0)
    const event = new Event('vite:preloadError', { cancelable: true })
    target.dispatchEvent(event)
    expect(reloads).toBe(1)
    expect(event.defaultPrevented).toBe(true)
  })

  test('a chunk error after a refused reload goes on as an error', () => {
    const target = new EventTarget()
    reloadOnChunkError(target, () => false)
    const event = new Event('vite:preloadError', { cancelable: true })
    target.dispatchEvent(event)
    expect(event.defaultPrevented).toBe(false)
  })
})
