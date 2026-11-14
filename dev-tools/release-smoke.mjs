// The part of dev-tools/release-smoke.sh that a person does in the console in production, see docs/release.md: the first admin
// accepts the invitation, registers the test SP of infra/deploy and invites the synthetic user, who sets a password and TOTP, and
// the admin assigns that user to the SP. Writes the login of that user as SMOKE_LOGIN expects it.
import { randomBytes } from 'node:crypto'
import { readFile, writeFile } from 'node:fs/promises'
import { setTimeout as sleep } from 'node:timers/promises'
import { parseArgs } from 'node:util'
import { serviceProviderOf } from '../infra/deploy/saml.mjs'
import { DEFAULT_SP, totp } from '../infra/deploy/smoke-sso.mjs'

const MAILPIT = 'https://mail.idp.test'

const { values } = parseArgs({ options: { host: { type: 'string' }, admin: { type: 'string' }, since: { type: 'string' }, out: { type: 'string' } } })
if (!values.host || !values.admin || !values.since || !values.out) {
  throw new Error('Usage: node release-smoke.mjs --host <tenant host> --admin <contact> --since <epoch ms> --out <login file>')
}
const origin = `https://${values.host}`

function browser() {
  const cookies = new Map()
  return async (method, path, { json, form } = {}) => {
    const headers = {}
    if (cookies.size > 0) headers.Cookie = [...cookies].map(([name, value]) => `${name}=${value}`).join('; ')
    if (cookies.has('__Host-csrf')) headers['X-CSRF-Token'] = cookies.get('__Host-csrf')
    if (json !== undefined) headers['Content-Type'] = 'application/json'
    const response = await fetch(new URL(path, origin), { method, headers, body: json === undefined ? form : JSON.stringify(json) })
    for (const cookie of response.headers.getSetCookie()) {
      const [pair] = cookie.split(';')
      const at = pair.indexOf('=')
      cookies.set(pair.slice(0, at), pair.slice(at + 1))
    }
    const text = await response.text()
    return { status: response.status, body: text === '' ? undefined : JSON.parse(text) }
  }
}

const expect = (reply, status, what) => {
  if (reply.status !== status) throw new Error(`${what} answered ${reply.status}: ${JSON.stringify(reply.body)}`)
  return reply.body
}

async function invitationToken(to) {
  const query = encodeURIComponent(`to:"${to}" subject:"You are invited"`)
  for (const deadline = Date.now() + 60_000; Date.now() < deadline; await sleep(1_000)) {
    const found = await (await fetch(`${MAILPIT}/api/v1/search?query=${query}`)).json()
    const mail = found.messages.find((it) => Date.parse(it.Created) >= Number(values.since))
    if (mail === undefined) continue
    const message = await (await fetch(`${MAILPIT}/api/v1/message/${mail.ID}`)).json()
    const token = /\/invite#token=([A-Za-z0-9_-]+)/.exec(message.Text)?.[1]
    if (token === undefined) throw new Error(`The invitation to ${to} has no link`)
    return token
  }
  throw new Error(`No invitation reached ${to}`)
}

// The invitation, a password and TOTP, as the login page walks them. Returns the browser with its session.
async function accept(to) {
  const request = browser()
  const token = await invitationToken(to)
  const flow = expect(await request('POST', '/api/flows'), 201, 'Starting a flow').id
  const password = randomBytes(16).toString('hex')
  const invited = expect(await request('POST', `/api/flows/${flow}/invitation`, { json: { token, password } }), 200, 'The invitation')
  if (invited.step !== 'ENROLL_MFA') throw new Error(`After the invitation the flow asks for ${invited.step}, not for MFA`)
  const { secret } = expect(await request('POST', `/api/flows/${flow}/enrollment/totp`), 200, 'The TOTP enrollment')
  const enrolled = expect(
    await request('POST', `/api/flows/${flow}/enrollment/totp/confirmation`, { json: { code: totp(secret, Date.now()) } }),
    200,
    'The TOTP confirmation',
  )
  if (enrolled.step !== 'COMPLETE') throw new Error(`After TOTP the flow asks for ${enrolled.step}`)
  expect(await request('POST', `/api/flows/${flow}/complete`), 200, 'Completing the flow')
  return { request, password, secret }
}

for (const deadline = Date.now() + 90_000; ; await sleep(2_000)) {
  if ((await fetch(`${origin}/saml/metadata`)).status === 200) break
  if (Date.now() > deadline) throw new Error(`${origin}/saml/metadata does not answer, is the tenant active?`)
}

const admin = await accept(values.admin)
const metadata = await readFile(DEFAULT_SP)
const { entityId } = serviceProviderOf(metadata.toString('utf8'))
const upload = new FormData()
upload.append('name', 'Release smoke test')
upload.append('metadata', new Blob([metadata], { type: 'application/samlmetadata+xml' }), 'smoke-sp-metadata.xml')
const registered = await admin.request('POST', '/api/admin/service-providers', { form: upload })
const sp =
  registered.status === 409
    ? expect(await admin.request('GET', '/api/admin/service-providers'), 200, 'The list of SPs').serviceProviders.find((it) => it.entityId === entityId).id
    : expect(registered, 201, 'Registering the test SP').id

const user = `smoke-${Date.now()}@release-smoke.test`
const invited = expect(await admin.request('POST', '/api/admin/users', { json: { email: user, role: 'USER' } }), 201, 'Inviting the synthetic user').id
const synthetic = await accept(user)
expect(await admin.request('POST', `/api/admin/service-providers/${sp}/users`, { json: { userIds: [invited] } }), 204, 'Assigning the synthetic user')

await writeFile(values.out, `${JSON.stringify({ login: user, password: synthetic.password, totpSecret: synthetic.secret })}\n`, { mode: 0o600 })
console.log(`${user} of ${values.host} may use ${entityId}, its login is in ${values.out}`)
