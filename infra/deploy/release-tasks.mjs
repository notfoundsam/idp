import { readFile, writeFile } from 'node:fs/promises'
import { parseArgs } from 'node:util'
import { ONE_OFFS, readConfig, SERVICES } from './config.mjs'
import { cliEcs } from './ecs.mjs'
import { derive, environmentOf, imageTagOf, tagsOf } from './task-definitions.mjs'

const pause = (ms) => new Promise((resolve) => setTimeout(resolve, ms))
const KMS_CHECK = 'kms-check'
export const RUNS = ['migrator', KMS_CHECK, 'role-passwords']

// What app runs is what is live: its images are the backend, APP_VERSION the frontend in place, and its tags the commits of both.
export async function live(ecs, config) {
  const described = await ecs.taskDefinition((await ecs.service(config.services.app.service)).taskDefinition)
  const tags = tagsOf(described)
  return {
    release: tags.release,
    backendVersion: imageTagOf(described.definition, 'app'),
    frontendVersion: environmentOf(described.definition, 'app', 'APP_VERSION'),
    minClientVersion: environmentOf(described.definition, 'app', 'MIN_CLIENT_VERSION') ?? '',
    backendCommit: tags['backend-commit'],
    frontendCommit: tags['frontend-commit'],
  }
}

// Each revision derives from the latest one of its family, so a change that Terraform registered since goes out with it. Every
// revision of a service names the one the service ran before, which a rollback returns to.
export async function register(ecs, config, plan) {
  const oneOffs = {}
  for (const name of ONE_OFFS) {
    const { definition } = await ecs.taskDefinition(config.tasks[name].family)
    oneOffs[name] = await ecs.register(derive(definition, { imageTag: plan.backendVersion, main: name, tags: { release: plan.version } }))
  }
  const previous = {}
  const next = {}
  for (const name of SERVICES) {
    const { service, family } = config.services[name]
    previous[name] = (await ecs.service(service)).taskDefinition
    const { definition } = await ecs.taskDefinition(family)
    const app = name === 'app'
    next[name] = await ecs.register(
      derive(definition, {
        imageTag: plan.backendVersion,
        main: name,
        environment: app ? { APP_VERSION: plan.frontendVersion, MIN_CLIENT_VERSION: plan.minClientVersion } : {},
        tags: {
          release: plan.version,
          previous: previous[name],
          ...(app ? { 'backend-commit': plan.backendCommit, 'frontend-commit': plan.frontendCommit } : {}),
        },
      }),
    )
  }
  return { version: plan.version, previous, next, oneOffs }
}

export async function runOneOff(ecs, config, name, { oneOffs = {}, sleep = pause, interval = 10_000, attempts = 180 } = {}) {
  if (!RUNS.includes(name)) throw new Error(`A release runs ${RUNS.join(', ')}, not ${name}`)
  const task = name === KMS_CHECK ? 'cli' : name
  const overrides = name === KMS_CHECK ? { containerOverrides: [{ name: 'cli', command: ['cli', KMS_CHECK] }] } : undefined
  const arn = await ecs.runTask(oneOffs[task] ?? config.tasks[task].family, config.tasks[task].network, overrides)
  for (let attempt = 0; attempt < attempts; attempt++) {
    const described = await ecs.task(arn)
    if (described.lastStatus === 'STOPPED') {
      const failed = (described.containers ?? []).filter((it) => it.exitCode !== 0)
      if (failed.length === 0 && (described.containers ?? []).length > 0) return arn
      const containers = failed.map((it) => `${it.name} exited with ${it.exitCode ?? 'no code'}${it.reason ? ` (${it.reason})` : ''}`)
      throw new Error(`${name} failed: ${[...containers, described.stoppedReason].filter(Boolean).join('; ')}`)
    }
    await sleep(interval)
  }
  throw new Error(`${name} did not stop in ${(attempts * interval) / 1000} seconds`)
}

// The circuit breaker of the service rolls a failed rollout back by itself, which only ends the wait here.
export async function waitForRollout(ecs, service, taskDefinition, { sleep = pause, interval = 15_000, attempts = 160 } = {}) {
  for (let attempt = 0; attempt < attempts; attempt++) {
    const { taskDefinition: current, deployments } = await ecs.service(service)
    const deployment = deployments.find((it) => it.taskDefinition === taskDefinition)
    if (deployment?.rolloutState === 'FAILED' || (deployment === undefined && current !== taskDefinition)) {
      throw new Error(`${service} did not take ${taskDefinition}: ${deployment?.rolloutStateReason ?? `it runs ${current}`}`)
    }
    if (deployment?.status === 'PRIMARY' && deployment.rolloutState === 'COMPLETED' && deployments.length === 1) return
    await sleep(interval)
  }
  throw new Error(`${service} did not finish its rollout in ${(attempts * interval) / 1000} seconds`)
}

export async function rollout(ecs, config, name, taskDefinition, options) {
  const { service } = config.services[name]
  await ecs.updateService(service, taskDefinition)
  await waitForRollout(ecs, service, taskDefinition, options)
}

export function parseOptions(argv) {
  const { positionals, values } = parseArgs({
    args: argv,
    allowPositionals: true,
    options: {
      config: { type: 'string' },
      plan: { type: 'string' },
      state: { type: 'string' },
      task: { type: 'string' },
      service: { type: 'string' },
    },
  })
  const [command, ...rest] = positionals
  const usage =
    'Usage: node release-tasks.mjs register --config <settings> --plan <plan> --state <state>' +
    ` | run --config <settings> --task <${RUNS.join('|')}> [--state <state>]` +
    ` | rollout --config <settings> --state <state> --service <${SERVICES.join('|')}>`
  const needed = { register: ['plan', 'state'], run: ['task'], rollout: ['state', 'service'] }[command]
  if (rest.length > 0 || needed === undefined || !values.config || needed.some((it) => !values[it])) throw new Error(usage)
  if (command === 'run' && !RUNS.includes(values.task)) throw new Error(usage)
  if (command === 'rollout' && !SERVICES.includes(values.service)) throw new Error(usage)
  return { command, ...values }
}

const readJson = async (path) => JSON.parse(await readFile(path, 'utf8'))

if (import.meta.main) {
  const options = parseOptions(process.argv.slice(2))
  const config = await readConfig(options.config)
  const ecs = cliEcs(config.cluster)
  if (options.command === 'register') {
    const state = await register(ecs, config, await readJson(options.plan))
    await writeFile(options.state, `${JSON.stringify(state, null, 2)}\n`)
    console.log(`Registered ${Object.values({ ...state.oneOffs, ...state.next }).join(', ')}`)
  } else if (options.command === 'run') {
    const state = options.state === undefined ? {} : await readJson(options.state)
    console.log(`${options.task} succeeded: ${await runOneOff(ecs, config, options.task, { oneOffs: state.oneOffs })}`)
  } else {
    const state = await readJson(options.state)
    await rollout(ecs, config, options.service, state.next[options.service])
    console.log(`${options.service} runs ${state.next[options.service]}`)
  }
}
