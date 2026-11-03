import { expect, test, type Response } from '@playwright/test'
import { ACME, ALICE, BOB, enterLogin, enterPassword } from './support/login'
import { deployWhileOpen, navigationType, openedBeforeRelease, pageLoads, PREVIOUS_VERSION } from './support/release'

const FLOW_URL = new RegExp(`^${ACME}/login/\\?flow=[0-9a-f-]{36}$`)

const isCall = (method: string, path: RegExp) => (response: Response) =>
  response.request().method() === method && path.test(new URL(response.url()).pathname)

test.describe('a release while a page is open', () => {
  test('a form opened before the release is sent after it, and the next step comes from the live release', async ({ page }) => {
    const loads = pageLoads(page)
    const deploy = await openedBeforeRelease(page)
    await page.goto(`${ACME}/login/`)
    await enterLogin(page, BOB.login)
    await expect(page.getByLabel('Password')).toBeVisible()
    await expect(page.getByTestId('update-banner')).toBeHidden()

    deploy()
    const [sent, reread] = await Promise.all([
      page.waitForResponse(isCall('POST', /^\/api\/flows\/[^/]+\/password$/)),
      page.waitForResponse(isCall('GET', /^\/api\/flows\/[^/]+$/)),
      enterPassword(page, BOB.password),
    ])
    expect(sent.status()).toBe(200)
    expect(await sent.request().headerValue('x-client-version')).toBe(PREVIOUS_VERSION)

    await expect(page.getByLabel('Code from your authenticator app')).toBeVisible()
    expect(loads()).toBe(2)
    expect(await navigationType(page)).toBe('reload')
    const live = await reread.headerValue('x-app-version')
    expect(live).not.toBe(PREVIOUS_VERSION)
    expect(await reread.request().headerValue('x-client-version')).toBe(live)
    await expect(page.getByTestId('update-banner')).toBeHidden()
  })

  test('a new release shows a banner and loads on the next step, not in the middle of a form', async ({ page }) => {
    const loads = pageLoads(page)
    await page.goto(`${ACME}/login/`)
    await enterLogin(page, ALICE.login)
    await expect(page.getByLabel('Password')).toBeVisible()

    await deployWhileOpen(page)
    await enterPassword(page, 'not-the-password')
    await expect(page.getByRole('alert')).toContainText('did not work')
    await expect(page.getByTestId('update-banner')).toContainText('loads when you go on to the next step')
    await page.getByLabel('Password').fill(ALICE.password)
    await expect(page.getByLabel('Password')).toHaveValue(ALICE.password)
    expect(loads()).toBe(1)

    await page.getByRole('button', { name: 'Sign in' }).click()
    await expect(page.getByLabel('Code from your authenticator app')).toBeVisible()
    expect(loads()).toBe(2)
    expect(await navigationType(page)).toBe('reload')
    await expect(page).toHaveURL(FLOW_URL)
    await expect(page.getByTestId('update-banner')).toBeHidden()
  })

  test('a step the page does not know reloads it at once', async ({ page }) => {
    const loads = pageLoads(page)
    await page.route(
      `${ACME}/api/flows`,
      async (route) => {
        const response = await route.fetch()
        await route.fulfill({ response, json: { ...((await response.json()) as object), step: 'SOMETHING_NEW' } })
      },
      { times: 1 },
    )
    await page.goto(`${ACME}/login/`)

    await expect(page.getByLabel('Email')).toBeVisible()
    expect(loads()).toBe(2)
    expect(await navigationType(page)).toBe('reload')
    await expect(page).toHaveURL(FLOW_URL)
  })

  test('a 426 keeps what the form holds on screen and asks for a reload', async ({ page }) => {
    const loads = pageLoads(page)
    await page.goto(`${ACME}/login/`)
    await page.route(`${ACME}/api/flows/*/login`, (route) => route.fulfill({ status: 426 }), { times: 1 })
    await enterLogin(page, ALICE.login)

    await expect(page.getByRole('alert')).toContainText('out of date')
    await expect(page.getByLabel('Email')).toHaveValue(ALICE.login)
    const banner = page.getByTestId('update-banner')
    await expect(banner).toContainText('Reload it to go on')
    expect(loads()).toBe(1)

    await banner.getByRole('button', { name: 'Reload' }).click()
    await expect.poll(loads).toBe(2)
    await expect(page.getByLabel('Email')).toBeVisible()
    await expect(banner).toBeHidden()
    await enterLogin(page, ALICE.login)
    await expect(page.getByLabel('Password')).toBeVisible()
  })
})
