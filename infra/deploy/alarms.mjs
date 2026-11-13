import { setTimeout as sleep } from 'node:timers/promises'
import { parseArgs } from 'node:util'
import { readConfig } from './config.mjs'
import { aws } from './s3.mjs'

// The alarms that the release watches, named by modules/alarms, with the time each one went to ALARM.
export async function firingAlarms(run, { region, names }) {
  const output = await run(['cloudwatch', 'describe-alarms', '--region', region, '--alarm-names', ...names, '--state-value', 'ALARM', '--output', 'json'])
  const parsed = output.trim() === '' ? {} : JSON.parse(output)
  return [...(parsed.MetricAlarms ?? []), ...(parsed.CompositeAlarms ?? [])].map((alarm) => ({
    name: alarm.AlarmName,
    at: Date.parse(alarm.StateUpdatedTimestamp),
  }))
}

// Step 6 of the release order: after the smoke test the release watches the alarms for a while, and one that went to ALARM
// after the release started rolls it back. One that already fired before is a cause of its own, which the release only names.
export async function watchAlarms(run, alarms, { since, minutes, interval = 30_000, now = Date.now, wait = sleep, log = console.log }) {
  const deadline = now() + minutes * 60_000
  const earlier = new Set()
  for (;;) {
    const firing = await firingAlarms(run, alarms)
    const caused = firing.filter((alarm) => !(alarm.at < since))
    if (caused.length > 0) throw new Error(`Alarms fire after the release: ${caused.map((alarm) => alarm.name).join(', ')}`)
    for (const { name } of firing.filter((alarm) => !earlier.has(alarm.name))) {
      earlier.add(name)
      log(`::warning title=Alarm::${name} has fired since before the release started, so it does not stop the release`)
    }
    if (now() >= deadline) break
    await wait(Math.min(interval, deadline - now()))
  }
  log(`No alarm of ${alarms.names.join(', ')} went to ALARM for ${minutes} minutes`)
}

export function parseOptions(argv) {
  const { values } = parseArgs({
    args: argv,
    options: { config: { type: 'string' }, since: { type: 'string' }, minutes: { type: 'string', default: '5' } },
  })
  const since = Date.parse(values.since ?? '')
  if (!values.config || Number.isNaN(since) || !/^[0-9]+$/.test(values.minutes)) {
    throw new Error('Usage: node alarms.mjs --config <settings> --since <ISO time the release started> [--minutes <n>]')
  }
  return { config: values.config, since, minutes: Number(values.minutes) }
}

if (import.meta.main) {
  const options = parseOptions(process.argv.slice(2))
  const config = await readConfig(options.config)
  const names = config.alarms?.names
  if (!Array.isArray(names) || names.length === 0) throw new Error('The settings of the release name no alarms to watch')
  await watchAlarms(aws, { region: config.region, names }, { since: options.since, minutes: options.minutes })
}
