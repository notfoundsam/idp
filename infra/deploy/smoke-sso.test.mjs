import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'
import { fakeIdp, signingKey } from './fake-idp.mjs'
import { serviceProviderOf } from './saml.mjs'
import { parseLogin, parseOptions, smoke, totp } from './smoke-sso.mjs'

const HOST = 'release-smoke.idp.test'
const SP_METADATA = readFileSync(new URL('./smoke-sp-metadata.xml', import.meta.url), 'utf8')
const SP = serviceProviderOf(SP_METADATA)
const USER = { login: 'smoke@release-smoke.test', password: 'correct-password', totpSecret: 'JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP' }

// A clock that a wait moves, so a test that waits for the next TOTP window takes no time.
function clock(start = Date.parse('2026-11-10T00:00:10Z')) {
  let at = start
  return {
    now: () => at,
    wait: async (ms) => {
      at += ms
    },
  }
}

function run(options = {}, { login = USER, appVersion } = {}) {
  const time = clock()
  const idp = fakeIdp({ host: HOST, sp: SP, user: USER, now: time.now, ...options })
  const lines = []
  const result = smoke({ host: HOST, login, spMetadata: SP_METADATA, fetch: idp.fetch, now: time.now, wait: time.wait, appVersion, log: (it) => lines.push(it) })
  return { result, idp, lines, time }
}

test('TOTP codes follow RFC 6238 with SHA-1, six digits and 30 seconds', () => {
  assert.equal(totp('GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ', 59_000), '287082')
  assert.equal(totp('GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ', 1_111_111_109_000), '081804')
})

test('the synthetic user signs in to the test SP and the IdP hands back a Response for it alone', async () => {
  const { result, idp, lines } = run({}, { appVersion: '2026.11.10.1' })
  const verified = await result
  assert.equal(verified.nameId, 'name-id-of-the-user')
  assert.equal(idp.state.requests[0].issuer, SP.entityId)
  assert.deepEqual(idp.state.calls.slice(0, 2), ['GET /saml/metadata', 'GET /saml/sso'])
  assert.match(idp.state.calls.at(-1), /^GET \/saml\/resume\//)
  assert.match(lines.at(-1), /the synthetic user reached https:\/\/release-smoke.invalid\/saml\/metadata/)
  assert.ok(lines.every((it) => !it.includes(USER.login)))
})

test('a code that an earlier run spent in the same window is followed by the code of the next window', async () => {
  const time = clock()
  const { result, idp } = run({ spentCode: totp(USER.totpSecret, time.now()) })
  await result
  assert.equal(idp.state.totps.length, 2)
  assert.notEqual(idp.state.totps[0], idp.state.totps[1])
})

test('a wrong TOTP secret fails after the code of the next window, a wrong password at once', async () => {
  const wrongSecret = run({}, { login: { ...USER, totpSecret: 'GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ' } })
  await assert.rejects(wrongSecret.result, /refused the TOTP code of the synthetic user twice/)
  assert.equal(wrongSecret.idp.state.totps.length, 2)
  await assert.rejects(run({}, { login: { ...USER, password: 'wrong' } }).result, /refused the password/)
  await assert.rejects(run({ locked: true }).result, /answer 410 \(the flow expired, or the synthetic user may not log in now/)
})

test('a Response for another audience fails, though the IdP signed it', async () => {
  await assert.rejects(run({ response: { audience: 'https://other.example/sp' } }).result, /meant for https:\/\/other.example\/sp/)
})

test('a Response changed after signing or signed by a key the metadata does not publish fails', async () => {
  await assert.rejects(run({ tamper: (xml) => xml.replace('name-id-of-the-user', 'name-id-of-another') }).result, /does not match/)
  await assert.rejects(run({ metadataKeys: [signingKey()] }).result, /does not verify with a certificate of the IdP metadata/)
})

test('an IdP that refuses the request or runs another version than the release fails the smoke test', async () => {
  await assert.rejects(run({ accept: false }).result, /refused the AuthnRequest/)
  await assert.rejects(run({ version: '2026.11.9.3' }, { appVersion: '2026.11.10.1' }).result, /answered by app 2026.11.9.3, not by the release 2026.11.10.1/)
})

test('the login of Secrets Manager and the options are checked before anything is sent', () => {
  assert.deepEqual(parseLogin(JSON.stringify(USER)), USER)
  assert.throws(() => parseLogin('{"login":"a"}'), /misses password, totpSecret/)
  assert.throws(() => parseLogin(undefined), /no JSON/)
  assert.deepEqual({ ...parseOptions(['--host', HOST, '--app-version', '1.2']) }, { host: HOST, 'app-version': '1.2' })
  assert.throws(() => parseOptions(['--host', 'https://x']), /Usage/)
})
