// SP-initiated logins of alice at SimpleSAMLphp, as a browser with a live session makes them: the signed AuthnRequest, the session
// that proves the factors, and a signed Response posted back to the SP. One full login in setup gives the session, since a TOTP code
// counts once.
import http from 'k6/http'
import { check, fail } from 'k6'
import { ACME, ALICE, SP_WHOAMI, logIn, post } from './lib.js'

const BOUNCE = /url=(\/saml\/sso\/[0-9a-f-]{36})/
const FLOW = /\/login\/\?flow=([0-9a-f-]{36})$/

export const options = {
  scenarios: {
    sso: {
      executor: 'constant-arrival-rate',
      rate: Number(__ENV.RATE || 2),
      timeUnit: '1s',
      duration: __ENV.DURATION || '30s',
      preAllocatedVUs: 4,
      maxVUs: 20,
    },
  },
  thresholds: {
    checks: ['rate>0.99'],
    'http_req_duration{name:resume}': ['p(95)<2000'],
  },
}

export function setup() {
  const cookies = logIn(ACME, ALICE)
  return { session: cookies['__Host-session'][0], csrf: cookies['__Host-csrf'][0] }
}

function field(html, name) {
  const found = new RegExp(`name="${name}" value="([^"]*)"`).exec(html)
  return found === null ? null : found[1].replace(/&quot;/g, '"').replace(/&#39;/g, "'").replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&amp;/g, '&')
}

export default function ({ session, csrf }) {
  const jar = http.cookieJar()
  jar.clear('https://sp.idp.test')
  jar.set(ACME, '__Host-session', session, { path: '/', secure: true })
  jar.set(ACME, '__Host-csrf', csrf, { path: '/', secure: true })

  const request = http.get(SP_WHOAMI, { tags: { name: 'sp start' } })
  const bounce = BOUNCE.exec(request.body)
  if (!check(request, { 'the SP sends an AuthnRequest the IdP accepts': () => bounce !== null })) fail(`sso answered ${request.status}`)

  const proceeded = http.get(`${ACME}${bounce[1]}`, { redirects: 0, tags: { name: 'sso proceed' } })
  const flow = FLOW.exec(proceeded.headers.Location || '')
  if (!check(proceeded, { 'the IdP starts a flow': () => flow !== null })) fail(`proceed answered ${proceeded.status}`)

  const step = http.get(`${ACME}/api/flows/${flow[1]}`, { tags: { name: 'flow' } })
  check(step, { 'the session proves what the SP asks': (r) => r.json('step') === 'COMPLETE' })
  const complete = post(`${ACME}/api/flows/${flow[1]}/complete`, undefined, 'complete', { 'X-CSRF-Token': csrf })
  const resume = http.get(`${ACME}${complete.json('returnUrl')}`, { tags: { name: 'resume' } })
  const response = field(resume.body, 'SAMLResponse')
  if (!check(resume, { 'the IdP answers with a signed Response': () => response !== null })) fail(`resume answered ${resume.status}`)

  const action = /action="([^"]+)"/.exec(resume.body)[1].replace(/&amp;/g, '&')
  const acs = http.post(action, { SAMLResponse: response, RelayState: field(resume.body, 'RelayState') }, { tags: { name: 'sp acs' } })
  check(acs, { 'the SP accepts the Response': (r) => r.status === 200 && r.json('nameId') !== null })
}
