import { describe, expect, test } from 'vitest'
import { createAccountClient, needsReauthentication } from './account'
import { ApiError } from './client'

interface Sent {
  path: string
  init: RequestInit
}

function recording(response: () => Response, cookies = '__Host-csrf=token-value') {
  const sent: Sent[] = []
  const client = createAccountClient({
    version: '1.4.0',
    cookies: () => cookies,
    fetch: async (input, init) => {
      sent.push({ path: String(input), init: init ?? {} })
      return response()
    },
  })
  return { client, sent }
}

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })

const empty = () => new Response(null, { status: 204 })

const csrf = (sent: Sent) => new Headers(sent.init.headers).get('X-CSRF-Token')

describe('requests', () => {
  test('each operation goes to its path with its method', async () => {
    const { client, sent } = recording(() => json({ recoveryCodes: [] }))
    await client.getFactors()
    await client.beginTotp()
    await client.confirmTotp('123456')
    await client.beginPasskey()
    await client.addPasskey({ attestationObject: 'a', clientDataJson: 'c' })
    await client.renewRecoveryCodes()
    await client.getSessions()
    expect(sent.map((it) => `${it.init.method} ${it.path}`)).toEqual([
      'GET /api/account/factors',
      'POST /api/account/totp',
      'POST /api/account/totp/confirmation',
      'POST /api/account/passkeys/options',
      'POST /api/account/passkeys',
      'POST /api/account/recovery-codes',
      'GET /api/account/sessions',
    ])
    expect(sent[2]?.init.body).toBe('{"code":"123456"}')
    expect(sent[4]?.init.body).toBe('{"attestationObject":"a","clientDataJson":"c"}')
  })

  test('removals and logout echo the CSRF cookie and accept an empty answer', async () => {
    const { client, sent } = recording(empty)
    await expect(client.removeTotp()).resolves.toBeUndefined()
    await client.removePasskey('6f1c')
    await client.endOtherSessions()
    await client.endSession('9a2b')
    await client.logout()
    expect(sent.map((it) => `${it.init.method} ${it.path}`)).toEqual([
      'DELETE /api/account/totp',
      'DELETE /api/account/passkeys/6f1c',
      'DELETE /api/account/sessions',
      'DELETE /api/account/sessions/9a2b',
      'POST /api/account/logout',
    ])
    expect(sent.map(csrf)).toEqual(Array(5).fill('token-value'))
  })

  test('a password change sends both passwords and whether to end the other sessions', async () => {
    const { client, sent } = recording(empty)
    await client.changePassword({ currentPassword: 'old', newPassword: 'new', endOtherSessions: true })
    expect(sent[0]?.path).toBe('/api/account/password')
    expect(sent[0]?.init.body).toBe('{"currentPassword":"old","newPassword":"new","endOtherSessions":true}')
  })

  test('an id cannot leave the path it belongs to', async () => {
    const { client, sent } = recording(empty)
    await client.removePasskey('../totp')
    await client.endSession('../../logout')
    expect(sent.map((it) => it.path)).toEqual(['/api/account/passkeys/..%2Ftotp', '/api/account/sessions/..%2F..%2Flogout'])
  })
})

describe('refusals', () => {
  test('a login that is too old is told apart from a missing CSRF token', async () => {
    const { client } = recording(() => json({ error: 'reauthentication_required' }, 403))
    const aged = await client.removeTotp().catch((error: unknown) => error)
    expect(aged).toMatchObject({ status: 403, error: 'reauthentication_required' })
    expect(needsReauthentication(aged)).toBe(true)

    const { client: tokenless } = recording(() => new Response(null, { status: 403 }))
    const refused = await tokenless.removeTotp().catch((error: unknown) => error)
    expect(refused).toMatchObject({ status: 403, error: null })
    expect(needsReauthentication(refused)).toBe(false)
  })

  test('a body that is not JSON, or names no error, gives no reason', async () => {
    const { client } = recording(() => new Response('{', { status: 403, headers: { 'Content-Type': 'application/json' } }))
    await expect(client.endOtherSessions()).rejects.toMatchObject({ status: 403, error: null })
    const { client: other } = recording(() => json({ error: 7 }, 403))
    await expect(other.endOtherSessions()).rejects.toMatchObject({ error: null })
  })

  test('only an ApiError can ask for a fresh login', () => {
    expect(needsReauthentication(new ApiError(401, null, 'reauthentication_required'))).toBe(false)
    expect(needsReauthentication(new TypeError('Failed to fetch'))).toBe(false)
  })
})
