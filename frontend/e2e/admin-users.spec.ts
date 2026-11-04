import { expect, test, type Browser, type Page } from '@playwright/test'
import { randomBytes } from 'node:crypto'
import {
  acceptInvitation,
  ACME,
  ADMIN_USER,
  ALICE,
  atConsole,
  enrollTotp,
  enterLogin,
  enterPassword,
  enterRecoveryCode,
  sessionCookie,
} from './support/login'
import { linkFromMail } from './support/mail'

const CONSOLE = `${ACME}/console/`
const LOGIN = /\/login\/(\?flow=[0-9a-f-]{36})?$/
const CARD = /\/console\/admin\/users\/[0-9a-f-]{36}$/

/** The policy `acme` starts with locally, which the other specs rely on: a second factor for users, no passkeys required. */
const POLICY = { mfaForUsers: true, passkeyRequired: false, sessionIdleSeconds: 1800, sessionAbsoluteSeconds: 43200 }

async function adminStatus(page: Page, path: string): Promise<number> {
  return page.evaluate(async (target) => (await fetch(target, { headers: { Accept: 'application/json' } })).status, path)
}

/** Through the API with the session of the page, so a test that fails halfway leaves the policy of `acme` as it found it. */
async function restorePolicy(page: Page): Promise<number> {
  return page.evaluate(async (policy) => {
    const csrf = document.cookie
      .split('; ')
      .find((it) => it.startsWith('__Host-csrf='))
      ?.slice('__Host-csrf='.length)
    const read = await fetch('/api/admin/policy')
    const saved = await fetch('/api/admin/policy', {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json', 'If-Match': read.headers.get('ETag') ?? '', 'X-CSRF-Token': csrf ?? '' },
      body: JSON.stringify(policy),
    })
    return saved.status
  }, POLICY)
}

async function card(page: Page) {
  await page.reload()
  await expect(page.getByTestId('user-email')).toBeVisible()
}

/** The new user signs in with a fresh browser, and lands either in the console or at a step of the policy. */
async function newBrowser(browser: Browser) {
  const context = await browser.newContext()
  return { context, page: await context.newPage() }
}

/** One admin, invited anew before every run, who manages a user invited by the test itself, and then the policy. */
test.describe.serial('an admin of the tenant', () => {
  let password = ''
  let codes: string[] = []

  test('finds users by the whole address, invites, changes, blocks, resets and erases one', async ({ page, request, browser }) => {
    password = await acceptInvitation(page, request, ADMIN_USER)
    codes = (await enrollTotp(page)).codes
    await atConsole(page, ADMIN_USER)

    await page.getByRole('link', { name: 'Users' }).click()
    await expect(page).toHaveURL(`${CONSOLE}admin/users`)
    await page.getByLabel('Email address').fill(ALICE.login.toUpperCase())
    await page.getByRole('button', { name: 'Find' }).click()
    await expect(page.getByTestId('found-user')).toContainText('User')
    await expect(page.getByTestId('found-user')).toContainText('Active')
    await page.getByLabel('Email address').fill('alice@acme')
    await page.getByRole('button', { name: 'Find' }).click()
    await expect(page.getByTestId('no-user')).toHaveText('No user has the address alice@acme.')
    await expect(page.getByTestId('found-user')).toHaveCount(0)

    await page.getByLabel('Email of the new user').fill(ALICE.login)
    await page.getByRole('button', { name: 'Invite' }).click()
    await expect(page.getByRole('alert')).toContainText('already has it')

    const invitee = `e2e-${randomBytes(6).toString('hex')}@acme.test`
    await page.getByLabel('Email of the new user').fill(invitee)
    await page.getByLabel('Role', { exact: true }).selectOption('USER')
    await page.getByRole('button', { name: 'Invite' }).click()
    await expect(page).toHaveURL(CARD)
    await expect(page.getByTestId('admin-notice')).toHaveText(`An invitation is on its way to ${invitee}.`)
    await expect(page.getByTestId('user-email')).toHaveText(invitee)
    await expect(page.getByTestId('user-status')).toContainText('Invited')
    await expect(page.getByTestId('user-event').filter({ hasText: 'user.created' })).toHaveCount(1)
    const resent = Date.now()
    await page.getByRole('button', { name: 'Send the invitation again' }).click()
    await expect(page.getByTestId('admin-notice')).toContainText('A new invitation is on its way')

    const user = await newBrowser(browser)
    const link = await linkFromMail(request, { to: invitee, subject: 'You are invited', path: '/invite', since: resent - 1_000 })
    const userPassword = randomBytes(16).toString('hex')
    await user.page.goto(link)
    await user.page.getByLabel('New password').fill(userPassword)
    await user.page.getByRole('button', { name: 'Set password' }).click()
    await enrollTotp(user.page)
    await atConsole(user.page, invitee)
    await expect(user.page.getByTestId('admin-nav')).toHaveCount(0)
    await user.page.goto(`${CONSOLE}admin/users`)
    await expect(user.page.getByTestId('admins-only')).toBeVisible()
    expect(await adminStatus(user.page, '/api/admin/policy')).toBe(403)

    await card(page)
    await expect(page.getByTestId('user-status')).toHaveText('Active')
    await expect(page.getByRole('button', { name: 'Send the invitation again' })).toHaveCount(0)
    await page.getByLabel('Role', { exact: true }).selectOption('TENANT_ADMIN')
    await page.getByRole('button', { name: 'Change role' }).click()
    await expect(page.getByTestId('user-role')).toHaveText('Admin')
    await user.page.goto(CONSOLE)
    await expect(user.page.getByTestId('admin-nav')).toBeVisible()
    await page.getByLabel('Role', { exact: true }).selectOption('USER')
    await page.getByRole('button', { name: 'Change role' }).click()
    await expect(page.getByTestId('user-role')).toHaveText('User')

    await page.getByRole('button', { name: 'Block' }).click()
    await expect(page.getByTestId('user-status')).toHaveText('Blocked')
    await expect(page.getByTestId('user-event').filter({ hasText: 'user.blocked' })).toHaveCount(1)
    await user.page.goto(CONSOLE)
    await user.page.waitForURL(LOGIN)
    await page.getByRole('button', { name: 'Unblock' }).click()
    await expect(page.getByTestId('user-status')).toHaveText('Active')

    await page.getByRole('button', { name: 'Reset MFA' }).click()
    await page.getByRole('button', { name: 'Remove their factors' }).click()
    await expect(page.getByTestId('admin-notice')).toContainText('set up new ones at their next sign-in')
    await expect(page.getByTestId('user-event').filter({ hasText: 'user.mfa_reset' })).toHaveCount(1)
    await user.page.goto(`${ACME}/login/`)
    await enterLogin(user.page, invitee)
    await enterPassword(user.page, userPassword)
    await expect(user.page.getByRole('button', { name: 'Set up an authenticator app' })).toBeVisible()
    await user.context.close()

    const cardUrl = page.url()
    await page.getByRole('button', { name: 'Erase user' }).click()
    await expect(page.getByTestId('confirmation')).toContainText(`Erase ${invitee} for good?`)
    await page.getByRole('button', { name: 'Erase for good' }).click()
    await expect(page).toHaveURL(`${CONSOLE}admin/users`)
    await expect(page.getByTestId('admin-notice')).toHaveText(`${invitee} is erased.`)
    await page.getByLabel('Email address').fill(invitee)
    await page.getByRole('button', { name: 'Find' }).click()
    await expect(page.getByTestId('no-user')).toBeVisible()
    await page.goBack()
    await expect(page).toHaveURL(cardUrl)
    await expect(page.getByTestId('no-such-user')).toBeVisible()
    await expect(page.getByTestId('user-email')).toHaveCount(0)
  })

  test('saves the policy, refuses timeouts out of bounds, and offers to load it again on 412', async ({ page, context }) => {
    await page.goto(`${ACME}/login/`)
    await enterLogin(page, ADMIN_USER)
    await enterPassword(page, password)
    await enterRecoveryCode(page, codes[0] ?? '')
    await atConsole(page, ADMIN_USER)
    try {
      await page.getByRole('link', { name: 'Policy' }).click()
      await expect(page).toHaveURL(`${CONSOLE}admin/policy`)
      await expect(page.getByLabel('Users sign in with a second factor')).toBeChecked()
      await expect(page.getByLabel('Everyone signs in with a passkey')).not.toBeChecked()
      await expect(page.getByLabel('Idle timeout, minutes')).toHaveValue('30')
      await expect(page.getByLabel('Session lifetime, minutes')).toHaveValue('720')
      await expect(page.getByText('Admins always sign in with a second factor')).toBeVisible()

      await page.getByLabel('Session lifetime, minutes').fill('10')
      await page.getByRole('button', { name: 'Save policy' }).click()
      await expect(page.getByRole('alert')).toContainText('from the idle timeout to 1440 minutes')
      await page.getByLabel('Session lifetime, minutes').fill('720')

      const other = await context.newPage()
      await other.goto(`${CONSOLE}admin/policy`)
      await other.getByLabel('Idle timeout, minutes').fill('25')
      await other.getByRole('button', { name: 'Save policy' }).click()
      await expect(other.getByTestId('policy-saved')).toBeVisible()
      await other.close()

      await page.getByLabel('Session lifetime, minutes').fill('600')
      const [refused] = await Promise.all([
        page.waitForResponse((it) => it.url().endsWith('/api/admin/policy') && it.request().method() === 'PUT'),
        page.getByRole('button', { name: 'Save policy' }).click(),
      ])
      expect(refused.status()).toBe(412)
      await expect(page.getByTestId('policy-conflict')).toContainText('Someone else saved the policy after you opened it')
      await expect(page.getByTestId('policy-saved')).toHaveCount(0)
      await expect(page.getByLabel('Session lifetime, minutes')).toHaveValue('600')
      await page.getByRole('button', { name: 'Load the current policy' }).click()
      await expect(page.getByTestId('policy-conflict')).toHaveCount(0)
      await expect(page.getByLabel('Idle timeout, minutes')).toHaveValue('25')
      await expect(page.getByLabel('Session lifetime, minutes')).toHaveValue('720')
      await page.getByLabel('Session lifetime, minutes').fill('600')
      await page.getByRole('button', { name: 'Save policy' }).click()
      await expect(page.getByTestId('policy-saved')).toBeVisible()
      await page.reload()
      await expect(page.getByLabel('Idle timeout, minutes')).toHaveValue('25')
      await expect(page.getByLabel('Session lifetime, minutes')).toHaveValue('600')
    } finally {
      expect(await restorePolicy(page)).toBe(204)
    }
  })

  test('signs out everyone of the tenant, themselves too', async ({ page, context, browser }) => {
    const user = await newBrowser(browser)
    await user.page.goto(`${ACME}/login/`)
    await enterLogin(user.page, ADMIN_USER)
    await enterPassword(user.page, password)
    await enterRecoveryCode(user.page, codes[1] ?? '')
    await atConsole(user.page, ADMIN_USER)

    await page.goto(`${ACME}/login/`)
    await enterLogin(page, ADMIN_USER)
    await enterPassword(page, password)
    await enterRecoveryCode(page, codes[2] ?? '')
    await atConsole(page, ADMIN_USER)
    await page.getByRole('link', { name: 'Users' }).click()
    await page.getByRole('button', { name: 'Sign everyone out' }).click()
    await page.getByRole('button', { name: 'Sign everyone out now' }).click()
    await expect(page.getByTestId('signed-out')).toBeVisible()
    expect(await sessionCookie(context)).toBeUndefined()

    await user.page.goto(CONSOLE)
    await user.page.waitForURL(LOGIN)
    await user.context.close()
  })
})
