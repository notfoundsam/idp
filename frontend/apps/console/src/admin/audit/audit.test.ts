import type { AuditEntry, AuditJournal } from '@idp/api-client'
import type { VueWrapper } from '@vue/test-utils'
import { afterEach, describe, expect, test } from 'vitest'
import { adminAt, click, hasButton, refused, settle, submit } from '../../testing'

let mounted: VueWrapper | null = null

afterEach(() => {
  mounted?.unmount()
  mounted = null
})

async function start(page: Awaited<ReturnType<typeof adminAt>>) {
  mounted = await page.start()
  return mounted
}

const ADMIN = '5d2c8a90-7b1e-4f43-9c55-0e6a3b1f2d47'
const SP = '0b9f7e3c-5a1d-4c2e-8f6b-2d4a9c1e7b30'

const entryWith = (overrides: Partial<AuditEntry> = {}): AuditEntry => ({
  id: 'e1',
  occurredAt: '2026-11-04T09:00:00.123456Z',
  action: 'sp.settings_changed',
  actorType: 'USER',
  actorId: ADMIN,
  targetType: 'SERVICE_PROVIDER',
  targetId: SP,
  ip: '192.0.2.10',
  details: { nameIdFormat: 'EMAIL' },
  ...overrides,
})

const page = (entries: AuditEntry[], next: AuditJournal['next'] = null): AuditJournal => ({ entries, next })

const rows = (wrapper: VueWrapper) => wrapper.findAll('[data-testid="audit-entry"]')

describe('the journal', () => {
  test('shows each record with who acted, the target, the address and the details', async () => {
    const journal = await adminAt('/admin/audit')
    journal.answer(
      'readAuditJournal',
      page([
        entryWith(),
        entryWith({ id: 'e2', action: 'user.invited', actorType: 'SYSTEM', actorId: null, targetType: 'USER', targetId: ADMIN, ip: null, details: {} }),
        entryWith({ id: 'e3', action: 'login.failed', actorType: 'ANONYMOUS', actorId: null, targetType: null, targetId: null }),
      ]),
    )
    const wrapper = await start(journal)
    expect(journal.called('readAuditJournal')[0]?.args).toEqual([{ after: undefined, target: undefined }])

    const [first, second, third] = rows(wrapper)
    expect(first?.get('[data-testid="audit-action"]').text()).toBe('sp.settings_changed')
    expect(first?.text()).toContain(`the user ${ADMIN}`)
    expect(first?.find('a').attributes('href')).toBe(`/console/admin/users/${ADMIN}`)
    expect(first?.get('[data-testid="audit-target"]').attributes('href')).toBe(
      `/console/admin/audit?targetType=SERVICE_PROVIDER&targetId=${SP}`,
    )
    expect(first?.get('[data-testid="audit-ip"]').text()).toBe('192.0.2.10')
    expect(first?.get('[data-testid="audit-details"]').text()).toBe('nameIdFormat: EMAIL')
    expect(second?.text()).toContain('the system')
    expect(second?.get('[data-testid="audit-ip"]').text()).toBe('None')
    expect(second?.get('[data-testid="audit-details"]').text()).toBe('None')
    expect(third?.text()).toContain('someone not signed in')
    expect(third?.find('[data-testid="audit-target"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="audit-end"]').exists()).toBe(true)
    expect(hasButton(wrapper, 'Show older records')).toBe(false)
  })

  test('a record of an erased actor keeps the fact and says that the address and details were removed', async () => {
    const journal = await adminAt('/admin/audit')
    journal.answer('readAuditJournal', page([entryWith({ ip: null, details: null })]))
    const wrapper = await start(journal)
    const row = rows(wrapper)[0]
    expect(row?.get('[data-testid="audit-action"]').text()).toBe('sp.settings_changed')
    expect(row?.get('[data-testid="audit-erased"]').text()).toBe('Removed with the data of the user who acted')
    expect(row?.find('[data-testid="audit-ip"]').exists()).toBe(false)
  })

  test('reads older pages from the cursor of the page before and keeps the ones read', async () => {
    const journal = await adminAt('/admin/audit')
    const cursor = { before: '2026-11-04T08:00:00.000001Z', beforeId: 'e2' }
    journal.answer('readAuditJournal', page([entryWith(), entryWith({ id: 'e2' })], cursor), page([entryWith({ id: 'e3', action: 'user.blocked' })]))
    const wrapper = await start(journal)
    expect(rows(wrapper)).toHaveLength(2)

    await click(wrapper, 'Show older records')
    expect(journal.called('readAuditJournal')[1]?.args).toEqual([{ after: cursor, target: undefined }])
    expect(rows(wrapper).map((it) => it.get('[data-testid="audit-action"]').text())).toEqual([
      'sp.settings_changed',
      'sp.settings_changed',
      'user.blocked',
    ])
    expect(hasButton(wrapper, 'Show older records')).toBe(false)
  })

  test('shows only the records about the target of the address, and every record again from the link', async () => {
    const journal = await adminAt(`/admin/audit?targetType=SERVICE_PROVIDER&targetId=${SP}`)
    journal.answer('readAuditJournal', page([entryWith()]), page([entryWith(), entryWith({ id: 'e2', targetType: 'USER', targetId: ADMIN })]))
    const wrapper = await start(journal)
    expect(journal.called('readAuditJournal')[0]?.args).toEqual([{ after: undefined, target: { type: 'SERVICE_PROVIDER', id: SP } }])
    expect(wrapper.get('[data-testid="audit-filter"]').text()).toContain(SP)
    expect((wrapper.get('#target-type').element as HTMLSelectElement).value).toBe('SERVICE_PROVIDER')

    await wrapper.get('[data-testid="audit-filter"] a').trigger('click')
    await settle()
    expect(journal.router.currentRoute.value.query).toEqual({})
    expect(journal.called('readAuditJournal')[1]?.args).toEqual([{ after: undefined, target: undefined }])
    expect(wrapper.find('[data-testid="audit-filter"]').exists()).toBe(false)
    expect(rows(wrapper)).toHaveLength(2)
  })

  test('half of a target in the address is no target', async () => {
    const journal = await adminAt(`/admin/audit?targetType=SERVICE_PROVIDER`)
    journal.answer('readAuditJournal', page([]))
    const wrapper = await start(journal)
    expect(journal.called('readAuditJournal')[0]?.args).toEqual([{ after: undefined, target: undefined }])
    expect(wrapper.find('[data-testid="audit-filter"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="no-audit-entries"]').exists()).toBe(true)
  })

  test('a target typed in the form filters the journal, and an ID the server refuses is named', async () => {
    const journal = await adminAt('/admin/audit')
    journal.answer('readAuditJournal', page([entryWith()]), refused(400))
    const wrapper = await start(journal)
    await wrapper.get('#target-type').setValue('USER')
    await wrapper.get('#target-id').setValue(' not-an-id ')
    await submit(wrapper, 'target-form')
    expect(journal.router.currentRoute.value.query).toEqual({ targetType: 'USER', targetId: 'not-an-id' })
    expect(journal.called('readAuditJournal')[1]?.args).toEqual([{ after: undefined, target: { type: 'USER', id: 'not-an-id' } }])
    expect(wrapper.get('[data-testid="audit-refused"]').text()).toBe('That is not the ID of a target.')
  })
})
