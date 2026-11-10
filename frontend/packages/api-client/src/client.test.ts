import { describe, expect, test } from 'vitest'
import { ApiError, createClient, type ClientOptions } from './client'

interface Sent {
  path: string
  init: RequestInit
}

function recording(response: () => Response, options: Partial<ClientOptions> = {}) {
  const sent: Sent[] = []
  const client = createClient({
    version: '1.4.0',
    cookies: () => '',
    fetch: async (input, init) => {
      sent.push({ path: String(input), init: init ?? {} })
      return response()
    },
    ...options,
  })
  return { client, sent }
}

const json = (body: unknown, status = 200, extra: [string, string][] = []) =>
  new Response(JSON.stringify(body), { status, headers: [['Content-Type', 'application/json'], ...extra] })

const header = (sent: Sent, name: string) => new Headers(sent.init.headers).get(name)

function nth(sent: Sent[], index: number): Sent {
  const request = sent[index]
  if (request === undefined) throw new Error(`No request ${index}`)
  return request
}

const flow = { id: '7d6a3d55-0e0c-4b52-9a59-3c2f1f2b1c11', step: 'PASSWORD', factors: [] }

describe('requests', () => {
  test('every request names the client version', async () => {
    const { client, sent } = recording(() => json(flow))
    await client.getFlow(flow.id)
    await client.submitLogin(flow.id, 'alice@acme.test')
    expect(sent.map((it) => header(it, 'X-Client-Version'))).toEqual(['1.4.0', '1.4.0'])
  })

  test('a call that changes something echoes the CSRF cookie, a read does not', async () => {
    const { client, sent } = recording(() => json(flow), { cookies: () => 'other=1; __Host-csrf=token-value; last=2' })
    await client.getFlow(flow.id)
    await client.submitPassword(flow.id, 'secret')
    expect(header(nth(sent, 0), 'X-CSRF-Token')).toBeNull()
    expect(header(nth(sent, 1), 'X-CSRF-Token')).toBe('token-value')
  })

  test('without the CSRF cookie the header is left out', async () => {
    const { client, sent } = recording(() => json(flow), { cookies: () => 'not__Host-csrf=x' })
    await client.submitLogin(flow.id, 'alice@acme.test')
    expect(header(nth(sent, 0), 'X-CSRF-Token')).toBeNull()
  })

  test('a step sends its answer as JSON to the path of the flow', async () => {
    const { client, sent } = recording(() => json(flow))
    await client.submitTotp(flow.id, '123456')
    expect(nth(sent, 0).path).toBe(`/api/flows/${flow.id}/totp`)
    expect(nth(sent, 0).init.method).toBe('POST')
    expect(header(nth(sent, 0), 'Content-Type')).toBe('application/json')
    expect(nth(sent, 0).init.body).toBe('{"code":"123456"}')
  })

  test('a value with quotes, a backslash or markup stays one JSON string', async () => {
    const { client, sent } = recording(() => json(flow))
    const typed = 'a"b\\c","admin":true,"x":"</script>'
    await client.submitPassword(flow.id, typed)
    expect(JSON.parse(String(nth(sent, 0).init.body))).toEqual({ password: typed })
  })

  test('an id from the URL cannot leave the path of its flow', async () => {
    const { client, sent } = recording(() => json(flow))
    await client.getFlow('../../password-reset')
    expect(nth(sent, 0).path).toBe('/api/flows/..%2F..%2Fpassword-reset')
  })

  test('a token from a mail travels in the body, never in the path', async () => {
    const { client, sent } = recording(() => new Response(null, { status: 204 }))
    await client.confirmPasswordReset('mail-token', 'new password')
    await client.confirmEmailChange('mail-token')
    expect(sent.map((it) => it.path)).toEqual(['/api/password-reset/confirmation', '/api/email-change/confirmation'])
    expect(sent.map((it) => it.init.body)).toEqual(['{"token":"mail-token","password":"new password"}', '{"token":"mail-token"}'])
  })

  test('an invitation is accepted on the flow it signs in with', async () => {
    const { client, sent } = recording(() => json(flow))
    expect(await client.acceptInvitation(flow.id, 'mail-token', 'first password')).toEqual(flow)
    expect(nth(sent, 0).path).toBe(`/api/flows/${flow.id}/invitation`)
    expect(nth(sent, 0).init.body).toBe('{"token":"mail-token","password":"first password"}')
  })

  test('asking for a reset mail is accepted without a body', async () => {
    const { client, sent } = recording(() => new Response(null, { status: 202 }))
    await expect(client.requestPasswordReset('alice@acme.test')).resolves.toBeUndefined()
    expect(nth(sent, 0).path).toBe('/api/password-reset')
    expect(nth(sent, 0).init.body).toBe('{"login":"alice@acme.test"}')
  })

  test('starting a flow sends what it asks for', async () => {
    const { client, sent } = recording(() => json(flow, 201))
    await client.startFlow({ reauthenticate: true })
    expect(nth(sent, 0).path).toBe('/api/flows')
    expect(nth(sent, 0).init.body).toBe('{"reauthenticate":true}')
  })
})

describe('responses', () => {
  test('a flow is read from the body', async () => {
    const { client } = recording(() => json(flow))
    expect(await client.getFlow(flow.id)).toEqual(flow)
  })

  test('the version of the backend is reported from every answer, refusals included', async () => {
    const seen: string[] = []
    let status = 200
    const { client } = recording(() => json(flow, status, [['X-App-Version', `1.5.${status}`]]), {
      onAppVersion: (version) => seen.push(version),
    })
    await client.getFlow(flow.id)
    status = 422
    await expect(client.submitPassword(flow.id, 'wrong')).rejects.toThrow(ApiError)
    expect(seen).toEqual(['1.5.200', '1.5.422'])
  })

  test('an answer without a version reports none', async () => {
    const seen: string[] = []
    const { client } = recording(() => json(flow), { onAppVersion: (version) => seen.push(version) })
    await client.getFlow(flow.id)
    expect(seen).toEqual([])
  })

  test('a refusal carries its status', async () => {
    const { client } = recording(() => new Response(null, { status: 410 }))
    await expect(client.getFlow(flow.id)).rejects.toMatchObject({ status: 410, retryAfter: null })
  })

  test('426 is reported before it is thrown', async () => {
    let told = 0
    const { client } = recording(() => new Response(null, { status: 426 }), { onUpgradeRequired: () => told++ })
    await expect(client.submitLogin(flow.id, 'alice@acme.test')).rejects.toMatchObject({ status: 426 })
    expect(told).toBe(1)
  })

  test('429 carries how long to wait', async () => {
    const { client } = recording(() => new Response(null, { status: 429, headers: { 'Retry-After': '17' } }))
    await expect(client.submitTotp(flow.id, '000000')).rejects.toMatchObject({ status: 429, retryAfter: 17 })
  })

  test('an unreadable Retry-After is left out', async () => {
    const { client } = recording(() => new Response(null, { status: 429, headers: { 'Retry-After': 'soon' } }))
    await expect(client.submitTotp(flow.id, '000000')).rejects.toMatchObject({ retryAfter: null })
  })

  test('a failed network call is not taken for an answer', async () => {
    const client = createClient({
      version: '1.4.0',
      cookies: () => '',
      fetch: async () => {
        throw new TypeError('Failed to fetch')
      },
    })
    await expect(client.getFlow(flow.id)).rejects.toThrow(TypeError)
  })
})
