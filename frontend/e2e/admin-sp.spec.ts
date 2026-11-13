import { expect, test, type APIRequestContext, type Browser, type BrowserContext, type Page } from '@playwright/test'
import { randomBytes } from 'node:crypto'
import { writeFile } from 'node:fs/promises'
import { api, contextApi, metadataOf, registerSp, uniqueSp } from './support/admin'
import {
  ACME,
  acceptInvitation,
  ALICE,
  atConsole,
  BOB,
  enrollTotp,
  enterLogin,
  enterPassword,
  enterRecoveryCode,
  enterTotp,
  GLOBEX,
  newPassword,
  sessionCookie,
  SP_ADMIN,
  SP_WHOAMI,
} from './support/login'
import { linkFromMail } from './support/mail'

const CONSOLE = `${ACME}/console/`
const SP_CARD = /\/console\/admin\/service-providers\/([0-9a-f-]{36})$/
const USER_CARD = /\/console\/admin\/users\/([0-9a-f-]{36})$/
const SAML_METADATA = process.env.SAML_METADATA_DIR ?? '../../dev-tools/simplesamlphp/metadata'
const TEST_SP = 'SimpleSAMLphp'

async function upload(page: Page, name: string, content: string, file = 'metadata.xml') {
  await page.getByLabel('Name', { exact: true }).fill(name)
  await page.getByLabel('Metadata file').setInputFiles({ name: file, mimeType: 'text/xml', buffer: Buffer.from(content) })
  await page.getByRole('button', { name: 'Connect' }).click()
}

/** Leaves no service of the test behind, also when the test failed before its own removal. */
async function removeSp(context: BrowserContext, id: string | null) {
  if (id === null) return
  expect([204, 404]).toContain((await contextApi(context, 'DELETE', `/api/admin/service-providers/${id}`)).status)
}

/**
 * What fetch-idp-metadata.sh does: SimpleSAMLphp trusts the certificates of the file it reads, so during a rotation it gets the
 * metadata with both keys, and after it the one with the new key alone.
 */
async function handMetadataToSimpleSaml(request: APIRequestContext): Promise<string> {
  const metadata = await (await request.get(`${ACME}/saml/metadata`)).text()
  expect(metadata).toContain('EntityDescriptor')
  await writeFile(`${SAML_METADATA}/acme.xml`, metadata)
  return metadata
}

const certificatesIn = (metadata: string) => metadata.match(/<(?:ds:)?X509Certificate>/g)?.length ?? 0

async function signIn(page: Page, password: string, code: string) {
  await page.goto(`${ACME}/login/`)
  await enterLogin(page, SP_ADMIN)
  await enterPassword(page, password)
  await enterRecoveryCode(page, code)
  await atConsole(page, SP_ADMIN)
}

async function newBrowser(browser: Browser) {
  const context = await browser.newContext()
  return { context, page: await context.newPage() }
}

async function openTestSp(page: Page) {
  await page.goto(`${CONSOLE}admin/service-providers`)
  await page.getByTestId('service-provider-item').getByRole('link', { name: TEST_SP, exact: true }).click()
  await expect(page.getByTestId('sp-name')).toHaveText(TEST_SP)
}

/** The SP answers with what it received when the IdP let the user in, and the IdP answers 403 on its own page otherwise. */
async function signInAtTestSp(page: Page): Promise<number> {
  const [resumed] = await Promise.all([
    page.waitForResponse((it) => it.url().startsWith(`${ACME}/saml/resume/`)),
    page.goto(SP_WHOAMI),
  ])
  return resumed.status()
}

/** One admin, invited anew before every run, who connects services, assigns users, rotates the key and reads the journal. */
test.describe.serial('an admin of the tenant', () => {
  let password = ''
  let codes: string[] = []
  let erasedUser = ''
  /** What a test leaves to undo, done after it with the cookies of its context, also when it failed or ran out of time. */
  const undo: (() => Promise<void>)[] = []

  test.afterEach(async () => {
    for (const step of undo.splice(0).reverse()) await step()
  })

  test('connects a service from its metadata, configures it over a change of another admin, and removes it', async ({
    page,
    request,
    context,
  }) => {
    password = await acceptInvitation(page, request, SP_ADMIN)
    codes = (await enrollTotp(page)).codes
    await atConsole(page, SP_ADMIN)
    const sp = uniqueSp()
    let id: string | null = null
    undo.push(() => removeSp(context, id))

    await page.getByTestId('admin-nav').getByRole('link', { name: 'Services' }).click()
    await expect(page).toHaveURL(`${CONSOLE}admin/service-providers`)
    await expect(page.getByTestId('service-provider-item').filter({ hasText: TEST_SP })).toHaveCount(1)

    await upload(page, sp.name, '<html>not metadata</html>')
    await expect(page.getByRole('alert')).toContainText('not valid SAML metadata')
    await upload(page, sp.name, metadataOf(sp.entityId), '../../etc/passwd')
    await expect(page).toHaveURL(SP_CARD)
    id = SP_CARD.exec(page.url())?.[1] ?? null
    await expect(page.getByTestId('admin-notice')).toHaveText(`${sp.name} is connected. Nobody is assigned to it yet, so nobody signs in to it.`)
    await expect(page.getByTestId('sp-entity-id')).toHaveText(sp.entityId)
    await expect(page.getByTestId('sp-acs')).toHaveText([`${sp.entityId}/acs (default)`, `${sp.entityId}/acs2`])
    await expect(page.getByTestId('no-assigned-users')).toBeVisible()
    await expect(page.getByLabel('NameID')).toHaveValue('PERSISTENT')
    await expect(page.getByLabel('Signature algorithm')).toHaveValue('RSA_PSS_SHA256')

    await page.getByRole('link', { name: 'Back to services' }).click()
    await upload(page, `${sp.name} again`, metadataOf(sp.entityId))
    await expect(page.getByRole('alert')).toHaveText('A service with the EntityID of this file is connected already.')
    await expect(page.getByTestId('service-provider-item').filter({ hasText: sp.entityId })).toHaveCount(1)
    await page.getByTestId('service-provider-item').getByRole('link', { name: sp.name, exact: true }).click()
    await expect(page).toHaveURL(`${CONSOLE}admin/service-providers/${id}`)

    const other = await context.newPage()
    await other.goto(page.url())
    await other.getByLabel('Phone number').check()
    await other.getByRole('button', { name: 'Save settings' }).click()
    await expect(other.getByTestId('settings-saved')).toBeVisible()
    await other.close()

    await page.getByLabel('NameID').selectOption('EMAIL')
    await page.getByLabel('Email address', { exact: true }).check()
    const [refused] = await Promise.all([
      page.waitForResponse((it) => it.url().endsWith('/settings') && it.request().method() === 'PUT'),
      page.getByRole('button', { name: 'Save settings' }).click(),
    ])
    expect(refused.status()).toBe(412)
    await expect(page.getByTestId('settings-conflict')).toContainText('Someone else saved the settings')
    await expect(page.getByLabel('NameID')).toHaveValue('EMAIL')
    await page.getByRole('button', { name: 'Load the current settings' }).click()
    await expect(page.getByTestId('settings-conflict')).toHaveCount(0)
    await expect(page.getByLabel('NameID')).toHaveValue('PERSISTENT')
    await expect(page.getByLabel('Phone number')).toBeChecked()
    await page.getByLabel('NameID').selectOption('EMAIL')
    await page.getByLabel('Email address', { exact: true }).check()
    await page.getByRole('button', { name: 'Save settings' }).click()
    await expect(page.getByTestId('settings-saved')).toBeVisible()
    await page.reload()
    await expect(page.getByLabel('NameID')).toHaveValue('EMAIL')
    await expect(page.getByLabel('Email address', { exact: true })).toBeChecked()
    await expect(page.getByLabel('Phone number')).toBeChecked()

    await page.getByTestId('sp-journal').click()
    await expect(page.getByTestId('audit-filter')).toContainText(id ?? '')
    const changes = page.getByTestId('audit-entry').filter({ hasText: 'sp.settings_changed' })
    await expect(changes).toHaveCount(2)
    await expect(changes.first().getByTestId('audit-details')).toContainText('EMAIL')
    await expect(page.getByTestId('audit-entry').filter({ hasText: 'sp.registered' })).toHaveCount(1)
    await expect(page.getByTestId('audit-entry').getByTestId('audit-ip').first()).not.toHaveText('None')
    await expect(page.getByTestId('audit-entry').filter({ hasText: 'passwd' })).toHaveCount(0)
    await page.goBack()

    await page.getByRole('button', { name: 'Remove service' }).click()
    await expect(page.getByTestId('confirmation')).toContainText(`Remove ${sp.name}?`)
    await page.getByRole('button', { name: 'Remove for good' }).click()
    await expect(page).toHaveURL(`${CONSOLE}admin/service-providers`)
    await expect(page.getByTestId('admin-notice')).toHaveText(`${sp.name} is removed. Its users can no longer sign in to it.`)
    await expect(page.getByTestId('service-provider-item').filter({ hasText: sp.entityId })).toHaveCount(0)
    await page.goBack()
    await expect(page.getByTestId('no-such-service-provider')).toBeVisible()
  })

  test('assigns the selected users at once and revokes them at once', async ({ page, context }) => {
    await signIn(page, password, codes[0] ?? '')
    const sp = uniqueSp()
    const id = await registerSp(page, sp.name, sp.entityId)
    undo.push(() => removeSp(context, id))

    await page.goto(`${CONSOLE}admin/service-providers/${id}`)
    await expect(page.getByTestId('sp-name')).toHaveText(sp.name)
    for (const email of [ALICE.login, 'nobody@acme.test', BOB.login.toUpperCase(), ALICE.login]) {
      await page.getByLabel('Email address of a user').fill(email)
      await page.getByRole('button', { name: 'Add', exact: true }).click()
      await expect(page.getByLabel('Email address of a user')).toHaveValue(email === 'nobody@acme.test' ? email : '')
    }
    await expect(page.getByTestId('pick-no-user')).toHaveCount(0)
    await expect(page.getByTestId('picked-user')).toHaveCount(2)

    const [assigned] = await Promise.all([
      page.waitForResponse((it) => it.url().endsWith(`/service-providers/${id}/users`) && it.request().method() === 'POST'),
      page.getByRole('button', { name: 'Assign selected' }).click(),
    ])
    expect(assigned.status()).toBe(204)
    expect((JSON.parse(assigned.request().postData() ?? '{}') as { userIds: string[] }).userIds).toHaveLength(2)
    await expect(page.getByTestId('admin-notice')).toHaveText('2 users can sign in to it now.')
    await expect(page.getByTestId('assigned-user')).toHaveCount(2)

    await page.goto(`${CONSOLE}admin/users`)
    await page.getByLabel('Email address').fill(BOB.login)
    await page.getByRole('button', { name: 'Find' }).click()
    await page.getByTestId('found-user').getByRole('link').click()
    await expect(page).toHaveURL(USER_CARD)
    const bob = USER_CARD.exec(page.url())?.[1] ?? ''
    await expect(page.getByTestId('service-provider').filter({ hasText: sp.name })).toHaveCount(1)
    await page.getByTestId('service-provider').getByRole('link', { name: sp.name }).click()
    await expect(page).toHaveURL(`${CONSOLE}admin/service-providers/${id}`)

    await expect(page.getByTestId('assigned-user')).toHaveCount(2)
    for (const checkbox of await page.getByTestId('assigned-user').getByRole('checkbox').all()) await checkbox.check()
    await page.getByRole('button', { name: 'Revoke selected' }).click()
    await expect(page.getByTestId('admin-notice')).toContainText('2 users can no longer sign in to it')
    await expect(page.getByTestId('no-assigned-users')).toBeVisible()
    await page.goto(`${CONSOLE}admin/users/${bob}`)
    await expect(page.getByTestId('service-provider').filter({ hasText: sp.name })).toHaveCount(0)
  })

  test('lets a new user sign in to the test SP only while they are assigned to it', async ({ page, request, browser, context }) => {
    await signIn(page, password, codes[1] ?? '')
    const invitee = `e2e-${randomBytes(6).toString('hex')}@acme.test`
    let user = ''
    let assigned = false
    undo.push(async () => {
      if (assigned) await contextApi(context, 'POST', `/api/admin/service-providers/${await testSpId(context)}/users/removal`, { userIds: [user] })
      if (user !== '') {
        expect([204, 404]).toContain((await contextApi(context, 'DELETE', `/api/admin/users/${user}`)).status)
        erasedUser = user
      }
    })

    await page.goto(`${CONSOLE}admin/users`)
    await page.getByLabel('Email of the new user').fill(invitee)
    await page.getByRole('button', { name: 'Invite' }).click()
    await expect(page).toHaveURL(USER_CARD)
    user = USER_CARD.exec(page.url())?.[1] ?? ''

    const guest = await newBrowser(browser)
    await guest.page.goto(await linkFromMail(request, { to: invitee, subject: 'You are invited', path: '/invite' }))
    await guest.page.getByLabel('New password').fill(newPassword())
    await guest.page.getByRole('button', { name: 'Set password' }).click()
    await enrollTotp(guest.page)
    await atConsole(guest.page, invitee)
    expect((await api(guest.page, 'GET', '/api/admin/service-providers')).status).toBe(403)

    expect(await signInAtTestSp(guest.page)).toBe(403)
    expect(guest.page.url()).not.toContain('sp.idp.test')

    await openTestSp(page)
    await page.getByLabel('Email address of a user').fill(invitee)
    await page.getByRole('button', { name: 'Add', exact: true }).click()
    await expect(page.getByTestId('picked-user')).toContainText(invitee)
    await page.getByRole('button', { name: 'Assign selected' }).click()
    await expect(page.getByTestId('admin-notice')).toHaveText('1 user can sign in to it now.')
    assigned = true
    await expect(page.getByTestId('assigned-user').filter({ hasText: user })).toHaveCount(1)

    await guest.context.clearCookies({ domain: 'sp.idp.test' })
    expect(await signInAtTestSp(guest.page)).toBe(200)
    await guest.page.waitForURL(SP_WHOAMI)
    expect(await guest.page.locator('body').innerText()).toContain('nameId')

    await page.getByLabel(`User ${user}`).check()
    await page.getByRole('button', { name: 'Revoke selected' }).click()
    await expect(page.getByTestId('admin-notice')).toContainText('1 user can no longer sign in to it')
    assigned = false
    await expect(page.getByTestId('assigned-user').filter({ hasText: user })).toHaveCount(0)

    await guest.context.clearCookies({ domain: 'sp.idp.test' })
    expect(await signInAtTestSp(guest.page)).toBe(403)
    expect(guest.page.url()).not.toContain('sp.idp.test')
    await guest.context.close()
  })

  test('rotates the signing key while SimpleSAMLphp keeps signing users in', async ({ page, request, browser, context }) => {
    await signIn(page, password, codes[2] ?? '')
    undo.push(async () => {
      const keys = await contextApi(context, 'GET', '/api/admin/signing-keys')
      if (keys.body.includes('"NEXT"')) {
        expect((await contextApi(context, 'POST', '/api/admin/signing-keys/rotation/completion')).status).toBe(204)
      }
      expect(certificatesIn(await handMetadataToSimpleSaml(request))).toBe(1)
    })

    await page.getByTestId('admin-nav').getByRole('link', { name: 'Signing keys' }).click()
    await expect(page.getByTestId('metadata-url')).toHaveText(`${ACME}/saml/metadata`)
    await expect(page.getByTestId('signing-key')).toHaveCount(1)
    const before = await page.getByTestId('key-fingerprint').innerText()

    await page.getByRole('button', { name: 'Start rotation' }).click()
    await expect(page.getByTestId('admin-notice')).toContainText('The next key is published')
    await expect(page.getByTestId('signing-key')).toHaveCount(2)
    await expect(page.locator('[data-testid="signing-key"][data-status="ACTIVE"] code')).toHaveText(before)
    const next = await page.locator('[data-testid="signing-key"][data-status="NEXT"] code').innerText()
    expect(next).not.toBe(before)
    const listed = JSON.parse((await api(page, 'GET', '/api/admin/signing-keys')).body) as { keys: { id: string; status: string }[] }
    const nextId = listed.keys.find((it) => it.status === 'NEXT')?.id ?? ''
    expect(nextId).not.toBe('')
    expect(certificatesIn(await handMetadataToSimpleSaml(request))).toBe(2)

    await page.getByRole('button', { name: 'Complete rotation' }).click()
    await expect(page.getByTestId('confirmation')).toContainText('fails every sign-in')
    await page.getByRole('button', { name: 'Complete now' }).click()
    await expect(page.getByTestId('admin-notice')).toHaveText('The next key signs now, and the one before is deleted.')
    await expect(page.getByTestId('signing-key')).toHaveCount(1)
    await expect(page.locator('[data-testid="signing-key"][data-status="ACTIVE"] code')).toHaveText(next)

    const alice = await newBrowser(browser)
    await alice.page.goto(SP_WHOAMI)
    await enterLogin(alice.page, ALICE.login)
    await enterPassword(alice.page, ALICE.password)
    await enterTotp(alice.page, ALICE.totp)
    await alice.page.waitForURL(SP_WHOAMI)
    expect(await alice.page.locator('body').innerText()).toContain(ALICE.login)
    await alice.context.close()

    await page.getByTestId('admin-nav').getByRole('link', { name: 'Journal' }).click()
    const ofThisRotation = page.getByTestId('audit-entry').filter({ hasText: nextId })
    await expect(ofThisRotation.filter({ hasText: 'saml.key_rotation_started' })).toHaveCount(1)
    await expect(ofThisRotation.filter({ hasText: 'saml.key_rotation_completed' })).toHaveCount(1)
  })

  test('reads the journal page by page, by target, and without the data of an erased user', async ({ page }) => {
    await signIn(page, password, codes[3] ?? '')
    // A fresh environment may hold less than one page of the default size, so the pages are made small.
    await page.route(/\/api\/admin\/audit\?/, (route) => {
      const url = new URL(route.request().url())
      url.searchParams.set('limit', '5')
      return route.continue({ url: url.toString() })
    })
    await page.route(/\/api\/admin\/audit$/, (route) => route.continue({ url: `${route.request().url()}?limit=5` }))

    await page.getByTestId('admin-nav').getByRole('link', { name: 'Journal' }).click()
    await expect(page.getByTestId('audit-entry')).toHaveCount(5)
    const first = await page.getByTestId('audit-entry').allInnerTexts()
    await page.getByRole('button', { name: 'Show older records' }).click()
    await expect(page.getByTestId('audit-entry')).toHaveCount(10)
    const both = await page.getByTestId('audit-entry').allInnerTexts()
    expect(both.slice(0, 5)).toEqual(first)
    expect(new Set(both).size).toBeGreaterThan(5)

    await page.getByTestId('audit-entry').getByTestId('audit-target').first().click()
    await expect(page.getByTestId('audit-filter')).toBeVisible()
    await page.getByRole('link', { name: 'Show every record' }).click()
    await expect(page.getByTestId('audit-filter')).toHaveCount(0)

    expect(erasedUser).not.toBe('')
    await page.getByLabel('Target', { exact: true }).selectOption('USER')
    await page.getByLabel('ID of the target').fill(erasedUser)
    await page.getByRole('button', { name: 'Show only this target' }).click()
    await expect(page).toHaveURL(`${CONSOLE}admin/audit?targetType=USER&targetId=${erasedUser}`)
    await expect(page.getByTestId('audit-entry').filter({ hasText: 'user.erased' })).toHaveCount(1)
    await expect(page.getByTestId('audit-erased').first()).toHaveText('Removed with the data of the user who acted')

    await page.getByLabel('ID of the target').fill('not-an-id')
    await page.getByRole('button', { name: 'Show only this target' }).click()
    await expect(page.getByTestId('audit-refused')).toBeVisible()
  })

  test('is no admin at another tenant, even with the session of this one', async ({ page, context }) => {
    await signIn(page, password, codes[4] ?? '')
    expect((await api(page, 'GET', '/api/admin/service-providers')).status).toBe(200)
    const session = await sessionCookie(context)
    if (session === undefined) throw new Error('No session at acme')
    await context.addCookies([{ name: session.name, value: session.value, url: GLOBEX, secure: true, httpOnly: true, sameSite: 'Lax' }])
    await page.goto(`${GLOBEX}/login/`)
    for (const path of ['/api/admin/service-providers', '/api/admin/signing-keys', '/api/admin/audit']) {
      expect((await api(page, 'GET', path)).status).toBe(401)
    }
  })
})

async function testSpId(context: BrowserContext): Promise<string> {
  const listed = JSON.parse((await contextApi(context, 'GET', '/api/admin/service-providers')).body) as {
    serviceProviders: { id: string; name: string }[]
  }
  return listed.serviceProviders.find((it) => it.name === TEST_SP)?.id ?? ''
}
