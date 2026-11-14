import { expect, type Page } from '@playwright/test'
import { saveRecoveryCodes } from './login'

export const OPS = 'https://ops.idp.test'
export const OPS_SSO = 'https://ops-sso.idp.test'
export const OPS_LOGIN = `${OPS}/saml2/authenticate/ops-sso`

/** The service tenant asks for a passkey, so the step offers nothing else to register. */
export async function registerPasskey(page: Page) {
  await expect(page.getByRole('button', { name: 'Register a passkey' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Set up an authenticator app' })).toHaveCount(0)
  await page.getByRole('button', { name: 'Register a passkey' }).click()
  await saveRecoveryCodes(page)
  await page.waitForURL(`${OPS_SSO}/console/`)
}

export interface OpsAnswer {
  status: number
  body: string
}

/** A call of the operator API with the session of the page, which echoes the CSRF cookie of `ops` as its UI will. */
export async function opsApi(page: Page, method: string, path: string, body?: unknown): Promise<OpsAnswer> {
  const csrf = (await page.context().cookies(OPS)).find((it) => it.name === 'XSRF-TOKEN')?.value
  const response = await page.request.fetch(`${OPS}${path}`, {
    method,
    headers: { Accept: 'application/json', ...(csrf === undefined ? {} : { 'X-XSRF-TOKEN': csrf }) },
    data: body,
  })
  return { status: response.status(), body: await response.text() }
}
