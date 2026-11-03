import { expect, test } from '@playwright/test'
import { randomBytes } from 'node:crypto'
import {
  ACME,
  ALICE,
  BOB,
  cspViolations,
  enterLogin,
  enterPassword,
  enterTotp,
  INVITEE,
  paste,
  sessionCookie,
  SP_WHOAMI,
} from './support/login'
import { linkFromMail } from './support/mail'
import { totp } from './support/totp'

/** Hex has none of the words the password policy refuses, such as parts of the address or the product name. */
const newPassword = () => randomBytes(16).toString('hex')

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
