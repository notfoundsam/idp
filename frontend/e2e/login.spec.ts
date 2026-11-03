import { expect, test } from '@playwright/test'
import { ACME, ALICE, BOB, cspViolations, enterLogin, enterPassword, enterTotp, SP_WHOAMI } from './support/login'

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
