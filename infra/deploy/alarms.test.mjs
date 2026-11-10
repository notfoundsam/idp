import assert from 'node:assert/strict'
import { test } from 'node:test'
import { firingAlarms, parseOptions, watchAlarms } from './alarms.mjs'

const ALARMS = { prefix: 'idp-test-', regions: ['eu-central-1', 'us-east-1'] }

// A recorded AWS CLI that answers each describe-alarms in turn, and a clock that only the waits move.
function recorded(answers) {
  const calls = []
  let at = 0
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

test('the release asks both regions for alarms of the environment that fire, and only for those', async () => {
  const aws = recorded([{ MetricAlarms: [] }, { MetricAlarms: [{ AlarmName: 'idp-test-requests-per-address-blocked' }], CompositeAlarms: [] }])
  assert.deepEqual(await firingAlarms(aws.run, ALARMS), ['idp-test-requests-per-address-blocked in us-east-1'])
  assert.deepEqual(aws.calls, [
    'cloudwatch describe-alarms --region eu-central-1 --alarm-name-prefix idp-test- --state-value ALARM --output json',
    'cloudwatch describe-alarms --region us-east-1 --alarm-name-prefix idp-test- --state-value ALARM --output json',
  ])
})

test('quiet alarms for the whole watch let the release through', async () => {
  const aws = recorded([])
  const lines = []
  await watchAlarms(aws.run, ALARMS, { minutes: 2, interval: 30_000, now: aws.now, wait: aws.wait, log: (it) => lines.push(it) })
  assert.equal(aws.calls.length, 2 * 5)
  assert.deepEqual(lines, ['No alarm of idp-test-* fired for 2 minutes'])
})

test('an alarm that fires during the watch fails the release, which then rolls back', async () => {
  const aws = recorded([{}, {}, { MetricAlarms: [{ AlarmName: 'idp-test-server-errors' }] }, {}])
  await assert.rejects(watchAlarms(aws.run, ALARMS, { minutes: 5, now: aws.now, wait: aws.wait }), /idp-test-server-errors in eu-central-1/)
  assert.equal(aws.now(), 30_000)
})

test('the watch takes whole minutes', () => {
  assert.deepEqual(parseOptions(['--config', 'c.json']), { config: 'c.json', minutes: 5 })
  assert.throws(() => parseOptions(['--config', 'c.json', '--minutes', 'soon']), /Usage/)
})
