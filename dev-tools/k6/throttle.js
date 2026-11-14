// Starts flows in globex faster than the limit per address, which is 30 a minute times THROTTLE_MULTIPLIER: a bucket of 3000 that
// refills 50 a second by default. 150 a second empties it in half a minute. Throttle must refuse the rest with 429 and Retry-After,
// and nothing may fail. globex leaves the counters of acme, which the e2e tests use, untouched. The bucket refills within a minute.
import http from 'k6/http'
import { check } from 'k6'
import { Counter } from 'k6/metrics'
import { GLOBEX } from './lib.js'

const refused = new Counter('throttle_refused')

export const options = {
  scenarios: {
    flood: {
      executor: 'constant-arrival-rate',
      rate: Number(__ENV.RATE || 150),
      timeUnit: '1s',
      duration: __ENV.DURATION || '40s',
      preAllocatedVUs: 20,
      maxVUs: 100,
    },
  },
  thresholds: {
    checks: ['rate==1'],
    throttle_refused: ['count>0'],
  },
}

export default function () {
  const reply = http.post(`${GLOBEX}/api/flows`, null, {
    headers: { 'Content-Type': 'application/json' },
    tags: { name: 'start flow' },
    responseCallback: http.expectedStatuses(201, 429),
  })
  if (reply.status === 429) refused.add(1)
  check(reply, {
    'a flow starts or Throttle refuses it': (r) => r.status === 201 || r.status === 429,
    'a refusal says when to come back': (r) => r.status !== 429 || Number(r.headers['Retry-After']) >= 1,
  })
}
