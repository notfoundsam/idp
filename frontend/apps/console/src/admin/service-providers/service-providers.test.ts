import type { ServiceProvider, SigningKey, VersionedServiceProvider } from '@idp/api-client'
import type { VueWrapper } from '@vue/test-utils'
import { afterEach, describe, expect, test } from 'vitest'
import { adminAt, button, click, hasButton, refused, settle, submit } from '../../testing'
import { ENTITY_ID_TAKEN, METADATA_REFUSED, METADATA_TOO_LARGE, NO_ROTATION, ROTATION_IN_PROGRESS, USER_GONE } from '../admin'

let mounted: VueWrapper | null = null

afterEach(() => {
  mounted?.unmount()
  mounted = null
})

async function start(page: Awaited<ReturnType<typeof adminAt>>) {
  mounted = await page.start()
  return mounted
}

const SP = '0b9f7e3c-5a1d-4c2e-8f6b-2d4a9c1e7b30'
const CAROL = '5d2c8a90-7b1e-4f43-9c55-0e6a3b1f2d47'
const DAVE = '9a1e6c42-3f0b-4d8e-b7a5-1c2d3e4f5a60'

const providerWith = (overrides: Partial<ServiceProvider> = {}): ServiceProvider => ({
  id: SP,
  entityId: 'https://wiki.example/saml',
  name: 'Wiki',
  acs: [
    { index: 0, url: 'https://wiki.example/saml/acs', isDefault: true },
    { index: 1, url: 'https://wiki.example/saml/acs2', isDefault: false },
  ],
  authnRequestsSigned: true,
  signingCertificates: [{ sha256Fingerprint: 'AB:CD:EF', notAfter: '2027-01-01T00:00:00Z' }],
  settings: { nameIdFormat: 'PERSISTENT', signedPart: 'ASSERTION', signatureAlgorithm: 'RSA_PSS_SHA256', attributes: [] },
  ...overrides,
})

const versioned = (version: string, overrides: Partial<ServiceProvider> = {}): VersionedServiceProvider => ({
  serviceProvider: providerWith(overrides),
  version,
})

const notice = (wrapper: VueWrapper) => wrapper.get('[data-testid="admin-notice"]').text()
const alert = (wrapper: VueWrapper) => wrapper.get('[role="alert"]').text()

async function pick(wrapper: VueWrapper, file: File) {
  const input = wrapper.get('#sp-metadata')
  Object.defineProperty(input.element, 'files', { value: [file], configurable: true })
  await input.trigger('change')
}

describe('the list of services', () => {
  test('names each service with its EntityID and leads to its card', async () => {
    const page = await adminAt('/admin/service-providers')
    page.answer('listServiceProviders', [{ id: SP, entityId: 'https://wiki.example/saml', name: 'Wiki' }])
    const wrapper = await start(page)
    const item = wrapper.get('[data-testid="service-provider-item"]')
    expect(item.text()).toContain('Wiki')
    expect(item.text()).toContain('https://wiki.example/saml')
    expect(item.get('a').attributes('href')).toBe(`/console/admin/service-providers/${SP}`)
  })

  test('says when no service is connected', async () => {
    const page = await adminAt('/admin/service-providers')
    page.answer('listServiceProviders', [])
    const wrapper = await start(page)
    expect(wrapper.find('[data-testid="no-service-providers"]').exists()).toBe(true)
  })
})

describe('connecting a service', () => {
  async function importing(...answers: unknown[]) {
    const page = await adminAt('/admin/service-providers')
    page.answer('listServiceProviders', [])
    page.answer('registerServiceProvider', ...answers)
    page.answer('getServiceProvider', versioned('"1"'))
    page.answer('listAssignedUsers', [])
    const wrapper = await start(page)
    await wrapper.get('#sp-name').setValue('  Wiki ')
    return { page, wrapper }
  }

  test('uploads the name and the file, then leads to the card of the new service', async () => {
    const { page, wrapper } = await importing(SP)
    const file = new File(['<md:EntityDescriptor/>'], 'wiki.xml', { type: 'text/xml' })
    await pick(wrapper, file)
    await submit(wrapper, 'import-form')
    expect(page.called('registerServiceProvider')[0]?.args).toEqual(['Wiki', file])
    expect(page.router.currentRoute.value.fullPath).toBe(`/admin/service-providers/${SP}`)
    expect(notice(wrapper)).toBe('Wiki is connected. Nobody is assigned to it yet, so nobody signs in to it.')
  })

  test('refuses a file over 128 KiB before sending it', async () => {
    const { page, wrapper } = await importing(SP)
    await pick(wrapper, new File(['x'.repeat(128 * 1024 + 1)], 'big.xml'))
    await submit(wrapper, 'import-form')
    expect(page.called('registerServiceProvider')).toHaveLength(0)
    expect(alert(wrapper)).toBe(METADATA_TOO_LARGE)
  })

  test.each([
    [422, METADATA_REFUSED],
    [409, ENTITY_ID_TAKEN],
    [413, METADATA_TOO_LARGE],
  ])('a refusal with %i names why, and the page stays', async (status, message) => {
    const { page, wrapper } = await importing(refused(status))
    await pick(wrapper, new File(['<md:EntityDescriptor/>'], 'wiki.xml'))
    await submit(wrapper, 'import-form')
    expect(alert(wrapper)).toBe(message)
    expect(page.router.currentRoute.value.name).toBe('service-providers')
  })
})

describe('the card of a service', () => {
  async function card({ providers = [versioned('"1"')], assigned = [[]] }: { providers?: unknown[]; assigned?: unknown[] } = {}) {
    const page = await adminAt(`/admin/service-providers/${SP}`)
    page.answer('getServiceProvider', ...providers)
    page.answer('listAssignedUsers', ...assigned)
    return page
  }

  const field = (wrapper: VueWrapper, selector: string) => wrapper.get(selector).element as HTMLInputElement

  test('shows what the metadata said and links to the journal of the service', async () => {
    const page = await card()
    const wrapper = await start(page)
    expect(page.called('getServiceProvider')[0]?.args).toEqual([SP])
    expect(wrapper.get('[data-testid="sp-name"]').text()).toBe('Wiki')
    expect(wrapper.get('[data-testid="sp-entity-id"]').text()).toBe('https://wiki.example/saml')
    expect(wrapper.get('[data-testid="sp-requests-signed"]').text()).toContain('Yes')
    expect(wrapper.findAll('[data-testid="sp-acs"]').map((it) => it.text())).toEqual([
      'https://wiki.example/saml/acs (default)',
      'https://wiki.example/saml/acs2',
    ])
    expect(wrapper.get('[data-testid="sp-certificate"]').text()).toContain('AB:CD:EF')
    expect(wrapper.get('[data-testid="sp-journal"]').attributes('href')).toBe(
      `/console/admin/audit?targetType=SERVICE_PROVIDER&targetId=${SP}`,
    )
    expect(wrapper.find('[data-testid="no-assigned-users"]').exists()).toBe(true)
  })

  test('of a service that is gone says so', async () => {
    const page = await adminAt(`/admin/service-providers/${SP}`)
    page.answer('getServiceProvider', refused(404))
    page.answer('listAssignedUsers', refused(404))
    const wrapper = await start(page)
    expect(wrapper.get('[data-testid="no-such-service-provider"]').text()).toContain('no such service')
  })

  test('saves the settings with the version they were read with, then with the next one', async () => {
    const page = await card({ providers: [versioned('"1"'), versioned('"2"')] })
    page.answer('saveServiceProviderSettings', '"2"', '"3"')
    const wrapper = await start(page)
    expect((wrapper.get('#sp-name-id').element as HTMLSelectElement).value).toBe('PERSISTENT')

    await wrapper.get('#sp-name-id').setValue('EMAIL')
    await wrapper.get('#sp-algorithm').setValue('RSA_SHA256')
    await wrapper.get('input[value="PHONE"]').setValue(true)
    await wrapper.get('input[value="EMAIL"]').setValue(true)
    await submit(wrapper, 'settings-form')
    expect(page.called('saveServiceProviderSettings')[0]?.args).toEqual([
      SP,
      { nameIdFormat: 'EMAIL', signedPart: 'ASSERTION', signatureAlgorithm: 'RSA_SHA256', attributes: ['EMAIL', 'PHONE'] },
      '"1"',
    ])
    expect(wrapper.find('[data-testid="settings-saved"]').exists()).toBe(true)

    await submit(wrapper, 'settings-form')
    expect(page.called('saveServiceProviderSettings')[1]?.args[2]).toBe('"2"')
  })

  test('saved meanwhile by someone else offers to load their version, and the change is made again on top of it', async () => {
    const theirs = versioned('"2"', {
      settings: { nameIdFormat: 'PERSISTENT', signedPart: 'BOTH', signatureAlgorithm: 'RSA_PSS_SHA256', attributes: ['DISPLAY_NAME'] },
    })
    const page = await card({ providers: [versioned('"1"'), theirs] })
    page.answer('saveServiceProviderSettings', refused(412), '"3"')
    const wrapper = await start(page)

    await wrapper.get('#sp-name-id').setValue('EMAIL')
    await submit(wrapper, 'settings-form')
    expect(wrapper.get('[data-testid="settings-conflict"]').text()).toContain('Someone else saved the settings')
    expect(wrapper.find('[data-testid="settings-saved"]').exists()).toBe(false)
    expect((wrapper.get('#sp-name-id').element as HTMLSelectElement).value).toBe('EMAIL')

    await click(wrapper, 'Load the current settings')
    expect(wrapper.find('[data-testid="settings-conflict"]').exists()).toBe(false)
    expect((wrapper.get('#sp-name-id').element as HTMLSelectElement).value).toBe('PERSISTENT')
    expect((wrapper.get('#sp-signed-part').element as HTMLSelectElement).value).toBe('BOTH')
    expect(field(wrapper, 'input[value="DISPLAY_NAME"]').checked).toBe(true)

    await wrapper.get('#sp-name-id').setValue('EMAIL')
    await submit(wrapper, 'settings-form')
    expect(page.called('saveServiceProviderSettings')[1]?.args).toEqual([
      SP,
      { nameIdFormat: 'EMAIL', signedPart: 'BOTH', signatureAlgorithm: 'RSA_PSS_SHA256', attributes: ['DISPLAY_NAME'] },
      '"2"',
    ])
    expect(wrapper.find('[data-testid="settings-saved"]').exists()).toBe(true)
  })

  test('an old login leads to a fresh one before the settings are saved', async () => {
    const page = await card()
    page.answer('saveServiceProviderSettings', refused(403, 'reauthentication_required'))
    const wrapper = await start(page)
    await submit(wrapper, 'settings-form')
    expect(wrapper.get('[role="alert"] a').attributes('href')).toBe('/login/?reauthenticate')
  })

  test('assigns every selected user in one request, each found by the whole address', async () => {
    const page = await card({
      assigned: [
        [],
        [
          { id: CAROL, role: 'USER', status: 'ACTIVE' },
          { id: DAVE, role: 'TENANT_ADMIN', status: 'INVITED' },
        ],
      ],
    })
    page.answer(
      'searchUsers',
      [{ id: CAROL, role: 'USER', status: 'ACTIVE' }],
      [],
      [{ id: DAVE, role: 'TENANT_ADMIN', status: 'INVITED' }],
      [{ id: CAROL, role: 'USER', status: 'ACTIVE' }],
    )
    page.answer('assignUsers', undefined)
    const wrapper = await start(page)

    for (const email of [' carol@acme.test ', 'nobody@acme.test', 'dave@acme.test', 'Carol@acme.test']) {
      await wrapper.get('#pick-email').setValue(email)
      await submit(wrapper, 'pick-form')
    }
    expect(page.called('searchUsers').map((it) => it.args[0])).toEqual([
      'carol@acme.test',
      'nobody@acme.test',
      'dave@acme.test',
      'Carol@acme.test',
    ])
    expect(wrapper.findAll('[data-testid="picked-user"]').map((it) => it.text().split(',')[0])).toEqual([
      'carol@acme.test',
      'dave@acme.test',
    ])
    expect(wrapper.find('[data-testid="pick-no-user"]').exists()).toBe(false)

    await click(wrapper, 'Assign selected')
    expect(page.called('assignUsers')[0]?.args).toEqual([SP, [CAROL, DAVE]])
    expect(notice(wrapper)).toBe('2 users can sign in to it now.')
    expect(wrapper.findAll('[data-testid="picked-user"]')).toHaveLength(0)
    expect(wrapper.findAll('[data-testid="assigned-user"]').map((it) => it.get('a').attributes('href'))).toEqual([
      `/console/admin/users/${CAROL}`,
      `/console/admin/users/${DAVE}`,
    ])
  })

  test('says when no user has the address, and a user left out is not assigned', async () => {
    const page = await card()
    page.answer('searchUsers', [], [{ id: CAROL, role: 'USER', status: 'ACTIVE' }], [{ id: DAVE, role: 'USER', status: 'ACTIVE' }])
    page.answer('assignUsers', undefined)
    const wrapper = await start(page)

    await wrapper.get('#pick-email').setValue('nobody@acme.test')
    await submit(wrapper, 'pick-form')
    expect(wrapper.get('[data-testid="pick-no-user"]').text()).toBe('No user has the address nobody@acme.test.')
    expect(hasButton(wrapper, 'Assign selected')).toBe(false)

    for (const email of ['carol@acme.test', 'dave@acme.test']) {
      await wrapper.get('#pick-email').setValue(email)
      await submit(wrapper, 'pick-form')
    }
    await wrapper.findAll('[data-testid="picked-user"]')[0]?.get('button').trigger('click')
    await click(wrapper, 'Assign selected')
    expect(page.called('assignUsers')[0]?.args).toEqual([SP, [DAVE]])
    expect(notice(wrapper)).toBe('1 user can sign in to it now.')
  })

  test('a selection with a user who is gone assigns nobody and says so', async () => {
    const page = await card()
    page.answer('searchUsers', [{ id: CAROL, role: 'USER', status: 'ACTIVE' }])
    page.answer('assignUsers', refused(404))
    const wrapper = await start(page)
    await wrapper.get('#pick-email').setValue('carol@acme.test')
    await submit(wrapper, 'pick-form')
    await click(wrapper, 'Assign selected')
    expect(alert(wrapper)).toBe(USER_GONE)
    expect(wrapper.findAll('[data-testid="picked-user"]')).toHaveLength(1)
  })

  test('revokes the checked users in one request and reads them anew', async () => {
    const page = await card({
      assigned: [
        [
          { id: CAROL, role: 'USER', status: 'ACTIVE' },
          { id: DAVE, role: 'USER', status: 'BLOCKED' },
        ],
        [{ id: DAVE, role: 'USER', status: 'BLOCKED' }],
      ],
    })
    page.answer('unassignUsers', undefined)
    const wrapper = await start(page)
    expect(button(wrapper, 'Revoke selected').element.disabled).toBe(true)

    await wrapper.get(`#revoke-${CAROL}`).setValue(true)
    await submit(wrapper, 'revoke-form')
    expect(page.called('unassignUsers')[0]?.args).toEqual([SP, [CAROL]])
    expect(notice(wrapper)).toContain('1 user can no longer sign in to it')
    expect(wrapper.findAll('[data-testid="assigned-user"]').map((it) => it.text())).toEqual([expect.stringContaining(DAVE)])
  })

  test('is removed only after a confirmation, then leads back to the services with a notice', async () => {
    const page = await card()
    page.answer('removeServiceProvider', undefined)
    page.answer('listServiceProviders', [])
    const wrapper = await start(page)

    await click(wrapper, 'Remove service')
    expect(wrapper.get('[data-testid="confirmation"]').text()).toContain('Remove Wiki?')
    await click(wrapper, 'Cancel')
    expect(page.called('removeServiceProvider')).toHaveLength(0)

    await click(wrapper, 'Remove service')
    await click(wrapper, 'Remove for good')
    expect(page.called('removeServiceProvider')[0]?.args).toEqual([SP])
    expect(page.router.currentRoute.value.name).toBe('service-providers')
    expect(notice(wrapper)).toBe('Wiki is removed. Its users can no longer sign in to it.')
    expect(page.queries.getQueryCache().findAll({ queryKey: ['admin', 'service-providers', SP] })).toEqual([])
  })

  test('an old login leads to a fresh one before the removal', async () => {
    const page = await card()
    page.answer('removeServiceProvider', refused(403, 'reauthentication_required'))
    const wrapper = await start(page)
    await click(wrapper, 'Remove service')
    await click(wrapper, 'Remove for good')
    expect(wrapper.get('[role="alert"] a').attributes('href')).toBe('/login/?reauthenticate')
    expect(page.router.currentRoute.value.name).toBe('service-provider')
  })
})

describe('the signing keys', () => {
  const ACTIVE: SigningKey = {
    id: 'k1',
    status: 'ACTIVE',
    sha256Fingerprint: '11:11',
    notAfter: '2027-11-04T00:00:00Z',
    createdAt: '2026-11-04T00:00:00Z',
  }
  const NEXT: SigningKey = { ...ACTIVE, id: 'k2', status: 'NEXT', sha256Fingerprint: '22:22' }
  const fingerprints = (wrapper: VueWrapper) =>
    wrapper.findAll('[data-testid="signing-key"]').map((it) => [it.attributes('data-status'), it.get('code').text()])

  test('name the metadata the services read them from', async () => {
    const page = await adminAt('/admin/signing-keys')
    page.answer('listSigningKeys', [ACTIVE])
    const wrapper = await start(page)
    expect(wrapper.get('[data-testid="metadata-url"]').attributes('href')).toBe('https://acme.idp.test/saml/metadata')
    expect(fingerprints(wrapper)).toEqual([['ACTIVE', '11:11']])
    expect(hasButton(wrapper, 'Complete rotation')).toBe(false)
  })

  test('rotate: the next key is published first, and completing it asks first', async () => {
    const page = await adminAt('/admin/signing-keys')
    page.answer('listSigningKeys', [ACTIVE], [ACTIVE, NEXT], [{ ...NEXT, status: 'ACTIVE' }])
    page.answer('startKeyRotation', 'k2')
    page.answer('completeKeyRotation', undefined)
    const wrapper = await start(page)

    await click(wrapper, 'Start rotation')
    expect(page.called('startKeyRotation')).toHaveLength(1)
    expect(notice(wrapper)).toContain('The next key is published')
    expect(fingerprints(wrapper)).toEqual([
      ['ACTIVE', '11:11'],
      ['NEXT', '22:22'],
    ])
    expect(hasButton(wrapper, 'Start rotation')).toBe(false)

    await click(wrapper, 'Complete rotation')
    await click(wrapper, 'Cancel')
    expect(page.called('completeKeyRotation')).toHaveLength(0)
    await click(wrapper, 'Complete rotation')
    await click(wrapper, 'Complete now')
    expect(page.called('completeKeyRotation')).toHaveLength(1)
    expect(notice(wrapper)).toBe('The next key signs now, and the one before is deleted.')
    expect(fingerprints(wrapper)).toEqual([['ACTIVE', '22:22']])
  })

  test('a rotation another admin started meanwhile is shown', async () => {
    const page = await adminAt('/admin/signing-keys')
    page.answer('listSigningKeys', [ACTIVE], [ACTIVE, NEXT])
    page.answer('startKeyRotation', refused(409))
    const wrapper = await start(page)
    await click(wrapper, 'Start rotation')
    expect(alert(wrapper)).toBe(ROTATION_IN_PROGRESS)
    expect(hasButton(wrapper, 'Complete rotation')).toBe(true)
  })

  test('a rotation another admin completed meanwhile is shown', async () => {
    const page = await adminAt('/admin/signing-keys')
    page.answer('listSigningKeys', [ACTIVE, NEXT], [{ ...NEXT, status: 'ACTIVE' }])
    page.answer('completeKeyRotation', refused(409))
    const wrapper = await start(page)
    await click(wrapper, 'Complete rotation')
    await click(wrapper, 'Complete now')
    await settle()
    expect(alert(wrapper)).toBe(NO_ROTATION)
    expect(fingerprints(wrapper)).toEqual([['ACTIVE', '22:22']])
  })

  test('an old login leads to a fresh one before the completion', async () => {
    const page = await adminAt('/admin/signing-keys')
    page.answer('listSigningKeys', [ACTIVE, NEXT])
    page.answer('completeKeyRotation', refused(403, 'reauthentication_required'))
    const wrapper = await start(page)
    await click(wrapper, 'Complete rotation')
    await click(wrapper, 'Complete now')
    expect(wrapper.get('[role="alert"] a').attributes('href')).toBe('/login/?reauthenticate')
    expect(fingerprints(wrapper)).toHaveLength(2)
  })
})
