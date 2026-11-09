import assert from 'node:assert/strict'
import { test } from 'node:test'
import { parseConfig } from './config.mjs'
import { applied, config } from './fake-ecs.mjs'
import { live, parseOptions, register, rollout, runOneOff, waitForRollout } from './release-tasks.mjs'
import { environmentOf, imageTagOf, tagsOf } from './task-definitions.mjs'

const HEAD = 'a'.repeat(40)
const OLD = 'b'.repeat(40)
const planned = {
  version: '2026.11.9.8',
  backendVersion: '2026.11.9.8',
  frontendVersion: '2026.11.9.8',
  minClientVersion: '2026.11.1',
  backendCommit: HEAD,
  frontendCommit: OLD,
}
const quick = { sleep: async () => {}, attempts: 3 }

test('an environment that only Terraform applied has no release live', async () => {
  assert.deepEqual(await live(applied(), config), {
    release: undefined,
    backendVersion: '1.0',
    frontendVersion: '1.0',
    minClientVersion: '',
    backendCommit: undefined,
    frontendCommit: undefined,
  })
})

test('register derives a revision of each task and names in each service revision what the service ran before', async () => {
  const ecs = applied()
  const before = Object.fromEntries(await Promise.all(['app', 'worker', 'ops'].map(async (it) => [it, (await ecs.service(it)).taskDefinition])))

  const state = await register(ecs, config, planned)

  assert.deepEqual(state.previous, before)
  assert.deepEqual(Object.keys(state.oneOffs), ['migrator', 'cli'])
  for (const [name, arn] of Object.entries({ ...state.next, ...state.oneOffs })) {
    const { definition } = await ecs.taskDefinition(arn)
    assert.ok(definition.containerDefinitions.every((it) => it.image.endsWith(':2026.11.9.8')), name)
  }
  const app = await ecs.taskDefinition(state.next.app)
  assert.equal(environmentOf(app.definition, 'app', 'APP_VERSION'), '2026.11.9.8')
  assert.equal(environmentOf(app.definition, 'app', 'MIN_CLIENT_VERSION'), '2026.11.1')
  assert.deepEqual(tagsOf(app), { release: '2026.11.9.8', previous: before.app, 'backend-commit': HEAD, 'frontend-commit': OLD })
  assert.deepEqual(tagsOf(await ecs.taskDefinition(state.next.worker)), { release: '2026.11.9.8', previous: before.worker })
  assert.deepEqual(tagsOf(await ecs.taskDefinition(state.oneOffs.cli)), { release: '2026.11.9.8' })
  assert.equal((await ecs.service('app')).taskDefinition, before.app)
  assert.deepEqual(await live(ecs, config), { ...(await live(applied(), config)) })
})

test('a release of the frontend alone keeps the images that run', async () => {
  const ecs = applied()

  const state = await register(ecs, config, { ...planned, backendVersion: '1.0' })

  const app = await ecs.taskDefinition(state.next.app)
  assert.equal(imageTagOf(app.definition, 'app'), '1.0')
  assert.equal(environmentOf(app.definition, 'app', 'APP_VERSION'), '2026.11.9.8')
})

test('a revision that Terraform registered after the running one goes out with the release', async () => {
  const ecs = applied()
  const { definition } = await ecs.taskDefinition('idp-test-worker')
  definition.containerDefinitions[0].environment.push({ name: 'MAIL_HOST', value: 'email-smtp.eu-central-1.amazonaws.com' })
  ecs.seed(definition)

  const state = await register(ecs, config, planned)

  const worker = await ecs.taskDefinition(state.next.worker)
  assert.equal(environmentOf(worker.definition, 'worker', 'MAIL_HOST'), 'email-smtp.eu-central-1.amazonaws.com')
})

test('the release, the rollout and the live state follow each other', async () => {
  const ecs = applied()
  const state = await register(ecs, config, planned)

  for (const name of ['app', 'worker', 'ops']) await rollout(ecs, config, name, state.next[name], quick)

  assert.deepEqual(await live(ecs, config), {
    release: '2026.11.9.8',
    backendVersion: '2026.11.9.8',
    frontendVersion: '2026.11.9.8',
    minClientVersion: '2026.11.1',
    backendCommit: HEAD,
    frontendCommit: OLD,
  })
})

test('a rollout that the circuit breaker rolled back fails the release', async () => {
  const failing = new Set()
  const ecs = applied({ failing })
  const state = await register(ecs, config, planned)
  failing.add(state.next.worker)

  await assert.rejects(rollout(ecs, config, 'worker', state.next.worker, quick), /worker did not take .*tasks failed to start/)
  assert.equal((await ecs.service('worker')).taskDefinition, state.previous.worker)
})

test('a rollout that does not finish in time fails, and one that ECS replaced fails at once', async () => {
  const ecs = applied()
  const state = await register(ecs, config, planned)
  ecs.services.set('ops', {
    taskDefinition: state.next.ops,
    deployments: [
      { status: 'PRIMARY', taskDefinition: state.next.ops, rolloutState: 'IN_PROGRESS' },
      { status: 'ACTIVE', taskDefinition: state.previous.ops, rolloutState: 'COMPLETED' },
    ],
  })
  let waited = 0

  await assert.rejects(waitForRollout(ecs, 'ops', state.next.ops, { sleep: async () => waited++, attempts: 3, interval: 1000 }), /3 seconds/)
  assert.equal(waited, 3)
  await assert.rejects(waitForRollout(ecs, 'app', state.next.app, quick), /it runs .*idp-test-app:1/)
})

test('one-off runs take the revision of the release, and kms-check runs the CLI with its command', async () => {
  const ecs = applied()
  const state = await register(ecs, config, planned)

  await runOneOff(ecs, config, 'kms-check', { ...quick, oneOffs: state.oneOffs })
  await runOneOff(ecs, config, 'migrator', { ...quick, oneOffs: state.oneOffs })
  await runOneOff(ecs, config, 'role-passwords', quick)

  assert.deepEqual(
    ecs.log.filter(([kind]) => kind === 'run'),
    [
      ['run', state.oneOffs.cli, 'cli kms-check'],
      ['run', state.oneOffs.migrator, undefined],
      ['run', 'idp-test-role-passwords', undefined],
    ],
  )
})

test('a one-off run that exits with another code than 0 fails the release, as one that never stops', async () => {
  const ecs = applied({ exits: { 'cli kms-check': 3, 'idp-test-migrator': 1 } })

  await assert.rejects(runOneOff(ecs, config, 'kms-check', quick), /kms-check failed: cli exited with 3; Essential container in task exited/)
  await assert.rejects(runOneOff(ecs, config, 'migrator', quick), /migrator failed: migrator exited with 1/)
  await assert.rejects(runOneOff(ecs, config, 'recovery', quick), /not recovery/)
  const running = applied()
  running.task = async () => ({ lastStatus: 'RUNNING', containers: [] })
  await assert.rejects(runOneOff(running, config, 'migrator', quick), /did not stop/)
})

test('options name a command and what it needs', () => {
  assert.deepEqual(parseOptions(['rollout', '--config', 'c', '--state', 's', '--service', 'app']), {
    command: 'rollout',
    config: 'c',
    state: 's',
    service: 'app',
  })
  assert.throws(() => parseOptions(['rollout', '--config', 'c', '--state', 's', '--service', 'pgbouncer']), /Usage/)
  assert.throws(() => parseOptions(['run', '--config', 'c', '--task', 'recovery']), /Usage/)
  assert.throws(() => parseOptions(['register', '--config', 'c', '--plan', 'p']), /Usage/)
  assert.throws(() => parseOptions(['deploy', '--config', 'c']), /Usage/)
})

test('the settings of modules/release are checked before anything runs', () => {
  assert.deepEqual(parseConfig(JSON.stringify(config)), config)
  const { tasks, ...partial } = config
  assert.throws(() => parseConfig(JSON.stringify({ ...partial, tasks: { migrator: tasks.migrator } })), /miss tasks.cli, tasks.role-passwords/)
  assert.throws(() => parseConfig(JSON.stringify({ ...config, bucket: '' })), /miss bucket/)
  assert.throws(() => parseConfig('{'), /no JSON/)
})
