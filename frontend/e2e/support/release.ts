import type { Page, Request } from '@playwright/test'
import { existsSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { ACME } from './login'

/** The login app of the last release, built with its version. After a release it is rebuilt from the release tag. */
const PREVIOUS_RELEASE = fileURLToPath(new URL('../fixtures/previous-release/', import.meta.url))
export const PREVIOUS_VERSION = '0.0.0'

export const NEXT_VERSION = '999.0.0'

/** The page of the previous release keeps the headers nginx serves, but not those that describe the body it replaces. */
const BODY_HEADERS: ReadonlySet<string> = new Set(['content-length', 'etag', 'last-modified'])

const isPageLoad = (page: Page, request: Request) => request.isNavigationRequest() && request.frame() === page.mainFrame()

/** Counts the full loads of the page, so a test tells a reload from a change of step. */
export function pageLoads(page: Page): () => number {
  let loads = 0
  page.on('request', (request) => {
    if (isPageLoad(page, request)) loads++
  })
  return () => loads
}

export async function navigationType(page: Page): Promise<string> {
  return page.evaluate(() => (performance.getEntriesByType('navigation')[0] as PerformanceNavigationTiming).type)
}

/**
 * The tab of a user who opened the login before a deploy: the page of the previous release, against a backend of the same release.
 * The returned function deploys the live release. From then on the backend answers with its own version and the live page is
 * served, while the files of the previous release stay, as they stay in S3.
 */
export async function openedBeforeRelease(page: Page): Promise<() => void> {
  let released = false
  await page.route(`${ACME}/login/**`, async (route) => {
    const file = /^\/login\/(assets\/[\w.-]+)$/.exec(new URL(route.request().url()).pathname)?.[1]
    if (file !== undefined && existsSync(PREVIOUS_RELEASE + file)) return route.fulfill({ path: PREVIOUS_RELEASE + file })
    if (released || !isPageLoad(page, route.request())) return route.continue()
    const live = await route.fetch()
    const headers = Object.fromEntries(Object.entries(live.headers()).filter(([name]) => !BODY_HEADERS.has(name)))
    return route.fulfill({ status: live.status(), headers, path: `${PREVIOUS_RELEASE}index.html` })
  })
  await page.route(`${ACME}/api/**`, async (route) => {
    if (released) return route.continue()
    const response = await route.fetch()
    return route.fulfill({ response, headers: { ...response.headers(), 'x-app-version': PREVIOUS_VERSION } })
  })
  return () => {
    released = true
  }
}

/** A deploy while the page stays open: the backend names [NEXT_VERSION] until the page loads again, which brings the new bundle. */
export async function deployWhileOpen(page: Page): Promise<void> {
  let reloaded = false
  page.on('request', (request) => {
    if (isPageLoad(page, request)) reloaded = true
  })
  await page.route(`${ACME}/api/**`, async (route) => {
    if (reloaded) return route.continue()
    const response = await route.fetch()
    return route.fulfill({ response, headers: { ...response.headers(), 'x-app-version': NEXT_VERSION } })
  })
}
