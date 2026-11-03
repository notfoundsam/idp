import { expect, type BrowserContext, type Page } from '@playwright/test'
import { totp } from './totp'

export const ACME = 'https://acme.idp.test'
export const SP_WHOAMI = 'https://sp.idp.test/simplesaml/whoami.php'

/** The fixtures of `acme`, see dev-tools/README.md. */
export const ALICE = { login: 'alice@acme.test', password: 'local-fixture-password-acme', totp: 'JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP' }
export const BOB = { login: 'bob@acme.test', password: 'local-fixture-password-acme', totp: 'JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP' }

/** Invited anew by `dev-tools/e2e-invitation.sh` before every run, so it starts without a password or factors. */
export const INVITEE = 'invitee@acme.test'

export async function sessionCookie(context: BrowserContext) {
  return (await context.cookies(ACME)).find((it) => it.name === '__Host-session')
}

/** Types nothing: the text comes from the clipboard, as from a password manager. */
export async function paste(page: Page, label: string, text: string) {
  await page.context().grantPermissions(['clipboard-read', 'clipboard-write'], { origin: ACME })
  await page.evaluate((value) => navigator.clipboard.writeText(value), text)
  const field = page.getByLabel(label, { exact: true })
  await field.focus()
  await page.keyboard.press('ControlOrMeta+V')
  await expect(field).toHaveValue(text)
}

/** Collects what the CSP blocks, so a test can tell that a page worked within it. */
export function cspViolations(page: Page): string[] {
  const violations: string[] = []
  page.on('console', (message) => {
    if (message.text().includes('Content Security Policy')) violations.push(message.text())
  })
  return violations
}

export async function enterLogin(page: Page, login: string) {
  await page.getByLabel('Email').fill(login)
  await page.getByRole('button', { name: 'Next' }).click()
}

export async function enterPassword(page: Page, password: string) {
  await page.getByLabel('Password').fill(password)
  await page.getByRole('button', { name: 'Sign in' }).click()
}

/**
 * A code counts once, so a code that an earlier run spent in the same 30 seconds is refused. Then the test waits for the next
 * window, which costs one of the free failures.
 */
export async function enterTotp(page: Page, secret: string) {
  for (let attempt = 0; attempt < 2; attempt++) {
    await page.getByLabel('Code from your authenticator app').fill(totp(secret))
    const [response] = await Promise.all([
      page.waitForResponse((it) => it.url().endsWith('/totp') && it.request().method() === 'POST'),
      page.getByRole('button', { name: 'Verify' }).click(),
    ])
    if (response.ok()) return
    expect(response.status()).toBe(422)
    await page.waitForTimeout(30_000 - (Date.now() % 30_000) + 1_000)
  }
  throw new Error('The TOTP code was refused twice')
}
