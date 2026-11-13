import { expect, test, type Page } from '@playwright/test'
import {
  ACCOUNT_USER,
  acceptInvitation,
  ACME,
  cspViolations,
  enterLogin,
  enterPassword,
  enterRecoveryCode,
  enterTotp,
  newPassword,
  sentToLogin,
  sessionCookie,
} from './support/login'
import { virtualAuthenticator } from './support/passkey'
import { totp } from './support/totp'

const CONSOLE = `${ACME}/console/`
const SECOND_BROWSER = 'Mozilla/5.0 (X11; Linux x86_64) e2e-second-browser'

async function shownCodes(page: Page): Promise<string[]> {
  const codes = page.getByTestId('recovery-codes').locator('li')
  await expect(codes.first()).toBeVisible()
  const shown = await codes.allInnerTexts()
  await page.getByRole('button', { name: 'I have saved them' }).click()
  await expect(page.getByTestId('recovery-codes')).toHaveCount(0)
  return shown.map((it) => it.trim())
}

async function signIn(page: Page, password: string, secondFactor: (page: Page) => Promise<void>) {
  await page.goto(`${ACME}/login/`)
  await enterLogin(page, ACCOUNT_USER)
  await enterPassword(page, password)
  await secondFactor(page)
  await page.waitForURL(CONSOLE)
  await expect(page.getByTestId('account-email')).toHaveText(ACCOUNT_USER)
}

async function signOut(page: Page) {
  await page.getByRole('button', { name: 'Sign out', exact: true }).click()
  await expect(page.getByTestId('signed-out')).toBeVisible()
}

/** One user, invited anew before every run, whose factors the tests change one after another. */
test.describe.serial('the account of a user', () => {
  let password = ''
  let secret = ''
  let codes: string[] = []

  test('manages the password, the TOTP, passkeys and recovery codes', async ({ page, request, context }) => {
    const violations = cspViolations(page)
    const authenticator = await virtualAuthenticator(page)
    const first = await acceptInvitation(page, request, ACCOUNT_USER)
    await page.getByRole('button', { name: 'Set up an authenticator app' }).click()
    const enrolled = (await page.getByTestId('totp-secret').innerText()).replace(/\s/g, '')
    await page.getByLabel('Code from your authenticator app').fill(totp(enrolled))
    await page.getByRole('button', { name: 'Confirm' }).click()
    await page.getByRole('button', { name: 'I have saved them' }).click()
    await page.waitForURL(CONSOLE)
    await expect(page.getByTestId('account-email')).toHaveText(ACCOUNT_USER)
    await expect(page.getByTestId('totp-status')).toHaveText('An authenticator app is set up.')

    await expect(page.locator('#password-rules')).toContainText('15 or more is recommended')
    await expect(page.getByLabel('Current password')).toHaveAttribute('type', 'password')
    await expect(page.getByLabel('New password')).toHaveAttribute('type', 'password')
    await page.getByLabel('Current password').fill(newPassword())
    await page.getByLabel('New password').fill(newPassword())
    await page.getByRole('button', { name: 'Change password' }).click()
    await expect(page.getByRole('alert')).toContainText('The current password is wrong, or the new one was refused')
    password = newPassword()
    await page.getByLabel('Current password').fill(first)
    await page.getByLabel('New password').fill(password)
    await page.getByRole('button', { name: 'Change password' }).click()
    await expect(page.getByTestId('password-changed')).toBeVisible()
    await expect(page.getByLabel('Current password')).toHaveValue('')

    await page.getByRole('button', { name: 'Remove authenticator app' }).click()
    await expect(page.getByRole('alert')).toContainText('Add another one first')
    await expect(page.getByTestId('totp-status')).toHaveText('An authenticator app is set up.')

    await page.getByRole('button', { name: 'Add a passkey' }).click()
    await expect(page.getByTestId('passkey')).toHaveCount(1)
    await expect(page.getByTestId('passkey')).toContainText('never used')
    await expect(page.getByTestId('recovery-codes')).toHaveCount(0)
    expect(await authenticator.passkeys()).toEqual([{ rpId: 'acme.idp.test', discoverable: true }])

    await page.getByRole('button', { name: 'Remove authenticator app' }).click()
    await expect(page.getByTestId('totp-status')).toHaveText('No authenticator app is set up.')
    await expect(page.getByTestId('end-sessions-offer')).toBeVisible()
    await expect(page.getByTestId('recovery-codes-left')).toBeVisible()
    await page.getByRole('button', { name: 'Set up an authenticator app' }).click()
    secret = (await page.getByTestId('totp-secret').innerText()).replace(/\s/g, '')
    expect(secret).not.toBe(enrolled)
    const link = await page.getByRole('link', { name: 'Open in an authenticator app' }).getAttribute('href')
    expect(link).toContain(`otpauth://totp/${encodeURIComponent(`acme.idp.test:${ACCOUNT_USER}`)}?secret=${secret}`)
    await page.getByLabel('Code from your authenticator app').fill(totp(secret, Date.now() - 120_000))
    await page.getByRole('button', { name: 'Confirm' }).click()
    await expect(page.getByRole('alert')).toContainText('That code did not work')
    await page.getByLabel('Code from your authenticator app').fill(totp(secret))
    await page.getByRole('button', { name: 'Confirm' }).click()
    await expect(page.getByTestId('totp-status')).toHaveText('An authenticator app is set up.')
    await expect(page.getByTestId('recovery-codes')).toHaveCount(0)

    await page.getByRole('button', { name: /^Remove the passkey added / }).click()
    await expect(page.getByTestId('no-passkeys')).toBeVisible()

    await page.getByRole('button', { name: 'Get new recovery codes' }).click()
    codes = await shownCodes(page)
    expect(codes.length).toBeGreaterThanOrEqual(5)
    await expect(page.getByTestId('recovery-codes-left')).toHaveText(`${codes.length} recovery codes left.`)

    const stored = await page.evaluate(() => ({ local: Object.keys(localStorage), session: Object.keys(sessionStorage) }))
    expect(stored.local).toEqual([])
    expect(stored.session.filter((key) => key !== 'idp:reloaded-at')).toEqual([])
    expect(violations).toEqual([])

    await signOut(page)
    expect(await sessionCookie(context)).toBeUndefined()
    await sentToLogin(page)
  })

  test('lists the sessions, marks this one, and ends another one or all others', async ({ page, browser }) => {
    await signIn(page, password, (it) => enterTotp(it, secret))
    const other = await browser.newContext({ userAgent: SECOND_BROWSER })
    const second = await other.newPage()
    await signIn(second, password, (it) => enterRecoveryCode(it, codes[0] ?? ''))

    await page.getByRole('link', { name: 'Sessions' }).click()
    await expect(page).toHaveURL(`${CONSOLE}sessions`)
    const current = page.locator('[data-testid="session"][data-current="true"]')
    await expect(current).toHaveCount(1)
    await expect(current).toContainText('This browser')
    await expect(current.getByRole('button', { name: 'End session' })).toHaveCount(0)
    const theOther = page.getByTestId('session').filter({ hasText: SECOND_BROWSER })
    await expect(theOther).toHaveCount(1)
    await theOther.getByRole('button', { name: 'End session' }).click()
    await expect(theOther).toHaveCount(0)

    await sentToLogin(second)
    await signIn(second, password, (it) => enterRecoveryCode(it, codes[1] ?? ''))
    await page.reload()
    await expect(theOther).toHaveCount(1)
    await page.getByRole('button', { name: 'Sign out everywhere else' }).click()
    await expect(page.getByTestId('session')).toHaveCount(1)
    await expect(current).toHaveCount(1)

    await sentToLogin(second)
    await page.reload()
    await expect(page.getByTestId('account-email')).toHaveText(ACCOUNT_USER)
    await other.close()
  })

  test('a fresh login that the console asks for proves every factor again and leads back to it', async ({ page }) => {
    await signIn(page, password, (it) => enterRecoveryCode(it, codes[2] ?? ''))
    await page.goto(`${ACME}/login/?reauthenticate`)
    await expect(page.getByLabel('Email')).toBeVisible()
    await enterLogin(page, ACCOUNT_USER)
    await enterPassword(page, password)
    await enterRecoveryCode(page, codes[3] ?? '')
    await page.waitForURL(CONSOLE)
    await expect(page.getByTestId('account-email')).toHaveText(ACCOUNT_USER)
    await signOut(page)
  })

  test('shows the logout on every page and forgets the account even when the server cannot be reached (V7.4.4, V14.3.1)', async ({
    page,
    context,
  }) => {
    await signIn(page, password, (it) => enterRecoveryCode(it, codes[4] ?? ''))
    for (const path of ['', 'sessions']) {
      await page.goto(`${CONSOLE}${path}`)
      await expect(page.getByTestId('account-email')).toHaveText(ACCOUNT_USER)
      await expect(page.getByRole('button', { name: 'Sign out', exact: true })).toBeVisible()
    }
    await page.goto(CONSOLE)
    await expect(page.getByTestId('totp-status')).toBeVisible()
    await page.getByRole('link', { name: 'Sessions' }).click()
    await expect(page.getByTestId('session').first()).toBeVisible()

    await page.route('**/api/account/logout', (route) => route.abort('internetdisconnected'))
    await page.getByRole('button', { name: 'Sign out', exact: true }).click()
    await expect(page.getByTestId('signed-out-unconfirmed')).toContainText('did not confirm')
    await expect(page.getByText(ACCOUNT_USER)).toHaveCount(0)

    // Nothing answers now, so whatever the page shows of the account would come from what it kept.
    await page.route('**/api/**', (route) => route.abort('internetdisconnected'))
    await page.goBack()
    await expect(page).toHaveURL(CONSOLE)
    await expect(page.getByRole('alert').first()).toContainText('could not be reached')
    await expect(page.getByTestId('account-email')).toHaveCount(0)
    await expect(page.getByTestId('totp-status')).toHaveCount(0)
    await expect(page.getByText(ACCOUNT_USER)).toHaveCount(0)
    expect(await sessionCookie(context)).toBeDefined()

    await page.unrouteAll()
    await page.goto(CONSOLE)
    await expect(page.getByTestId('account-email')).toHaveText(ACCOUNT_USER)
    await signOut(page)
    expect(await sessionCookie(context)).toBeUndefined()
    await sentToLogin(page)
  })
})
