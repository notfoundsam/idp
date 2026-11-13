import assert from 'node:assert/strict'
import { test } from 'node:test'
import { firingAlarms, parseOptions, watchAlarms } from './alarms.mjs'

const ALARMS = { region: 'eu-central-1', names: ['idp-test-backend-tls-failures', 'idp-test-outbox-lag'] }
const START = Date.parse('2026-11-13T10:00:00Z')

const firing = (name, at) => ({ AlarmName: name, StateTransitionedTimestamp: at })

// A recorded AWS CLI that answers each describe-alarms in turn, and a clock that only the waits move.
function recorded(answers) {
  const calls = []
  let at = START
  return {
    calls,
    run: async (args) => {
      calls.push(args.join(' '))
      return JSON.stringify(answers.shift() ?? {})
    },
    now: () => at,
    wait: async (ms) => {
      at += ms
    },
  }
}

const watch = (aws, options = {}) => {
  const lines = []
  const result = watchAlarms(aws.run, ALARMS, { since: START, minutes: 2, now: aws.now, wait: aws.wait, log: (it) => lines.push(it), ...options })
  return { result, lines }
}

test('the release asks only for the alarms it watches, in the region of the environment', async () => {
  const aws = recorded([{ MetricAlarms: [firing('idp-test-outbox-lag', '2026-11-13T10:01:00.000+00:00')], CompositeAlarms: [] }])
  assert.deepEqual(await firingAlarms(aws.run, ALARMS), [{ name: 'idp-test-outbox-lag', at: Date.parse('2026-11-13T10:01:00Z') }])
  assert.deepEqual(aws.calls, [
    'cloudwatch describe-alarms --region eu-central-1 --alarm-names idp-test-backend-tls-failures idp-test-outbox-lag --state-value ALARM --output json',
  ])
})

test('quiet alarms for the whole watch let the release through', async () => {
  const aws = recorded([])
  const { result, lines } = watch(aws)
  await result
  assert.equal(aws.calls.length, 2 * 2 + 1)
  assert.deepEqual(lines, ['No alarm of idp-test-backend-tls-failures, idp-test-outbox-lag went to ALARM for 2 minutes'])
})

test('an alarm that goes to ALARM during the watch fails the release, which then rolls back', async () => {
  const aws = recorded([{}, {}, { MetricAlarms: [firing('idp-test-backend-tls-failures', '2026-11-13T10:00:50Z')] }, {}])
  await assert.rejects(watch(aws, { minutes: 5 }).result, /Alarms fire after the release: idp-test-backend-tls-failures$/)
  assert.equal(aws.now(), START + 60_000)
})

test('an alarm that fired before the release started is named once and does not stop it', async () => {
  const before = { MetricAlarms: [firing('idp-test-outbox-lag', '2026-11-13T09:00:00Z')] }
  const aws = recorded([before, before, before, before, before])
  const { result, lines } = watch(aws)
  await result
  assert.deepEqual(lines, [
    '::warning title=Alarm::idp-test-outbox-lag has fired since before the release started, so it does not stop the release',
    'No alarm of idp-test-backend-tls-failures, idp-test-outbox-lag went to ALARM for 2 minutes',
  ])
})

test('an alarm in ALARM since before the start counts as earlier, though CloudWatch evaluated it again since', async () => {
  const before = { MetricAlarms: [{ ...firing('idp-test-outbox-lag', '2026-11-13T09:00:00Z'), StateUpdatedTimestamp: '2026-11-13T10:01:00Z' }] }
  const aws = recorded([before, before, before, before, before])
  const { result, lines } = watch(aws)
  await result
  assert.match(lines[0], /idp-test-outbox-lag has fired since before the release started/)
})

test('an alarm that fired before, cleared and fires again after the start stops the release', async () => {
  const aws = recorded([{ MetricAlarms: [firing('idp-test-outbox-lag', '2026-11-13T09:00:00Z')] }, {}, { MetricAlarms: [firing('idp-test-outbox-lag', '2026-11-13T10:01:00Z')] }])
  await assert.rejects(watch(aws).result, /idp-test-outbox-lag/)
})

test('an alarm without a readable time counts as caused by the release', async () => {
  const aws = recorded([{ MetricAlarms: [{ AlarmName: 'idp-test-outbox-lag' }] }])
  await assert.rejects(watch(aws).result, /idp-test-outbox-lag/)
})

test('the watch takes whole minutes and the time the release started', () => {
  assert.deepEqual(parseOptions(['--config', 'c.json', '--since', '2026-11-13T10:00:00Z']), { config: 'c.json', since: START, minutes: 5 })
  assert.throws(() => parseOptions(['--config', 'c.json', '--since', '2026-11-13T10:00:00Z', '--minutes', 'soon']), /Usage/)
  assert.throws(() => parseOptions(['--config', 'c.json']), /Usage/)
  assert.throws(() => parseOptions(['--config', 'c.json', '--since', 'yesterday']), /Usage/)
})
