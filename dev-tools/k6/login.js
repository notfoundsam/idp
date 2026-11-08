// A password login of alice up to the second factor, which is where the hash is computed. The TOTP code counts once per window, so
// a repeated login cannot get past it, and the hash is the cost worth measuring. One VU: of two concurrent attempts of one user only
// one goes on, so the logins of alice follow each other.
import { check } from 'k6'
import { ACME, ALICE, post, startFlow } from './lib.js'

export const options = {
  scenarios: {
    password: { executor: 'constant-vus', vus: 1, duration: __ENV.DURATION || '30s' },
  },
  thresholds: {
    checks: ['rate>0.99'],
    'http_req_duration{name:password}': ['p(95)<2000'],
  },
}

export default function () {
  const flow = startFlow(ACME)
  const login = post(`${ACME}/api/flows/${flow}/login`, { login: ALICE.login }, 'login')
  check(login, { 'the login asks for the password': (r) => r.status === 200 && r.json('step') === 'PASSWORD' })
  const password = post(`${ACME}/api/flows/${flow}/password`, { password: ALICE.password }, 'password')
  check(password, { 'the password passes to the second factor': (r) => r.status === 200 && r.json('step') === 'SECOND_FACTOR' })
}
