import { expect, test, type Browser, type BrowserContext, type Page } from '@playwright/test'
import { randomInt } from 'node:crypto'
import { api, registerSp, uniqueSp, userIdOf } from './support/admin'
import {
  acceptInvitation,
  atConsole,
  enrollTotp,
  enterLogin,
  enterPassword,
  enterRecoveryCode,
  newPassword,
  sessionCookie,
} from './support/login'
import { linkFromMail, mailIds } from './support/mail'
import { OPS, OPS_LOGIN, OPS_SSO, opsApi, registerPasskey } from './support/ops'
import { virtualAuthenticator } from './support/passkey'
import { authnRequest, samlResponseOf, serveAcs, type TestSp } from './support/saml'

/** An admin of the service tenant, invited anew before every run, who makes themselves an operator for this spec. */
const OPERATOR = 'onboarding@ops-sso.test'

/** Letters outside hex, so no password of `newPassword` holds a word of the tenant. */
const randomName = () => Array.from({ length: 10 }, () => 'ghjkmnpqrstvwxyz'.charAt(randomInt(16))).join('')

interface NewTenant {
  subdomain: string
  origin: string
  admin: string
  application: string
  id: string
}

function newTenant(): NewTenant {
  const subdomain = `onb-${randomName()}`
  return { subdomain, origin: `https://${subdomain}.idp.test`, admin: `first-admin@${subdomain}.test`, application: '', id: '' }
}

interface Account {
  page: Page
  email: string
  id: string
  password: string
  codes: string[]
}

/** The browsers of the people in the spec, which outlive a test, so a later test continues their sessions. */
const opened: BrowserContext[] = []

async function newPage(browser: Browser): Promise<Page> {
  const context = await browser.newContext()
  opened.push(context)
  return context.newPage()
}

const resumed = (page: Page, origin: string) => page.waitForResponse((it) => it.url().startsWith(`${origin}/saml/resume/`))

type Call = [method: string, path: string, body?: unknown]

/** Each call with the status it got, so a failure names the call. */
async function answered(page: Page, calls: Call[]): Promise<string[]> {
  const statuses: string[] = []
  for (const [method, path, body] of calls) statuses.push(`${method} ${path} ${(await api(page, method, path, body)).status}`)
  return statuses
}

const notFound = (calls: Call[]) => calls.map(([method, path]) => `${method} ${path} 404`)

async function cardOf(page: Page, user: string): Promise<unknown> {
  const answer = await api(page, 'GET', `/api/admin/users/${user}`)
  expect(answer.status).toBe(200)
  return JSON.parse(answer.body)
}

const nextCode = (account: Account) => {
  const code = account.codes.shift()
  if (code === undefined) throw new Error(`${account.email} has no recovery code left`)
  return code
}

/**
 * The acceptance criteria end to end on tenants of their own, made anew on every run under random names: the operator enters and
 * approves them, their first admins take them over, and the operator never sees what they hold.
 */
test.describe('a new tenant', () => {
  test.describe.configure({ mode: 'serial', timeout: 180_000 })

  const first = newTenant()
  const second = newTenant()
  const admins = new Map<NewTenant, Account>()
  const member: Account = { page: undefined as unknown as Page, email: '', id: '', password: '', codes: [] }
  let operator: Page
  let operatorId = ''
  let opsSp = ''
  let testSp: TestSp = { entityId: '', acs: '' }
  let testSpId = ''

  const adminOf = (tenant: NewTenant) => {
    const admin = admins.get(tenant)
    if (admin === undefined) throw new Error(`${tenant.subdomain} has no admin yet`)
    return admin
  }

  test.beforeAll(async ({ browser }) => {
    operator = await newPage(browser)
  })

  /** A test that failed before the last one leaves its tenants active, each with a schema and a KEK, unless they go here. */
  test.afterAll(async () => {
    for (const tenant of [first, second]) {
      if (tenant.id === '') continue
      const shown = await opsApi(operator, 'GET', `/api/tenants/${tenant.id}`)
      if (shown.status !== 200) continue
      const { status } = JSON.parse(shown.body) as { status: string }
      if (status === 'ACTIVE') await opsApi(operator, 'POST', `/api/tenants/${tenant.id}/suspend`)
      if (status === 'ACTIVE' || status === 'SUSPENDED') await opsApi(operator, 'POST', `/api/tenants/${tenant.id}/offboarding`)
    }
    if (opsSp !== '' && operatorId !== '') {
      await operator.goto(`${OPS_SSO}/console/`)
      await api(operator, 'POST', `/api/admin/service-providers/${opsSp}/users/removal`, { userIds: [operatorId] })
    }
    for (const context of opened.splice(0)) await context.close()
  })

  test('the operator enters and approves the applications, and sees no personal data of them', async ({ request }) => {
    await virtualAuthenticator(operator)
    await acceptInvitation(operator, request, OPERATOR)
    await registerPasskey(operator)
    operatorId = (await userIdOf(operator, OPERATOR)) ?? ''
    const listed = JSON.parse((await api(operator, 'GET', '/api/admin/service-providers')).body) as {
      serviceProviders: { id: string; entityId: string }[]
    }
    opsSp = listed.serviceProviders.find((it) => it.entityId.startsWith(OPS))?.id ?? ''
    expect((await api(operator, 'POST', `/api/admin/service-providers/${opsSp}/users`, { userIds: [operatorId] })).status).toBe(204)
    await operator.goto(OPS_LOGIN)
    await operator.waitForURL(`${OPS}/`)

    for (const tenant of [first, second]) {
      const submitted = await opsApi(operator, 'POST', '/api/applications', {
        companyName: `Onboarding ${tenant.subdomain}`,
        subdomain: tenant.subdomain,
        contact: tenant.admin,
      })
      expect(submitted.status).toBe(201)
      expect(Object.keys(JSON.parse(submitted.body) as object)).toEqual(['id'])
      tenant.application = (JSON.parse(submitted.body) as { id: string }).id

      const waiting = await opsApi(operator, 'GET', `/api/applications/${tenant.application}`)
      expect(JSON.parse(waiting.body)).toMatchObject({
        subdomain: tenant.subdomain,
        status: 'SUBMITTED',
        contactMask: `f***@${tenant.subdomain}.test`,
      })
      expect(waiting.body).not.toContain('first-admin')
      tenant.id = (JSON.parse(waiting.body) as { tenantId: string }).tenantId
      expect((await opsApi(operator, 'POST', `/api/applications/${tenant.application}/approve`)).status).toBe(204)
    }
    expect((await opsApi(operator, 'POST', `/api/applications/${first.application}/approve`)).status).toBe(422)

    for (const tenant of [first, second]) {
      await expect
        .poll(async () => (JSON.parse((await opsApi(operator, 'GET', `/api/tenants/${tenant.id}`)).body) as { status: string }).status, {
          timeout: 120_000,
          intervals: [1_000],
        })
        .toBe('ACTIVE')
      const shown = await opsApi(operator, 'GET', `/api/tenants/${tenant.id}`)
      expect(Object.keys(JSON.parse(shown.body) as object).sort()).toEqual(['id', 'name', 'offboardingAfter', 'provisioningStep', 'status'])
      const approved = await opsApi(operator, 'GET', `/api/applications/${tenant.application}`)
      expect(JSON.parse(approved.body)).toMatchObject({ status: 'APPROVED', contactMask: null })
      for (const body of [shown.body, approved.body]) {
        expect(body).not.toContain('first-admin')
        expect(body).not.toMatch(/kek|vault|transit|arn:/i)
      }
    }

    const probe = await operator.context().newPage()
    await probe.goto(`${first.origin}/login/`)
    await expect(probe.getByLabel('Email')).toBeVisible()
    expect((await api(probe, 'POST', '/api/admin/users/search', { email: first.admin })).status).toBe(401)
    await probe.close()
  })

  test('the first admin of each accepts the invitation, sets up a second factor and signs in directly', async ({ browser, request }) => {
    for (const tenant of [first, second]) {
      const page = await newPage(browser)
      const password = await acceptInvitation(page, request, tenant.admin)
      expect(page.url().startsWith(`${tenant.origin}/login/`)).toBe(true)
      const { codes } = await enrollTotp(page)
      await atConsole(page, tenant.admin, tenant.origin)
      await expect(page.getByTestId('admin-nav')).toBeVisible()
      admins.set(tenant, { page, email: tenant.admin, id: (await userIdOf(page, tenant.admin)) ?? '', password, codes })
    }

    const admin = adminOf(first)
    await admin.page.context().clearCookies()
    await admin.page.goto(`${first.origin}/login/`)
    await enterLogin(admin.page, admin.email)
    await enterPassword(admin.page, 'not-the-password')
    await expect(admin.page.getByRole('alert')).toContainText('did not work')
    await enterPassword(admin.page, admin.password)
    await enterRecoveryCode(admin.page, nextCode(admin))
    await atConsole(admin.page, admin.email, first.origin)
    expect(await sessionCookie(admin.page.context(), first.origin)).toMatchObject({ httpOnly: true, secure: true })
  })

  test('a user signs in to a service of the tenant only while the admin assigns them to it', async ({ browser, request }) => {
    const admin = adminOf(first)
    const sp = uniqueSp()
    testSp = { entityId: sp.entityId, acs: `${sp.entityId}/acs` }
    testSpId = await registerSp(admin.page, sp.name, sp.entityId)

    member.email = `member-${randomName()}@${first.subdomain}.test`
    const created = await api(admin.page, 'POST', '/api/admin/users', { email: member.email, role: 'USER' })
    expect(created.status).toBe(201)
    member.id = (JSON.parse(created.body) as { id: string }).id
    member.page = await newPage(browser)
    const link = await linkFromMail(request, { to: member.email, subject: 'You are invited', path: '/invite' })
    await member.page.goto(link)
    member.password = newPassword()
    await member.page.getByLabel('New password').fill(member.password)
    await member.page.getByRole('button', { name: 'Set password' }).click()
    member.codes = (await enrollTotp(member.page)).codes
    await atConsole(member.page, member.email, first.origin)
    expect((await api(member.page, 'GET', '/api/admin/service-providers')).status).toBe(403)

    await member.page.context().clearCookies()
    await serveAcs(member.page, testSp)
    await member.page.goto(authnRequest(first.origin, testSp).url)
    await expect(member.page).toHaveURL(new RegExp(`^${first.origin}/login/\\?flow=[0-9a-f-]{36}$`))
    await enterLogin(member.page, member.email)
    await enterPassword(member.page, member.password)
    const [refused] = await Promise.all([resumed(member.page, first.origin), enterRecoveryCode(member.page, nextCode(member))])
    expect(refused.status()).toBe(403)
    expect(member.page.url()).not.toContain(testSp.acs)

    const assignment = `/api/admin/service-providers/${testSpId}/users`
    expect((await api(admin.page, 'POST', assignment, { userIds: [member.id] })).status).toBe(204)
    const allowed = authnRequest(first.origin, testSp)
    const [posted] = await Promise.all([member.page.waitForRequest(testSp.acs), member.page.goto(allowed.url)])
    const xml = samlResponseOf(posted)
    expect(xml).toContain(`InResponseTo="${allowed.id}"`)
    expect(xml).toContain(`Destination="${testSp.acs}"`)
    expect(xml).toContain('urn:oasis:names:tc:SAML:2.0:status:Success')
    expect(xml).toContain(`${first.origin}/saml/metadata`)
    expect(xml).toMatch(/<(\w+:)?NameID\b/)
    expect(xml).toMatch(/<(\w+:)?Signature\b/)

    expect((await api(admin.page, 'POST', `${assignment}/removal`, { userIds: [member.id] })).status).toBe(204)
    const revoking = authnRequest(first.origin, testSp)
    const [revoked] = await Promise.all([resumed(member.page, first.origin), member.page.goto(revoking.url)])
    expect(revoked.status()).toBe(403)

    const elsewhere = await newPage(browser)
    await serveAcs(elsewhere, testSp)
    const [unknown] = await Promise.all([
      elsewhere.waitForResponse((it) => it.url().startsWith(`${second.origin}/saml/sso`)),
      elsewhere.goto(authnRequest(second.origin, testSp).url),
    ])
    expect(unknown.status()).toBe(400)
    await elsewhere.context().close()
  })

  test('an admin manages the users and services of their own tenant only', async ({ browser }) => {
    const own = adminOf(first)
    const other = adminOf(second)
    const outsider = `member-${randomName()}@${second.subdomain}.test`
    const created = await api(other.page, 'POST', '/api/admin/users', { email: outsider, role: 'USER' })
    expect(created.status).toBe(201)
    const outsiderId = (JSON.parse(created.body) as { id: string }).id

    expect((await api(own.page, 'GET', `/api/admin/users/${member.id}`)).status).toBe(200)
    expect((await api(own.page, 'GET', `/api/admin/service-providers/${testSpId}`)).status).toBe(200)
    expect(await userIdOf(own.page, outsider)).toBeUndefined()
    expect(await userIdOf(other.page, member.email)).toBeUndefined()

    const intoSecond: Call[] = [
      ['GET', `/api/admin/users/${outsiderId}`],
      ['POST', `/api/admin/users/${outsiderId}/invitation`],
      ['PUT', `/api/admin/users/${outsiderId}/role`, { role: 'TENANT_ADMIN' }],
      ['POST', `/api/admin/users/${outsiderId}/block`],
      ['POST', `/api/admin/users/${outsiderId}/mfa-reset`],
      ['DELETE', `/api/admin/users/${outsiderId}/sessions`],
      ['DELETE', `/api/admin/users/${other.id}`],
      ['POST', `/api/admin/service-providers/${testSpId}/users`, { userIds: [outsiderId] }],
    ]
    expect(await answered(own.page, intoSecond)).toEqual(notFound(intoSecond))

    const intoFirst: Call[] = [
      ['GET', `/api/admin/users/${member.id}`],
      ['POST', `/api/admin/users/${member.id}/block`],
      ['DELETE', `/api/admin/users/${member.id}`],
      ['GET', `/api/admin/service-providers/${testSpId}`],
      ['GET', `/api/admin/service-providers/${testSpId}/users`],
      ['POST', `/api/admin/service-providers/${testSpId}/users/removal`, { userIds: [member.id] }],
      ['DELETE', `/api/admin/service-providers/${testSpId}`],
    ]
    expect(await answered(other.page, intoFirst)).toEqual(notFound(intoFirst))

    expect(await cardOf(other.page, outsiderId)).toMatchObject({ role: 'USER', status: 'INVITED' })
    expect(await cardOf(own.page, member.id)).toMatchObject({ role: 'USER', status: 'ACTIVE' })
    expect((await api(own.page, 'GET', `/api/admin/service-providers/${testSpId}`)).status).toBe(200)

    const session = await sessionCookie(own.page.context(), first.origin)
    if (session === undefined) throw new Error(`No session at ${first.subdomain}`)
    const thief = await newPage(browser)
    const { name, value } = session
    await thief.context().addCookies([{ name, value, url: second.origin, secure: true, httpOnly: true, sameSite: 'Lax' }])
    await thief.goto(`${second.origin}/login/`)
    await expect(thief.getByLabel('Email')).toBeVisible()
    for (const path of [`/api/admin/users/${outsiderId}`, '/api/admin/service-providers', '/api/admin/audit']) {
      expect((await api(thief, 'GET', path)).status).toBe(401)
    }
    await thief.context().close()
  })

  test('a user who lost the password and every factor resets the password and gets new factors from the admin', async ({ request }) => {
    const page = member.page
    await page.context().clearCookies()
    await page.goto(`${first.origin}/login/`)
    await enterLogin(page, member.email)
    await page.getByRole('link', { name: 'Forgot your password?' }).click()
    const earlier = await mailIds(request, member.email, 'Reset your')
    await page.getByLabel('Email').fill(member.email)
    await page.getByRole('button', { name: 'Send link' }).click()
    await expect(page.getByTestId('reset-requested')).toBeVisible()
    await page.goto(await linkFromMail(request, { to: member.email, subject: 'Reset your', path: '/reset-password', skip: earlier }))
    const password = newPassword()
    await page.getByLabel('New password').fill(password)
    await page.getByRole('button', { name: 'Set password' }).click()
    await expect(page.getByTestId('password-changed')).toBeVisible()

    await page.goto(`${first.origin}/login/`)
    await enterLogin(page, member.email)
    await enterPassword(page, password)
    await expect(page.getByLabel('Code from your authenticator app')).toBeVisible()
    await expect(page.getByRole('button', { name: 'Set up an authenticator app' })).toHaveCount(0)

    expect((await api(adminOf(first).page, 'POST', `/api/admin/users/${member.id}/mfa-reset`)).status).toBe(204)
    await page.goto(`${first.origin}/login/`)
    await enterLogin(page, member.email)
    await enterPassword(page, password)
    await enrollTotp(page)
    await atConsole(page, member.email, first.origin)
  })

  test('the only admin who lost everything gets back in through the operator, who learns nothing of the tenant', async ({ request }) => {
    const admin = adminOf(first)
    const earlier = await mailIds(request, first.admin, 'You are invited')
    const answer = await opsApi(operator, 'POST', `/api/tenants/${first.id}/admin-reinvite`, { contact: first.admin })
    expect(answer.status).toBe(202)
    expect(answer.body).toBe('')
    const link = await linkFromMail(request, { to: first.admin, subject: 'You are invited', path: '/invite', skip: earlier })
    await expect.poll(async () => (await api(admin.page, 'GET', `/api/admin/users/${member.id}`)).status).toBe(401)

    await admin.page.context().clearCookies()
    await admin.page.goto(`${first.origin}/login/`)
    await enterLogin(admin.page, admin.email)
    await enterPassword(admin.page, admin.password)
    await expect(admin.page.getByRole('alert')).toContainText('did not work')

    await admin.page.goto(link)
    await admin.page.getByLabel('New password').fill(newPassword())
    await admin.page.getByRole('button', { name: 'Set password' }).click()
    await enrollTotp(admin.page)
    await atConsole(admin.page, admin.email, first.origin)
    expect(await cardOf(admin.page, admin.id)).toMatchObject({ role: 'TENANT_ADMIN', status: 'ACTIVE' })
    expect((await api(admin.page, 'GET', `/api/admin/users/${member.id}`)).status).toBe(200)
  })

  test('the operator suspends both and starts their offboarding', async () => {
    for (const tenant of [first, second]) {
      expect((await opsApi(operator, 'POST', `/api/tenants/${tenant.id}/suspend`)).status).toBe(204)
      expect((await opsApi(operator, 'POST', `/api/tenants/${tenant.id}/offboarding`)).status).toBe(202)
      expect(JSON.parse((await opsApi(operator, 'GET', `/api/tenants/${tenant.id}`)).body)).toMatchObject({ status: 'OFFBOARDING' })
    }
  })
})
