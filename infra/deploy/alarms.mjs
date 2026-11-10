import { setTimeout as sleep } from 'node:timers/promises'
import { parseArgs } from 'node:util'
import { readConfig } from './config.mjs'
import { aws } from './s3.mjs'

// The alarms of modules/alarms in the region of the environment and in us-east-1, where those of the WAF of CloudFront live.
export async function firingAlarms(run, { prefix, regions }) {
  const firing = []
  for (const region of regions) {
    const output = await run(['cloudwatch', 'describe-alarms', '--region', region, '--alarm-name-prefix', prefix, '--state-value', 'ALARM', '--output', 'json'])
    const parsed = output.trim() === '' ? {} : JSON.parse(output)
    for (const alarm of [...(parsed.MetricAlarms ?? []), ...(parsed.CompositeAlarms ?? [])]) firing.push(`${alarm.AlarmName} in ${region}`)
  }
  return firing
}

// Step 6 of the release order: after the smoke test the release watches the alarms for a while, and one that fires rolls it back.
export async function watchAlarms(run, alarms, { minutes, interval = 30_000, now = Date.now, wait = sleep, log = console.log }) {
  const deadline = now() + minutes * 60_000
  for (;;) {
    const firing = await firingAlarms(run, alarms)
    if (firing.length > 0) throw new Error(`Alarms fire after the release: ${firing.join(', ')}`)
    if (now() >= deadline) break
    await wait(Math.min(interval, deadline - now()))
  }
  log(`No alarm of ${alarms.prefix}* fired for ${minutes} minutes`)
}

export function parseOptions(argv) {
  const { values } = parseArgs({ args: argv, options: { config: { type: 'string' }, minutes: { type: 'string', default: '5' } } })
  if (!values.config || !/^[0-9]+$/.test(values.minutes)) throw new Error('Usage: node alarms.mjs --config <settings> [--minutes <n>]')
  return { config: values.config, minutes: Number(values.minutes) }
}

if (import.meta.main) {
  const options = parseOptions(process.argv.slice(2))
  const config = await readConfig(options.config)
  if (typeof config.alarms?.prefix !== 'string' || !Array.isArray(config.alarms.regions)) throw new Error('The settings of the release miss alarms')
  await watchAlarms(aws, config.alarms, { minutes: options.minutes })
}
