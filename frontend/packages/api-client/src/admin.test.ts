import { describe, expect, test } from 'vitest'
import { createAdminClient, PRECONDITION_FAILED } from './admin'
import { ApiError } from './client'

interface Sent {
  path: string
  init: RequestInit
}

function recording(response: () => Response) {
  const sent: Sent[] = []
  const client = createAdminClient({
    version: '1.4.0',
    cookies: () => '__Host-csrf=token-value',
    fetch: async (input, init) => {
      sent.push({ path: String(input), init: init ?? {} })
      return response()
    },
  })
  return { client, sent }
}

const json = (body: unknown, status = 200, etag: string | null = null) => {
  const headers = new Headers({ 'Content-Type': 'application/json' })
  if (etag !== null) headers.set('ETag', etag)
  return new Response(JSON.stringify(body), { status, headers })
}

const empty = (etag: string | null = null) => new Response(null, { status: 204, headers: etag === null ? {} : { ETag: etag } })

const header = (sent: Sent | undefined, name: string) => new Headers(sent?.init.headers).get(name)

const POLICY = { mfaForUsers: true, passkeyRequired: false, sessionIdleSeconds: 1800, sessionAbsoluteSeconds: 43200 }

describe('users', () => {
  test('a search sends the whole address in the body, not the URL, and answers the hits', async () => {
    const hit = { id: 'a1', role: 'USER', status: 'ACTIVE' }
    const { client, sent } = recording(() => json({ users: [hit] }))
    await expect(client.searchUsers('alice@acme.test')).resolves.toEqual([hit])
    expect(sent[0]?.path).toBe('/api/admin/users/search')
    expect(sent[0]?.init.method).toBe('POST')
    expect(sent[0]?.init.body).toBe('{"email":"alice@acme.test"}')
  })

  test('an invitation answers the id of the new user', async () => {
    const { client, sent } = recording(() => json({ id: 'b2' }, 201))
    await expect(client.inviteUser({ email: 'new@acme.test', role: 'USER' })).resolves.toEqual({ id: 'b2' })
    expect(sent[0]?.init.body).toBe('{"email":"new@acme.test","role":"USER"}')
  })

  test('each change goes to its path with its method and echoes the CSRF cookie', async () => {
    const { client, sent } = recording(() => empty())
    await client.resendInvitation('u1')
    await client.changeRole('u1', 'TENANT_ADMIN')
    await client.blockUser('u1')
    await client.unblockUser('u1')
    await client.resetMfa('u1')
    await client.eraseUser('u1')
    await client.endUserSessions('u1')
    await client.endAllSessions()
    expect(sent.map((it) => `${it.init.method} ${it.path}`)).toEqual([
      'POST /api/admin/users/u1/invitation',
      'PUT /api/admin/users/u1/role',
      'POST /api/admin/users/u1/block',
      'POST /api/admin/users/u1/unblock',
      'POST /api/admin/users/u1/mfa-reset',
      'DELETE /api/admin/users/u1',
      'DELETE /api/admin/users/u1/sessions',
      'DELETE /api/admin/sessions',
    ])
    expect(sent[1]?.init.body).toBe('{"role":"TENANT_ADMIN"}')
    expect(sent.map((it) => header(it, 'X-CSRF-Token'))).toEqual(Array(8).fill('token-value'))
  })

  test('an id cannot leave the path it belongs to', async () => {
    const { client, sent } = recording(() => empty())
    await client.eraseUser('../sessions')
    await client.blockUser('../../policy')
    expect(sent.map((it) => it.path)).toEqual(['/api/admin/users/..%2Fsessions', '/api/admin/users/..%2F..%2Fpolicy/block'])
  })
})

describe('the policy', () => {
  test('is read with its version from the ETag', async () => {
    const { client, sent } = recording(() => json(POLICY, 200, '"7"'))
    await expect(client.getPolicy()).resolves.toEqual({ policy: POLICY, version: '"7"' })
    expect(sent[0]?.init.method).toBe('GET')
    expect(header(sent[0], 'X-CSRF-Token')).toBeNull()
  })

  test('without a version it is not taken, since no save could follow', async () => {
    const { client } = recording(() => json(POLICY))
    await expect(client.getPolicy()).rejects.toThrow('without its version')
  })

  test('is saved with the version it was read with, and answers the new one', async () => {
    const { client, sent } = recording(() => empty('"8"'))
    await expect(client.savePolicy(POLICY, '"7"')).resolves.toBe('"8"')
    expect(sent[0]?.init.method).toBe('PUT')
    expect(sent[0]?.path).toBe('/api/admin/policy')
    expect(header(sent[0], 'If-Match')).toBe('"7"')
    expect(header(sent[0], 'X-CSRF-Token')).toBe('token-value')
    expect(sent[0]?.init.body).toBe(JSON.stringify(POLICY))
  })

  test('a save after someone else saved is refused with 412', async () => {
    const { client } = recording(() => new Response(null, { status: PRECONDITION_FAILED }))
    const refused = await client.savePolicy(POLICY, '"6"').catch((error: unknown) => error)
    expect(refused).toBeInstanceOf(ApiError)
    expect((refused as ApiError).status).toBe(412)
  })
})
