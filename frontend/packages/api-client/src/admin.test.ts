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

const SP_ID = '0b9f7e3c-5a1d-4c2e-8f6b-2d4a9c1e7b30'
const SETTINGS = { nameIdFormat: 'PERSISTENT', signedPart: 'ASSERTION', signatureAlgorithm: 'RSA_PSS_SHA256', attributes: ['EMAIL'] } as const
const SERVICE_PROVIDER = {
  id: SP_ID,
  entityId: 'https://sp.example/saml',
  name: 'Wiki',
  acs: [{ index: 0, url: 'https://sp.example/saml/acs', isDefault: true }],
  authnRequestsSigned: false,
  signingCertificates: [],
  settings: { ...SETTINGS, attributes: [...SETTINGS.attributes] },
}

describe('Service Providers', () => {
  test('are listed by the list the server answers', async () => {
    const summary = { id: SP_ID, entityId: 'https://sp.example/saml', name: 'Wiki' }
    const { client, sent } = recording(() => json({ serviceProviders: [summary] }))
    await expect(client.listServiceProviders()).resolves.toEqual([summary])
    expect(`${sent[0]?.init.method} ${sent[0]?.path}`).toBe('GET /api/admin/service-providers')
  })

  test('are registered from a multipart form whose file carries a fixed name, not the one of the browser', async () => {
    const { client, sent } = recording(() => json({ id: SP_ID }, 201))
    const file = new File(['<md:EntityDescriptor/>'], '../../etc/passwd', { type: 'text/xml' })
    await expect(client.registerServiceProvider('Wiki', file)).resolves.toBe(SP_ID)
    expect(`${sent[0]?.init.method} ${sent[0]?.path}`).toBe('POST /api/admin/service-providers')
    const form = sent[0]?.init.body
    expect(form).toBeInstanceOf(FormData)
    expect((form as FormData).get('name')).toBe('Wiki')
    const metadata = (form as FormData).get('metadata') as File
    expect(metadata.name).toBe('metadata.xml')
    expect(await metadata.text()).toBe('<md:EntityDescriptor/>')
    expect(header(sent[0], 'Content-Type')).toBeNull()
    expect(header(sent[0], 'X-CSRF-Token')).toBe('token-value')
  })

  test('a file the server refuses is answered with its status', async () => {
    const { client } = recording(() => new Response(null, { status: 422 }))
    const refused = await client.registerServiceProvider('Wiki', new Blob(['not metadata'])).catch((error: unknown) => error)
    expect((refused as ApiError).status).toBe(422)
  })

  test('one is read with the version of its settings from the ETag', async () => {
    const { client, sent } = recording(() => json(SERVICE_PROVIDER, 200, '"2"'))
    await expect(client.getServiceProvider(SP_ID)).resolves.toEqual({ serviceProvider: SERVICE_PROVIDER, version: '"2"' })
    expect(sent[0]?.path).toBe(`/api/admin/service-providers/${SP_ID}`)
  })

  test('without a version it is not taken, since no save of its settings could follow', async () => {
    const { client } = recording(() => json(SERVICE_PROVIDER))
    await expect(client.getServiceProvider(SP_ID)).rejects.toThrow('without the version')
  })

  test('the settings are saved with the version they were read with, and a save over a newer one gets 412', async () => {
    const answers = [empty('"3"'), new Response(null, { status: PRECONDITION_FAILED })]
    const { client, sent } = recording(() => answers.shift() ?? empty())
    const settings = { ...SETTINGS, attributes: [...SETTINGS.attributes] }
    await expect(client.saveServiceProviderSettings(SP_ID, settings, '"2"')).resolves.toBe('"3"')
    expect(`${sent[0]?.init.method} ${sent[0]?.path}`).toBe(`PUT /api/admin/service-providers/${SP_ID}/settings`)
    expect(header(sent[0], 'If-Match')).toBe('"2"')
    expect(sent[0]?.init.body).toBe(JSON.stringify(settings))
    const refused = await client.saveServiceProviderSettings(SP_ID, settings, '"2"').catch((error: unknown) => error)
    expect((refused as ApiError).status).toBe(412)
  })

  test('users are listed, assigned and revoked by a list of IDs in one request each', async () => {
    const assigned = { id: 'u1', role: 'USER', status: 'ACTIVE' }
    const answers = [json({ users: [assigned] }), empty(), empty(), empty()]
    const { client, sent } = recording(() => answers.shift() ?? empty())
    await expect(client.listAssignedUsers(SP_ID)).resolves.toEqual([assigned])
    await client.assignUsers(SP_ID, ['u1', 'u2'])
    await client.unassignUsers(SP_ID, ['u2'])
    await client.removeServiceProvider(SP_ID)
    expect(sent.map((it) => `${it.init.method} ${it.path}`)).toEqual([
      `GET /api/admin/service-providers/${SP_ID}/users`,
      `POST /api/admin/service-providers/${SP_ID}/users`,
      `POST /api/admin/service-providers/${SP_ID}/users/removal`,
      `DELETE /api/admin/service-providers/${SP_ID}`,
    ])
    expect(sent[1]?.init.body).toBe('{"userIds":["u1","u2"]}')
    expect(sent[2]?.init.body).toBe('{"userIds":["u2"]}')
  })

  test('an id cannot leave the path it belongs to', async () => {
    const { client, sent } = recording(() => empty())
    await client.removeServiceProvider('../../signing-keys')
    expect(sent[0]?.path).toBe('/api/admin/service-providers/..%2F..%2Fsigning-keys')
  })
})

describe('the signing keys', () => {
  test('are listed, and a rotation starts and completes', async () => {
    const key = { id: 'k1', status: 'ACTIVE', sha256Fingerprint: 'AB:CD', notAfter: '2027-11-04T00:00:00Z', createdAt: '2026-11-04T00:00:00Z' }
    const answers = [json({ keys: [key] }), json({ id: 'k2' }, 201), empty()]
    const { client, sent } = recording(() => answers.shift() ?? empty())
    await expect(client.listSigningKeys()).resolves.toEqual([key])
    await expect(client.startKeyRotation()).resolves.toBe('k2')
    await client.completeKeyRotation()
    expect(sent.map((it) => `${it.init.method} ${it.path}`)).toEqual([
      'GET /api/admin/signing-keys',
      'POST /api/admin/signing-keys/rotation',
      'POST /api/admin/signing-keys/rotation/completion',
    ])
  })
})

describe('the journal', () => {
  const page = { entries: [], next: null }

  test('starts at the newest records with the limit of the server', async () => {
    const { client, sent } = recording(() => json(page))
    await expect(client.readAuditJournal()).resolves.toEqual(page)
    expect(sent[0]?.path).toBe('/api/admin/audit')
  })

  test('goes on from the cursor of the page before and keeps the target, each value with its pair', async () => {
    const { client, sent } = recording(() => json(page))
    await client.readAuditJournal({
      limit: 20,
      after: { before: '2026-11-04T09:00:00.123456Z', beforeId: 'e1' },
      target: { type: 'SERVICE_PROVIDER', id: SP_ID },
    })
    const url = new URL(sent[0]?.path ?? '', 'https://acme.idp.test')
    expect(url.pathname).toBe('/api/admin/audit')
    expect(Object.fromEntries(url.searchParams)).toEqual({
      limit: '20',
      before: '2026-11-04T09:00:00.123456Z',
      beforeId: 'e1',
      targetType: 'SERVICE_PROVIDER',
      targetId: SP_ID,
    })
  })

  test('a value the server refuses is answered with 400', async () => {
    const { client } = recording(() => new Response(null, { status: 400 }))
    const refused = await client.readAuditJournal({ target: { type: 'USER', id: 'not-an-id' } }).catch((error: unknown) => error)
    expect((refused as ApiError).status).toBe(400)
  })
})
