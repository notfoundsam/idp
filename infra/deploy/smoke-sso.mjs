import { createHmac, randomBytes } from 'node:crypto'
import { readFile } from 'node:fs/promises'
import { setTimeout as sleep } from 'node:timers/promises'
import { parseArgs } from 'node:util'
import { authnRequest, identityProviderOf, redirectUrl, serviceProviderOf, SmokeFailure, verifyResponse } from './saml.mjs'

const TOTP_STEP = 30_000
const REQUEST_TIMEOUT = 30_000
const BASE32 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567'
const UUID = '[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}'
export const DEFAULT_SP = new URL('./smoke-sp-metadata.xml', import.meta.url)

const refuse = (why) => {
  throw new SmokeFailure(why)
}

function base32(secret) {
  let bits = ''
  for (const char of secret.replace(/[\s=]/g, '').toUpperCase()) {
    const value = BASE32.indexOf(char)
    if (value < 0) refuse('The TOTP secret of the synthetic user is not base32')
    bits += value.toString(2).padStart(5, '0')
  }
  return Buffer.from((bits.match(/.{8}/g) ?? []).map((byte) => parseInt(byte, 2)))
}

// RFC 6238 with the parameters of TotpService: SHA-1, six digits, 30 seconds.
export function totp(secret, at) {
  const counter = Buffer.alloc(8)
  counter.writeBigUInt64BE(BigInt(Math.floor(at / TOTP_STEP)))
  const mac = createHmac('sha1', base32(secret)).update(counter).digest()
  const offset = mac.at(-1) & 0x0f
  return ((mac.readUInt32BE(offset) & 0x7fffffff) % 1_000_000).toString().padStart(6, '0')
}

// The secret of Secrets Manager, see docs/release.md.
export function parseLogin(body) {
  let login
  try {
    login = JSON.parse(body ?? '')
  } catch {
    refuse('SMOKE_LOGIN holds no JSON')
  }
  const missing = ['login', 'password', 'totpSecret'].filter((key) => typeof login?.[key] !== 'string' || login[key] === '')
  if (missing.length > 0) refuse(`SMOKE_LOGIN misses ${missing.join(', ')}`)
  return login
}

const unescapeHtml = (value) =>
  value.replace(/&quot;/g, '"').replace(/&#39;/g, "'").replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&amp;/g, '&')

function formOf(html) {
  const field = (name) => {
    const found = new RegExp(`<input type="hidden" name="${name}" value="([^"]*)">`).exec(html)
    return found === null ? undefined : unescapeHtml(found[1])
  }
  const action = /<form method="post" action="([^"]+)">/.exec(html)
  if (action === null || field('SAMLResponse') === undefined) refuse('The IdP answered the login with no form to the SP')
  return { action: unescapeHtml(action[1]), samlResponse: field('SAMLResponse'), relayState: field('RelayState') }
}

// One browser on the host of the tenant: it keeps the cookies the IdP sets and follows no redirect by itself.
function browser(origin, fetch, appVersion) {
  const cookies = new Map()
  return async (method, path, body) => {
    const headers = { 'User-Agent': 'idp-release-smoke', Accept: body === undefined ? 'text/html,application/json' : 'application/json' }
    if (cookies.size > 0) headers.Cookie = [...cookies].map(([name, value]) => `${name}=${value}`).join('; ')
    if (body !== undefined) headers['Content-Type'] = 'application/json'
    const response = await fetch(new URL(path, origin), {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
      redirect: 'manual',
      signal: AbortSignal.timeout(REQUEST_TIMEOUT),
    })
    for (const cookie of response.headers.getSetCookie()) {
      const [pair] = cookie.split(';')
      const at = pair.indexOf('=')
      cookies.set(pair.slice(0, at).trim(), pair.slice(at + 1).trim())
    }
    const served = response.headers.get('X-App-Version')
    if (appVersion !== undefined && path.startsWith('/api/') && served !== appVersion) {
      refuse(`${method} ${path} was answered by app ${served ?? 'without a version'}, not by the release ${appVersion}`)
    }
    return { status: response.status, location: response.headers.get('Location'), text: await response.text() }
  }
}

const json = (reply, what) => {
  try {
    return JSON.parse(reply.text)
  } catch {
    return refuse(`${what} answered ${reply.status} with no JSON`)
  }
}

// A locked user reads like a lost flow, so that a guess learns nothing, and failed smoke tests count towards that lock.
const MEANINGS = {
  410: 'the flow expired, or the synthetic user may not log in now, for example after failed attempts',
  426: 'a client version below the minimum',
  429: 'a limit of Throttle',
}

async function step(request, flow, name, body, what) {
  const reply = await request('POST', `/api/flows/${flow}/${name}`, body)
  if (reply.status !== 200) return { status: reply.status, step: `an answer ${reply.status}${MEANINGS[reply.status] ? ` (${MEANINGS[reply.status]})` : ''}` }
  return { status: 200, ...json(reply, what) }
}

// A login of the synthetic user through the test SP, as a browser makes it: the AuthnRequest by HTTP-Redirect, the flow API of the
// login page with the password and a TOTP code, and the form the IdP hands back for the ACS, checked as the SP would check it.
export async function smoke({ host, login, spMetadata, fetch = globalThis.fetch, now = Date.now, wait = sleep, appVersion, log = console.log }) {
  const origin = `https://${host}`
  const request = browser(origin, fetch, appVersion)
  const sp = serviceProviderOf(spMetadata)

  const metadata = await request('GET', '/saml/metadata')
  if (metadata.status !== 200) refuse(`The IdP metadata answered ${metadata.status}`)
  const idp = identityProviderOf(metadata.text)
  if (new URL(idp.sso).origin !== origin) refuse(`The IdP takes requests at ${idp.sso}, not at ${origin}`)

  const requestId = `_${randomBytes(16).toString('hex')}`
  const relayState = randomBytes(12).toString('hex')
  const xml = authnRequest({ id: requestId, issueInstant: new Date(now()), destination: idp.sso, sp })
  const sso = await request('GET', redirectUrl(idp.sso, xml, relayState))
  const bounce = new RegExp(`url=(/saml/sso/${UUID})"`).exec(sso.text)
  if (sso.status !== 200 || bounce === null) refuse(`The IdP refused the AuthnRequest of the test SP with ${sso.status}`)
  const proceeded = await request('GET', bounce[1])
  const flow = new RegExp(`^/login/\\?flow=(${UUID})$`).exec(proceeded.location ?? '')?.[1]
  if (proceeded.status !== 303 || flow === undefined) refuse(`The IdP started no login for the request, it answered ${proceeded.status}`)

  const identified = await step(request, flow, 'login', { login: login.login }, 'The login')
  if (identified.step !== 'PASSWORD') refuse(`After the login the IdP gave ${identified.step}, not the password step`)
  const password = await step(request, flow, 'password', { password: login.password }, 'The password')
  if (password.status === 422) refuse('The IdP refused the password of the synthetic user')
  if (password.step !== 'SECOND_FACTOR' || !password.factors.includes('TOTP')) refuse(`After the password the IdP gave ${password.step}, not a TOTP step`)
  // A code counts once, so a code an earlier run spent in the same window is refused, and the next window brings a new one.
  let second = await step(request, flow, 'totp', { code: totp(login.totpSecret, now()) }, 'The TOTP code')
  if (second.status === 422) {
    await wait(TOTP_STEP - (now() % TOTP_STEP) + 1_000)
    second = await step(request, flow, 'totp', { code: totp(login.totpSecret, now()) }, 'The TOTP code')
  }
  if (second.status === 422) refuse('The IdP refused the TOTP code of the synthetic user twice')
  if (second.step !== 'COMPLETE') refuse(`After the TOTP code the IdP gave ${second.step}, not the end of the login`)
  const completed = await request('POST', `/api/flows/${flow}/complete`)
  const returnUrl = completed.status === 200 ? json(completed, 'Completing the flow').returnUrl : undefined
  if (typeof returnUrl !== 'string' || !new RegExp(`^/saml/resume/${UUID}$`).test(returnUrl)) {
    refuse(`Completing the flow answered ${completed.status} without the way back to the SP`)
  }

  const resumed = await request('GET', returnUrl)
  if (resumed.status !== 200) refuse(`The IdP answered the return from the login with ${resumed.status}`)
  const form = formOf(resumed.text)
  if (form.action !== sp.acs) refuse(`The form goes to ${form.action}, not to the ACS ${sp.acs}`)
  if (form.relayState !== relayState) refuse('The form does not carry the RelayState of the request back')
  const verified = verifyResponse(Buffer.from(form.samlResponse, 'base64').toString('utf8'), { idp, sp, requestId, now: now() })
  for (const signature of verified.signatures) {
    log(`ok   ${signature.part} signed with ${signature.algorithm} by ${signature.fingerprint}`)
  }
  log(`ok   ${login.login} reached ${sp.entityId} through ${host} with MFA`)
  return verified
}

export function parseOptions(argv) {
  const { values } = parseArgs({ args: argv, options: { host: { type: 'string' }, sp: { type: 'string' }, 'app-version': { type: 'string' } } })
  if (!/^[a-z0-9.-]+$/.test(values.host ?? '')) {
    throw new Error('Usage: SMOKE_LOGIN=<json> node smoke-sso.mjs --host <tenant host> [--sp <SP metadata>] [--app-version <version>]')
  }
  return values
}

if (import.meta.main) {
  try {
    const options = parseOptions(process.argv.slice(2))
    const login = parseLogin(process.env.SMOKE_LOGIN)
    if (process.env.GITHUB_ACTIONS === 'true') {
      console.log(`::add-mask::${login.password}`)
      console.log(`::add-mask::${login.totpSecret}`)
    }
    await smoke({
      host: options.host,
      login,
      spMetadata: await readFile(options.sp ?? DEFAULT_SP, 'utf8'),
      appVersion: options['app-version'],
    })
  } catch (failure) {
    console.error(process.env.GITHUB_ACTIONS === 'true' ? `::error title=Smoke SSO::${failure.message}` : `FAIL ${failure.message}`)
    process.exitCode = 1
  }
}
