import { expect, test, type Page } from '@playwright/test'
import { randomUUID } from 'node:crypto'
import { acceptInvitation, cspViolations, enterLogin, enterPassword, saveRecoveryCodes, sessionCookie } from './support/login'
import { virtualAuthenticator } from './support/passkey'

const OPS = 'https://ops.idp.test'
const OPS_SSO = 'https://ops-sso.idp.test'
const OPS_LOGIN = `${OPS}/saml2/authenticate/ops-sso`

/** The first operator, whom the seed assigns to `ops`, and another admin of the service tenant, whom nobody assigns. */
const OPERATOR = 'operator@ops-sso.test'
const OUTSIDER = 'outsider@ops-sso.test'

/** `ops` answers 401 without a session, and a signed-in operator learns that no such tenant exists. */
async function opsSession(page: Page): Promise<'signed-in' | 'anonymous'> {
  const status = (await page.request.get(`${OPS}/api/tenants/${randomUUID()}`)).status()
  expect([401, 404]).toContain(status)
  return status === 404 ? 'signed-in' : 'anonymous'
}

/** The service tenant asks for a passkey, so the step offers nothing else to register. */
async function registerPasskey(page: Page) {
  await expect(page.getByRole('button', { name: 'Register a passkey' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Set up an authenticator app' })).toHaveCount(0)
  await page.getByRole('button', { name: 'Register a passkey' }).click()
  await saveRecoveryCodes(page)
  await page.waitForURL(`${OPS_SSO}/console/`)
}

test.describe('the login of ops', () => {
  test('the first operator registers a passkey and signs in to ops with it', async ({ page, request, context }) => {
    const violations = cspViolations(page)
    const authenticator = await virtualAuthenticator(page)
    const password = await acceptInvitation(page, request, OPERATOR)
    await registerPasskey(page)
    expect(await authenticator.passkeys()).toEqual([{ rpId: 'ops-sso.idp.test', discoverable: true }])
    expect(await opsSession(page)).toBe('anonymous')

    await page.goto(OPS_LOGIN)
    await page.waitForURL(`${OPS}/`)
    expect(await opsSession(page)).toBe('signed-in')

    await context.clearCookies()
    expect(await opsSession(page)).toBe('anonymous')
    await page.goto(OPS_LOGIN)
    await expect(page).toHaveURL(new RegExp(`^${OPS_SSO}/login/\\?flow=[0-9a-f-]{36}$`))
    await enterLogin(page, OPERATOR)
    await enterPassword(page, password)
    await expect(page.getByText('Confirm with your passkey.')).toBeVisible()
    await expect(page.getByRole('button', { name: /instead$/ })).toHaveCount(0)
    await page.getByRole('button', { name: 'Use passkey' }).click()
    await page.waitForURL(`${OPS}/`)
    expect(await opsSession(page)).toBe('signed-in')
    expect(violations).toEqual([])
  })

  test('an admin of the service tenant who is not assigned to ops proves a passkey and is still refused', async ({
    page,
    request,
    context,
  }) => {
    await virtualAuthenticator(page)
    await acceptInvitation(page, request, OUTSIDER)
    await registerPasskey(page)
    expect(await sessionCookie(context, OPS_SSO)).toBeDefined()

    const [refusal] = await Promise.all([
      page.waitForResponse((it) => it.url().startsWith(`${OPS_SSO}/saml/resume/`)),
      page.goto(OPS_LOGIN),
    ])
    expect(refusal.status()).toBe(403)
    await page.waitForURL(new RegExp(`^${OPS_SSO}/saml/resume/`))
    expect(await opsSession(page)).toBe('anonymous')
  })
})
