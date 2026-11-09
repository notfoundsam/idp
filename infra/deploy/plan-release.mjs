import { writeFile } from 'node:fs/promises'
import { parseArgs } from 'node:util'
import { changedPaths, classify } from './changes.mjs'
import { readConfig } from './config.mjs'
import { cliEcs } from './ecs.mjs'
import { live as liveBackend } from './release-tasks.mjs'
import { liveRelease } from './releases.mjs'
import { cliS3 } from './s3.mjs'

const NUMERIC = /^[0-9]+(\.[0-9]+)*$/

// ClientVersionFilter compares versions part by part as numbers, so a release is named by its date and the run that made it.
export function versionOf(date, runNumber) {
  if (!/^[1-9][0-9]*$/.test(String(runNumber))) throw new Error(`Unusable run number: ${runNumber}`)
  return `${date.getUTCFullYear()}.${date.getUTCMonth() + 1}.${date.getUTCDate()}.${runNumber}`
}

export function isOlder(version, than) {
  const mine = version.split('.').map(Number)
  const theirs = than.split('.').map(Number)
  const index = mine.findIndex((part, at) => at < theirs.length && part !== theirs[at])
  return index === -1 ? mine.length < theirs.length : mine[index] < theirs[index]
}

// A side goes out when a path of it changed since the commit that is live, so a rollback moves the base back too. A release of
// the frontend alone still rolls the services out, for APP_VERSION, with the images that run.
export function plan({ live, liveFrontend, frontendChanges, backendChanges, version, commit, full = false, skipSmoke = false, minClientVersion = '' }) {
  const first = live.release === undefined && liveFrontend === undefined
  if (skipSmoke && !first) throw new Error('Only the first release of an environment may skip the smoke test')
  if (minClientVersion !== '' && !NUMERIC.test(minClientVersion)) throw new Error(`Unreadable min-client-version: ${minClientVersion}`)
  const frontend = first || full || frontendChanges.frontend
  const backend = first || full || backendChanges.backend
  const frontendVersion = frontend ? version : live.frontendVersion
  const minimum = minClientVersion === '' ? live.minClientVersion : minClientVersion
  if (minimum !== '' && (!NUMERIC.test(frontendVersion ?? '') || isOlder(frontendVersion, minimum))) {
    throw new Error(`min-client-version ${minimum} is above the frontend ${frontendVersion} that will be live, so every page would get 426`)
  }
  return {
    version,
    first,
    frontend,
    backend,
    rollout: frontend || backend || minimum !== live.minClientVersion,
    smoke: !skipSmoke,
    frontendVersion,
    backendVersion: backend ? version : live.backendVersion,
    minClientVersion: minimum,
    frontendCommit: frontend ? commit : live.frontendCommit,
    backendCommit: backend ? commit : live.backendCommit,
  }
}

const kebab = (key) => key.replace(/[A-Z]/g, (letter) => `-${letter.toLowerCase()}`)

export const outputsOf = (planned) =>
  Object.entries(planned)
    .map(([key, value]) => `${kebab(key)}=${value}`)
    .join('\n')

export function parseOptions(argv) {
  const { values } = parseArgs({
    args: argv,
    options: {
      config: { type: 'string' },
      commit: { type: 'string' },
      'run-number': { type: 'string' },
      out: { type: 'string' },
      full: { type: 'boolean', default: false },
      'skip-smoke': { type: 'boolean', default: false },
      'min-client-version': { type: 'string', default: '' },
    },
  })
  if (!values.config || !/^[0-9a-f]{40}$/.test(values.commit ?? '') || !values['run-number'] || !values.out) {
    throw new Error(
      'Usage: node plan-release.mjs --config <settings> --commit <sha> --run-number <n> --out <plan>' +
        ' [--full] [--skip-smoke] [--min-client-version <version>]',
    )
  }
  return values
}

if (import.meta.main) {
  const options = parseOptions(process.argv.slice(2))
  const config = await readConfig(options.config)
  const live = await liveBackend(cliEcs(config.cluster), config)
  const planned = plan({
    live,
    liveFrontend: await liveRelease(cliS3(config.bucket)),
    frontendChanges: classify(await changedPaths(live.frontendCommit, options.commit)),
    backendChanges: classify(await changedPaths(live.backendCommit, options.commit)),
    version: versionOf(new Date(), options['run-number']),
    commit: options.commit,
    full: options.full,
    skipSmoke: options['skip-smoke'],
    minClientVersion: options['min-client-version'],
  })
  await writeFile(options.out, `${JSON.stringify(planned, null, 2)}\n`)
  console.log(outputsOf(planned))
}
