import { expect, test, type Page } from '@playwright/test'
import { randomUUID } from 'node:crypto'
import {
  ACME,
  acceptInvitation,
  ALICE,
  atConsole,
  BOB,
  cspViolations,
  enterLogin,
  enterPassword,
  enterTotp,
  GLOBEX,
  INVITEE,
  newPassword,
  PASSKEY_USER,
  paste,
  saveRecoveryCodes,
  sessionCookie,
  SP_WHOAMI,
} from './support/login'
import { linkFromMail } from './support/mail'
import { virtualAuthenticator } from './support/passkey'
import { totp } from './support/totp'

const FLOW_URL = /\/login\/\?flow=([0-9a-f-]{36})$/

interface Whoami {
  nameId: string | null
  attributes: object | null
}

test.describe('login through an SP', () => {
  test('a password and a TOTP code lead back to the SP, which receives the user', async ({ page }) => {
    const violations = cspViolations(page)
    await page.goto(SP_WHOAMI)
    await expect(page).toHaveURL(new RegExp(`^${ACME}/login/\\?flow=[0-9a-f-]{36}$`))

    await enterLogin(page, ALICE.login)
    await enterPassword(page, 'not-the-password')
    await expect(page.getByRole('alert')).toContainText('did not work')
    await expect(page.getByLabel('Password')).toBeVisible()

    await enterPassword(page, ALICE.password)
    await enterTotp(page, ALICE.totp)

    await page.waitForURL(SP_WHOAMI)
    const said = JSON.parse((await page.locator('body').innerText()).trim()) as Whoami
    expect(said.nameId).toBeTruthy()
    expect(Object.values(said.attributes ?? {}).flat()).toContain(ALICE.login)
    expect(violations).toEqual([])
  })

  test('a user who is not assigned to the SP proves every factor and is still refused there', async ({ page }) => {
    await page.goto(SP_WHOAMI)
    await enterLogin(page, BOB.login)
    await enterPassword(page, BOB.password)
    const [refusal] = await Promise.all([
      page.waitForResponse((it) => it.url().startsWith(`${ACME}/saml/resume/`)),
      enterTotp(page, BOB.totp),
    ])
    expect(refusal.status()).toBe(403)
    await page.waitForURL(new RegExp(`^${ACME}/saml/resume/`))
    expect(page.url()).not.toContain('sp.idp.test')
  })
})

/** Everything a script of the origin can read back, the cookies it may see included. */
async function readableStorage(page: Page) {
  return page.evaluate(async () => ({
    local: Object.entries(localStorage),
    session: Object.entries(sessionStorage),
    databases: (await indexedDB.databases()).map((it) => it.name),
    caches: await caches.keys(),
    cookies: document.cookie.split('; ').filter(Boolean),
  }))
}

test.describe('the browser of a user', () => {
  test('keeps nothing sensitive in its storage and loads no script from another party', async ({ page, context }) => {
    const foreign: string[] = []
    page.on('request', (request) => {
      if (new URL(request.url()).origin !== ACME) foreign.push(request.url())
    })
    await page.goto(`${ACME}/login/`)
    await enterLogin(page, BOB.login)
    await enterPassword(page, BOB.password)
    await expect(page.getByLabel('Code from your authenticator app')).toBeVisible()
    const midway = await readableStorage(page)
    const scripts = await page.locator('script').evaluateAll((all) => all.map((it) => (it as HTMLScriptElement).src))
    expect(scripts.length).toBeGreaterThan(0)
    expect(scripts.filter((src) => !src.startsWith(`${ACME}/login/assets/`))).toEqual([])
    await enterTotp(page, BOB.totp)
    await page.waitForURL(`${ACME}/console/`)
    const signedIn = await readableStorage(page)

    for (const stored of [midway, signedIn]) {
      expect(stored.local).toEqual([])
      expect(stored.session).toEqual([])
      expect(stored.databases).toEqual([])
      expect(stored.caches).toEqual([])
      expect(JSON.stringify(stored)).not.toContain(BOB.password)
    }
    expect(midway.cookies).toEqual([])
    expect(signedIn.cookies.map((it) => it.split('=')[0])).toEqual(['__Host-csrf'])
    const cookies = await context.cookies(ACME)
    expect(cookies.filter((it) => !it.httpOnly).map((it) => it.name)).toEqual(['__Host-csrf'])
    expect(cookies.map((it) => it.name)).toContain('__Host-session')
    expect(foreign).toEqual([])
  })
})

test.describe.serial('an invited user', () => {
  const firstPassword = newPassword()
  let secret = ''

  test('the link of the invitation sets the first password and works once', async ({ page, request }) => {
    const violations = cspViolations(page)
    const link = await linkFromMail(request, { to: INVITEE, subject: 'You are invited', path: '/invite' })
    await page.goto(link)
    await expect(page.getByRole('heading', { name: 'Accept your invitation' })).toBeVisible()
    expect(page.url()).toBe(`${ACME}/invite`)
    await expect(page.locator('#password-rules')).toContainText('15 or more is recommended')
    await expect(page.getByLabel('New password')).toHaveAttribute('type', 'password')

    await paste(page, 'New password', firstPassword)
    await page.getByRole('button', { name: 'Set password' }).click()
    await expect(page.getByRole('button', { name: 'Set up an authenticator app' })).toBeVisible()
    await expect(page).toHaveURL(new RegExp(`^${ACME}/login/\\?flow=[0-9a-f-]{36}$`))
    expect(violations).toEqual([])

    await page.goto(link)
    await page.getByLabel('New password').fill(newPassword())
    await page.getByRole('button', { name: 'Set password' }).click()
    await expect(page.getByRole('alert')).toContainText('expired or was already used')
  })

  test('a direct login sets up the second factor the policy asks for and opens a session', async ({ page, context }) => {
    await page.goto(`${ACME}/login/`)
    await enterLogin(page, INVITEE)
    await enterPassword(page, firstPassword)
    await page.getByRole('button', { name: 'Set up an authenticator app' }).click()
    secret = (await page.getByTestId('totp-secret').innerText()).replace(/\s/g, '')
    await page.getByLabel('Code from your authenticator app').fill(totp(secret))
    await page.getByRole('button', { name: 'Confirm' }).click()
    await expect(page.getByTestId('recovery-codes').locator('li').first()).toBeVisible()
    expect(await sessionCookie(context)).toBeUndefined()

    await page.getByRole('button', { name: 'I have saved them' }).click()
    await page.waitForURL(`${ACME}/console/`)
    expect(await sessionCookie(context)).toMatchObject({ httpOnly: true, secure: true, sameSite: 'Lax' })
  })

  test('a forgotten password is replaced through the link from the mail', async ({ page, request, context }) => {
    await page.goto(`${ACME}/login/`)
    await enterLogin(page, INVITEE)
    await page.getByRole('link', { name: 'Forgot your password?' }).click()
    await expect(page).toHaveURL(`${ACME}/login/forgot-password`)
    const since = Date.now() - 2_000
    await page.getByLabel('Email').fill(INVITEE)
    await page.getByRole('button', { name: 'Send link' }).click()
    await expect(page.getByTestId('reset-requested')).toBeVisible()

    const link = await linkFromMail(request, { to: INVITEE, subject: 'Reset your', path: '/reset-password', since })
    await page.goto(link)
    expect(page.url()).toBe(`${ACME}/reset-password`)
    await expect(page.locator('#password-rules')).toContainText('15 or more is recommended')
    const changed = newPassword()
    await page.getByLabel('New password').fill(changed)
    await page.getByRole('button', { name: 'Set password' }).click()
    await expect(page.getByTestId('password-changed')).toBeVisible()

    await page.getByRole('link', { name: 'Sign in' }).click()
    await enterLogin(page, INVITEE)
    await expect(page.getByLabel('Password')).toHaveAttribute('type', 'password')
    await paste(page, 'Password', changed)
    await page.getByRole('button', { name: 'Sign in' }).click()
    await enterTotp(page, secret)
    await page.waitForURL(`${ACME}/console/`)
    expect(await sessionCookie(context)).toBeDefined()
  })
})

test.describe('a user with a passkey', () => {
  test('registers it when the policy asks for a second factor, then signs in with it alone or after the password', async ({
    page,
    request,
    context,
  }) => {
    const violations = cspViolations(page)
    const authenticator = await virtualAuthenticator(page)
    const password = await acceptInvitation(page, request, PASSKEY_USER)
    await page.getByRole('button', { name: 'Register a passkey' }).click()
    await saveRecoveryCodes(page)
    await atConsole(page, PASSKEY_USER)
    expect(await authenticator.passkeys()).toEqual([{ rpId: 'acme.idp.test', discoverable: true }])

    await context.clearCookies()
    await page.goto(`${ACME}/login/`)
    await page.getByRole('button', { name: 'Sign in with a passkey' }).click()
    await atConsole(page, PASSKEY_USER)
    expect(await sessionCookie(context)).toBeDefined()

    await context.clearCookies()
    await page.goto(`${ACME}/login/`)
    await enterLogin(page, PASSKEY_USER)
    await enterPassword(page, password)
    await expect(page.getByText('Confirm with your passkey.')).toBeVisible()
    await page.getByRole('button', { name: 'Use passkey' }).click()
    await atConsole(page, PASSKEY_USER)
    expect(await sessionCookie(context)).toBeDefined()
    expect(violations).toEqual([])

    await page.goto(`${GLOBEX}/login/`)
    await page.getByRole('button', { name: 'Sign in with a passkey' }).click()
    await expect(page.getByRole('alert')).toContainText('did not work')
    expect(await sessionCookie(context, GLOBEX)).toBeUndefined()
  })
})

test.describe('two tenants', () => {
  test('share no flow, session or user', async ({ page, context }) => {
    await page.goto(`${ACME}/login/`)
    await expect(page).toHaveURL(FLOW_URL)
    const flow = FLOW_URL.exec(page.url())?.[1] ?? ''
    expect((await page.request.get(`${ACME}/api/flows/${flow}`)).status()).toBe(200)
    expect((await page.request.get(`${GLOBEX}/api/flows/${flow}`)).status()).toBe(410)
    expect((await page.request.get(`${GLOBEX}/api/flows/${randomUUID()}`)).status()).toBe(410)

    await enterLogin(page, ALICE.login)
    await enterPassword(page, ALICE.password)
    await enterTotp(page, ALICE.totp)
    await page.waitForURL(`${ACME}/console/`)
    const session = await sessionCookie(context)
    if (session === undefined) throw new Error('No session at acme')
    await page.goto(`${ACME}/login/`)
    await page.waitForURL(`${ACME}/console/`)

    await context.addCookies([{ name: session.name, value: session.value, url: GLOBEX, secure: true, httpOnly: true, sameSite: 'Lax' }])
    expect(await sessionCookie(context, GLOBEX)).toMatchObject({ value: session.value })
    await page.goto(`${GLOBEX}/login/`)
    await expect(page.getByLabel('Email')).toBeVisible()
    await expect(page).toHaveURL(FLOW_URL)

    await enterLogin(page, ALICE.login)
    await enterPassword(page, ALICE.password)
    await expect(page.getByRole('alert')).toContainText('did not work')
  })
})
