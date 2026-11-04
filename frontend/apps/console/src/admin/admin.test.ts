import type { UserCard, VersionedPolicy } from '@idp/api-client'
import type { VueWrapper } from '@vue/test-utils'
import { afterEach, describe, expect, test } from 'vitest'
import { adminSessions, button, click, consoleAt, factorsWith, hasButton, refused, sessionsOf, settle, type Operation } from '../testing'
import { INVITATION_REFUSED, LAST_ADMIN, TIMEOUTS_REFUSED } from './admin'

let mounted: VueWrapper | null = null

afterEach(() => {
  mounted?.unmount()
  mounted = null
})

const ADMIN_OPERATIONS: Operation[] = [
  'searchUsers',
  'inviteUser',
  'getUser',
  'resendInvitation',
  'changeRole',
  'blockUser',
  'unblockUser',
  'resetMfa',
  'eraseUser',
  'endUserSessions',
  'endAllSessions',
  'getPolicy',
  'savePolicy',
  'listServiceProviders',
  'registerServiceProvider',
  'getServiceProvider',
  'saveServiceProviderSettings',
  'removeServiceProvider',
  'listAssignedUsers',
  'assignUsers',
  'unassignUsers',
  'listSigningKeys',
  'startKeyRotation',
  'completeKeyRotation',
  'readAuditJournal',
]

const ID = '5d2c8a90-7b1e-4f43-9c55-0e6a3b1f2d47'

const cardWith = (overrides: Partial<UserCard> = {}): UserCard => ({
  id: ID,
  email: 'carol@acme.test',
  name: null,
  role: 'USER',
  status: 'ACTIVE',
  serviceProviders: [],
  events: [],
  ...overrides,
})

const policyOf = (version: string, overrides: Partial<VersionedPolicy['policy']> = {}): VersionedPolicy => ({
  policy: { mfaForUsers: true, passkeyRequired: false, sessionIdleSeconds: 1800, sessionAbsoluteSeconds: 43200, ...overrides },
  version,
})

async function asAdmin(path: string) {
  const page = await consoleAt(path)
  page.answer('getSessions', adminSessions())
  page.answer('getFactors', factorsWith())
  return page
}

async function start(page: Awaited<ReturnType<typeof consoleAt>>) {
  mounted = await page.start()
  return mounted
}

async function submit(wrapper: VueWrapper, form: string) {
  await wrapper.get(`[data-testid="${form}"]`).trigger('submit')
  await settle()
}

const notice = (wrapper: VueWrapper) => wrapper.get('[data-testid="admin-notice"]').text()

describe('the admin pages', () => {
  test('are offered to an admin from every page', async () => {
    const wrapper = await start(await asAdmin('/sessions'))
    const links = wrapper.get('[data-testid="admin-nav"]').findAll('a')
    expect(links.map((it) => [it.text(), it.attributes('href')])).toEqual([
      ['Users', '/console/admin/users'],
      ['Policy', '/console/admin/policy'],
      ['Services', '/console/admin/service-providers'],
      ['Signing keys', '/console/admin/signing-keys'],
      ['Journal', '/console/admin/audit'],
    ])
  })

  test('lead to the users first', async () => {
    const page = await asAdmin('/admin')
    await start(page)
    expect(page.router.currentRoute.value.name).toBe('users')
  })

  test.each([
    '/admin/users',
    `/admin/users/${ID}`,
    '/admin/policy',
    '/admin/service-providers',
    `/admin/service-providers/${ID}`,
    '/admin/signing-keys',
    `/admin/audit?targetType=USER&targetId=${ID}`,
  ])(
    'are hidden from a user who is no admin, and %s asks the admin API nothing',
    async (path) => {
      const page = await consoleAt(path)
      page.answer('getSessions', sessionsOf())
      const wrapper = await start(page)
      expect(wrapper.find('[data-testid="admin-nav"]').exists()).toBe(false)
      expect(wrapper.get('[data-testid="admins-only"]').text()).toContain('for the admins')
      expect(page.calls.filter((it) => ADMIN_OPERATIONS.includes(it.operation as Operation))).toEqual([])
    },
  )

  test('a refusal of the API is shown, since the role may have changed since the page was read', async () => {
    const page = await asAdmin('/admin/policy')
    page.answer('getPolicy', refused(403))
    const wrapper = await start(page)
    expect(wrapper.get('[role="alert"]').text()).toContain('Something went wrong')
    expect(wrapper.find('[data-testid="policy-form"]').exists()).toBe(false)
  })
})

describe('users', () => {
  test('are found only by the whole address, which goes to the API as typed without spaces', async () => {
    const page = await asAdmin('/admin/users')
    page.answer('searchUsers', [{ id: ID, role: 'TENANT_ADMIN', status: 'BLOCKED' }], [])
    const wrapper = await start(page)

    await wrapper.get('#search-email').setValue(' carol@acme.test ')
    await submit(wrapper, 'search-form')
    expect(page.called('searchUsers')[0]?.args).toEqual(['carol@acme.test'])
    const hit = wrapper.get('[data-testid="found-user"]')
    expect(hit.text()).toContain('carol@acme.test')
    expect(hit.text()).toContain('Admin')
    expect(hit.text()).toContain('Blocked')
    expect(hit.get('a').attributes('href')).toBe(`/console/admin/users/${ID}`)

    await wrapper.get('#search-email').setValue('carol@acme')
    await submit(wrapper, 'search-form')
    expect(wrapper.find('[data-testid="found-user"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="no-user"]').text()).toBe('No user has the address carol@acme.')
  })

  test('an invitation leads to the card of the new user, which says the mail is on its way', async () => {
    const page = await asAdmin('/admin/users')
    page.answer('inviteUser', { id: ID })
    page.answer('getUser', cardWith({ status: 'INVITED', role: 'TENANT_ADMIN' }))
    const wrapper = await start(page)

    await wrapper.get('#invite-email').setValue('carol@acme.test')
    await wrapper.get('#invite-role').setValue('TENANT_ADMIN')
    await submit(wrapper, 'invite-form')
    expect(page.called('inviteUser')[0]?.args).toEqual([{ email: 'carol@acme.test', role: 'TENANT_ADMIN' }])
    expect(page.router.currentRoute.value.fullPath).toBe(`/admin/users/${ID}`)
    expect(notice(wrapper)).toBe('An invitation is on its way to carol@acme.test.')
    expect(wrapper.get('[data-testid="user-status"]').text()).toContain('Invited')
  })

  test('an invitation that is refused names why, and the form keeps the address', async () => {
    const page = await asAdmin('/admin/users')
    page.answer('inviteUser', refused(422))
    const wrapper = await start(page)
    await wrapper.get('#invite-email').setValue('alice@acme.test')
    await submit(wrapper, 'invite-form')
    expect(wrapper.get('[role="alert"]').text()).toBe(INVITATION_REFUSED)
    expect((wrapper.get('#invite-email').element as HTMLInputElement).value).toBe('alice@acme.test')
    expect(page.router.currentRoute.value.name).toBe('users')
  })

  test('everyone is signed out only after a confirmation, the admin too', async () => {
    const page = await asAdmin('/admin/users')
    page.answer('endAllSessions', undefined)
    page.answer('logout', undefined)
    const wrapper = await start(page)

    await click(wrapper, 'Sign everyone out')
    await click(wrapper, 'Cancel')
    expect(page.called('endAllSessions')).toHaveLength(0)

    await click(wrapper, 'Sign everyone out')
    await click(wrapper, 'Sign everyone out now')
    expect(page.called('endAllSessions')).toHaveLength(1)
    expect(page.called('logout')).toHaveLength(1)
    expect(page.router.currentRoute.value.name).toBe('signed-out')
  })
})

describe('the card of a user', () => {
  test('shows who they are, their services and the newest events', async () => {
    const page = await asAdmin(`/admin/users/${ID}`)
    page.answer(
      'getUser',
      cardWith({
        name: 'Carol',
        serviceProviders: [{ id: 'sp', entityId: 'https://sp.example/saml', name: 'Wiki' }],
        events: [
          { id: 'e2', occurredAt: '2026-11-03T09:00:00Z', action: 'user.blocked', actorType: 'USER', actorId: 'someone' },
          { id: 'e1', occurredAt: '2026-11-03T08:00:00Z', action: 'login.succeeded', actorType: 'USER', actorId: ID },
          { id: 'e0', occurredAt: '2026-11-02T08:00:00Z', action: 'user.invited', actorType: 'SYSTEM', actorId: null },
        ],
      }),
    )
    const wrapper = await start(page)
    expect(page.called('getUser')[0]?.args).toEqual([ID])
    expect(wrapper.get('[data-testid="user-email"]').text()).toBe('carol@acme.test')
    expect(wrapper.text()).toContain('Carol')
    expect(wrapper.get('[data-testid="user-role"]').text()).toBe('User')
    expect(wrapper.get('[data-testid="service-provider"]').text()).toContain('Wiki')
    expect(wrapper.get('[data-testid="service-provider"] a').attributes('href')).toBe('/console/admin/service-providers/sp')
    expect(wrapper.get('[data-testid="user-journal"]').attributes('href')).toBe(
      `/console/admin/audit?targetType=USER&targetId=${ID}`,
    )
    const events = wrapper.findAll('[data-testid="user-event"]').map((it) => it.text())
    expect(events[0]).toContain('user.blocked by the user someone')
    expect(events[1]).toContain('login.succeeded by the user')
    expect(events[1]).not.toContain(ID)
    expect(events[2]).toContain('user.invited by the system')
    expect(hasButton(wrapper, 'Send the invitation again')).toBe(false)
  })

  test('of a user who is gone says so', async () => {
    const page = await asAdmin(`/admin/users/${ID}`)
    page.answer('getUser', refused(404))
    const wrapper = await start(page)
    expect(wrapper.get('[data-testid="no-such-user"]').text()).toContain('no such user')
  })

  test('blocks an active user and unblocks a blocked one, reading the card anew after each', async () => {
    const page = await asAdmin(`/admin/users/${ID}`)
    page.answer('getUser', cardWith(), cardWith({ status: 'BLOCKED' }), cardWith())
    page.answer('blockUser', undefined)
    page.answer('unblockUser', undefined)
    const wrapper = await start(page)

    expect(hasButton(wrapper, 'Unblock')).toBe(false)
    await click(wrapper, 'Block')
    expect(page.called('blockUser')[0]?.args).toEqual([ID])
    expect(wrapper.get('[data-testid="user-status"]').text()).toBe('Blocked')
    expect(notice(wrapper)).toContain('blocked, and their sessions are ended')

    await click(wrapper, 'Unblock')
    expect(page.called('unblockUser')[0]?.args).toEqual([ID])
    expect(wrapper.get('[data-testid="user-status"]').text()).toBe('Active')
  })

  test('the role changes, and the last admin is not made a user', async () => {
    const page = await asAdmin(`/admin/users/${ID}`)
    page.answer('getUser', cardWith(), cardWith({ role: 'TENANT_ADMIN' }))
    page.answer('changeRole', undefined, refused(422))
    const wrapper = await start(page)
    expect(button(wrapper, 'Change role').element.disabled).toBe(true)

    await wrapper.get('#user-role-choice').setValue('TENANT_ADMIN')
    await submit(wrapper, 'role-form')
    expect(page.called('changeRole')[0]?.args).toEqual([ID, 'TENANT_ADMIN'])
    expect(wrapper.get('[data-testid="user-role"]').text()).toBe('Admin')

    await wrapper.get('#user-role-choice').setValue('USER')
    await submit(wrapper, 'role-form')
    expect(wrapper.get('[role="alert"]').text()).toBe(LAST_ADMIN)
    expect(wrapper.get('[data-testid="user-role"]').text()).toBe('Admin')
  })

  test('an invitation is sent again only to a user who has not accepted one', async () => {
    const page = await asAdmin(`/admin/users/${ID}`)
    page.answer('getUser', cardWith({ status: 'INVITED' }))
    page.answer('resendInvitation', undefined)
    const wrapper = await start(page)
    await click(wrapper, 'Send the invitation again')
    expect(page.called('resendInvitation')[0]?.args).toEqual([ID])
    expect(notice(wrapper)).toContain('A new invitation is on its way')
  })

  test('the sessions of the user end', async () => {
    const page = await asAdmin(`/admin/users/${ID}`)
    page.answer('getUser', cardWith())
    page.answer('endUserSessions', undefined)
    const wrapper = await start(page)
    await click(wrapper, 'End their sessions')
    expect(page.called('endUserSessions')[0]?.args).toEqual([ID])
    expect(notice(wrapper)).toBe('Their sessions are ended.')
  })

  test('MFA is reset only after a confirmation, and an old login leads to a fresh one', async () => {
    const page = await asAdmin(`/admin/users/${ID}`)
    page.answer('getUser', cardWith())
    page.answer('resetMfa', refused(403, 'reauthentication_required'), undefined)
    const wrapper = await start(page)

    await click(wrapper, 'Reset MFA')
    expect(wrapper.get('[data-testid="confirmation"]').text()).toContain('carol@acme.test')
    await click(wrapper, 'Cancel')
    expect(page.called('resetMfa')).toHaveLength(0)

    await click(wrapper, 'Reset MFA')
    await click(wrapper, 'Remove their factors')
    expect(wrapper.get('[role="alert"] a').attributes('href')).toBe('/login/?reauthenticate')

    await click(wrapper, 'Reset MFA')
    await click(wrapper, 'Remove their factors')
    expect(page.called('resetMfa').map((it) => it.args)).toEqual([[ID], [ID]])
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(notice(wrapper)).toContain('set up new ones at their next sign-in')
  })

  test('erasing asks first, then leads back to the users with a notice', async () => {
    const page = await asAdmin(`/admin/users/${ID}`)
    page.answer('getUser', cardWith())
    page.answer('eraseUser', undefined)
    const wrapper = await start(page)

    await click(wrapper, 'Erase user')
    expect(page.called('eraseUser')).toHaveLength(0)
    await click(wrapper, 'Erase for good')
    expect(page.called('eraseUser')[0]?.args).toEqual([ID])
    expect(page.router.currentRoute.value.name).toBe('users')
    expect(notice(wrapper)).toBe('carol@acme.test is erased.')
    expect(page.queries.getQueryCache().findAll({ queryKey: ['admin', 'users', ID] })).toEqual([])
  })

  test('the last admin is not erased', async () => {
    const page = await asAdmin(`/admin/users/${ID}`)
    page.answer('getUser', cardWith({ role: 'TENANT_ADMIN' }))
    page.answer('eraseUser', refused(422))
    const wrapper = await start(page)
    await click(wrapper, 'Erase user')
    await click(wrapper, 'Erase for good')
    expect(wrapper.get('[role="alert"]').text()).toBe(LAST_ADMIN)
    expect(page.router.currentRoute.value.name).toBe('user')
  })
})

describe('the policy', () => {
  const field = (wrapper: VueWrapper, selector: string) => wrapper.get(selector).element as HTMLInputElement

  test('is shown in minutes and saved in seconds with the version it was read with, then with the next one', async () => {
    const page = await asAdmin('/admin/policy')
    page.answer('getPolicy', policyOf('"3"'), policyOf('"4"', { sessionIdleSeconds: 900 }))
    page.answer('savePolicy', '"4"', '"5"')
    const wrapper = await start(page)
    expect(wrapper.text()).toContain('Admins always sign in with a second factor')
    expect(field(wrapper, 'input[name="mfa-for-users"]').checked).toBe(true)
    expect(field(wrapper, 'input[name="passkey-required"]').checked).toBe(false)
    expect(field(wrapper, '#idle-minutes').value).toBe('30')
    expect(field(wrapper, '#absolute-minutes').value).toBe('720')

    await wrapper.get('input[name="mfa-for-users"]').setValue(false)
    await wrapper.get('#idle-minutes').setValue('15')
    await submit(wrapper, 'policy-form')
    expect(page.called('savePolicy')[0]?.args).toEqual([
      { mfaForUsers: false, passkeyRequired: false, sessionIdleSeconds: 900, sessionAbsoluteSeconds: 43200 },
      '"3"',
    ])
    expect(wrapper.find('[data-testid="policy-saved"]').exists()).toBe(true)

    await submit(wrapper, 'policy-form')
    expect(page.called('savePolicy')[1]?.args[1]).toBe('"4"')
  })

  test('saved meanwhile by someone else offers to load their version, and the change is made again on top of it', async () => {
    const page = await asAdmin('/admin/policy')
    page.answer('getPolicy', policyOf('"3"'), policyOf('"4"', { passkeyRequired: true, sessionIdleSeconds: 600 }))
    page.answer('savePolicy', refused(412), '"5"')
    const wrapper = await start(page)

    await wrapper.get('#absolute-minutes').setValue('600')
    await submit(wrapper, 'policy-form')
    expect(wrapper.get('[data-testid="policy-conflict"]').text()).toContain('Someone else saved the policy')
    expect(wrapper.find('[data-testid="policy-saved"]').exists()).toBe(false)
    expect(field(wrapper, '#absolute-minutes').value).toBe('600')

    await click(wrapper, 'Load the current policy')
    expect(wrapper.find('[data-testid="policy-conflict"]').exists()).toBe(false)
    expect(field(wrapper, 'input[name="passkey-required"]').checked).toBe(true)
    expect(field(wrapper, '#idle-minutes').value).toBe('10')
    expect(field(wrapper, '#absolute-minutes').value).toBe('720')

    await wrapper.get('#absolute-minutes').setValue('600')
    await submit(wrapper, 'policy-form')
    expect(page.called('savePolicy')[1]?.args).toEqual([
      { mfaForUsers: true, passkeyRequired: true, sessionIdleSeconds: 600, sessionAbsoluteSeconds: 36000 },
      '"4"',
    ])
    expect(wrapper.find('[data-testid="policy-saved"]').exists()).toBe(true)
  })

  test('timeouts out of bounds are refused with the bounds, and the form keeps them', async () => {
    const page = await asAdmin('/admin/policy')
    page.answer('getPolicy', policyOf('"3"'))
    page.answer('savePolicy', refused(422))
    const wrapper = await start(page)
    await wrapper.get('#absolute-minutes').setValue('10')
    await submit(wrapper, 'policy-form')
    expect(wrapper.get('[role="alert"]').text()).toBe(TIMEOUTS_REFUSED)
    expect(field(wrapper, '#absolute-minutes').value).toBe('10')
  })

  test('an old login leads to a fresh one before the save', async () => {
    const page = await asAdmin('/admin/policy')
    page.answer('getPolicy', policyOf('"3"'))
    page.answer('savePolicy', refused(403, 'reauthentication_required'))
    const wrapper = await start(page)
    await submit(wrapper, 'policy-form')
    expect(wrapper.get('[role="alert"] a').attributes('href')).toBe('/login/?reauthenticate')
  })
})
