import { expect, type APIRequestContext, type BrowserContext, type Page } from '@playwright/test'
import { randomBytes } from 'node:crypto'
import { linkFromMail } from './mail'
import { totp } from './totp'

export const ACME = 'https://acme.idp.test'
export const GLOBEX = 'https://globex.idp.test'
export const SP_WHOAMI = 'https://sp.idp.test/simplesaml/whoami.php'

/** The fixtures of `acme`, see dev-tools/README.md. */
export const ALICE = { login: 'alice@acme.test', password: 'local-fixture-password-acme', totp: 'JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP' }
export const BOB = { login: 'bob@acme.test', password: 'local-fixture-password-acme', totp: 'JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP' }

/** Invited anew by `dev-tools/e2e-invitation.sh` before every run, like the users of `ops-sso`, so they start without factors. */
export const INVITEE = 'invitee@acme.test'
export const PASSKEY_USER = 'passkey@acme.test'
export const ACCOUNT_USER = 'account@acme.test'
export const ADMIN_USER = 'admin@acme.test'

/** Hex has none of the words the password policy refuses, such as parts of the address or the product name. */
export const newPassword = () => randomBytes(16).toString('hex')

export async function sessionCookie(context: BrowserContext, origin = ACME) {
  return (await context.cookies(origin)).find((it) => it.name === '__Host-session')
}

/** Opens the invitation from the mail and sets the first password, which leaves the page at the next step of the policy. */
export async function acceptInvitation(page: Page, request: APIRequestContext, contact: string): Promise<string> {
  const password = newPassword()
  await page.goto(await linkFromMail(request, { to: contact, subject: 'You are invited', path: '/invite' }))
  await expect(page.getByRole('heading', { name: 'Accept your invitation' })).toBeVisible()
  await page.getByLabel('New password').fill(password)
  await page.getByRole('button', { name: 'Set password' }).click()
  await expect(page).toHaveURL(/\/login\/\?flow=[0-9a-f-]{36}$/)
  return password
}

/**
 * Waits until the console shows the account. A test that clears the cookies earlier races the console, which sends a browser
 * without a session to the login and so aborts the navigation of the test.
 */
export async function atConsole(page: Page, login: string, origin = ACME) {
  await page.waitForURL(`${origin}/console/`)
  await expect(page.getByTestId('account-email')).toHaveText(login)
}

/** Shown once after the first second factor. */
export async function saveRecoveryCodes(page: Page) {
  await expect(page.getByTestId('recovery-codes').locator('li').first()).toBeVisible()
  await page.getByRole('button', { name: 'I have saved them' }).click()
}

/** Sets up TOTP at the step the policy asks for and answers its secret and the recovery codes shown once. */
export async function enrollTotp(page: Page): Promise<{ secret: string; codes: string[] }> {
  await page.getByRole('button', { name: 'Set up an authenticator app' }).click()
  const secret = (await page.getByTestId('totp-secret').innerText()).replace(/\s/g, '')
  await page.getByLabel('Code from your authenticator app').fill(totp(secret))
  await page.getByRole('button', { name: 'Confirm' }).click()
  const shown = page.getByTestId('recovery-codes').locator('li')
  await expect(shown.first()).toBeVisible()
  const codes = (await shown.allInnerTexts()).map((it) => it.trim())
  await page.getByRole('button', { name: 'I have saved them' }).click()
  return { secret, codes }
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

/** Each code works once, so a test that signs in again takes the next one and waits for no TOTP window. */
export async function enterRecoveryCode(page: Page, code: string) {
  await page.getByRole('button', { name: 'Use a recovery code instead' }).click()
  await page.getByLabel('Recovery code').fill(code)
  await page.getByRole('button', { name: 'Verify' }).click()
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
