// Shared by the scenarios. The fixtures are those of dev-tools/README.md, so a run needs `make up` and nothing else.
import crypto from 'k6/crypto'
import http from 'k6/http'
import { check, fail, sleep } from 'k6'

export const ACME = 'https://acme.idp.test'
export const GLOBEX = 'https://globex.idp.test'
export const SP_WHOAMI = 'https://sp.idp.test/simplesaml/whoami.php'
export const ALICE = { login: 'alice@acme.test', password: 'local-fixture-password-acme', totp: 'JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP' }

const JSON_HEADERS = { 'Content-Type': 'application/json' }
const BASE32 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567'

/** A path with ids would make a series per flow, so every request is named by its step. */
export function post(url, body, name, headers = {}) {
  return http.post(url, body === undefined ? null : JSON.stringify(body), {
    headers: Object.assign({}, JSON_HEADERS, headers),
    tags: { name },
  })
}

export function startFlow(origin) {
  const reply = post(`${origin}/api/flows`, undefined, 'start flow')
  if (!check(reply, { 'flow started': (r) => r.status === 201 })) fail(`starting a flow answered ${reply.status}`)
  return reply.json('id')
}

function decodeBase32(text) {
  const bytes = []
  let buffer = 0
  let bits = 0
  for (const char of text) {
    buffer = (buffer << 5) | BASE32.indexOf(char)
    bits += 5
    if (bits >= 8) {
      bytes.push((buffer >> (bits - 8)) & 0xff)
      bits -= 8
    }
  }
  return new Uint8Array(bytes).buffer
}

export function totp(secret, at = Date.now()) {
  const counter = new Uint8Array(8)
  let step = Math.floor(at / 30000)
  for (let index = 7; index >= 0; index--) {
    counter[index] = step & 0xff
    step = Math.floor(step / 256)
  }
  const mac = new Uint8Array(crypto.hmac('sha1', decodeBase32(secret), counter.buffer, 'binary'))
  const offset = mac[mac.length - 1] & 0x0f
  const code = ((mac[offset] & 0x7f) << 24) | (mac[offset + 1] << 16) | (mac[offset + 2] << 8) | mac[offset + 3]
  return String(code % 1000000).padStart(6, '0')
}

/** A code counts once, so a code an earlier run spent in this window is refused, and the login waits for the next one. */
export function logIn(origin, user) {
  const flow = startFlow(origin)
  post(`${origin}/api/flows/${flow}/login`, { login: user.login }, 'login')
  const password = post(`${origin}/api/flows/${flow}/password`, { password: user.password }, 'password')
  if (password.status !== 200) fail(`the password was refused with ${password.status}`)
  for (let attempt = 0; attempt < 2; attempt++) {
    const reply = post(`${origin}/api/flows/${flow}/totp`, { code: totp(user.totp) }, 'totp')
    if (reply.status === 200) {
      const complete = post(`${origin}/api/flows/${flow}/complete`, undefined, 'complete')
      if (complete.status !== 200) fail(`the login did not complete: ${complete.status}`)
      return http.cookieJar().cookiesForURL(origin)
    }
    sleep(31 - ((Date.now() / 1000) % 30))
  }
  fail('the TOTP code was refused twice')
}
