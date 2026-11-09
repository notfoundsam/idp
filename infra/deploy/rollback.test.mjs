import assert from 'node:assert/strict'
import { test } from 'node:test'
import { applied, config } from './fake-ecs.mjs'
import { DAY, fakeS3, release } from './fake-s3.mjs'
import { activate } from './release-assets.mjs'
import { live, register, rollout } from './release-tasks.mjs'
import { liveRelease } from './releases.mjs'
import { parseOptions, previousTargets, rollback } from './rollback.mjs'
import { imageTagOf } from './task-definitions.mjs'

const now = new Date('2026-11-09T12:00:00Z')
const quick = { sleep: async () => {}, attempts: 3 }
const pages = (version) => ['login/index.html', `login/assets/index-${version}.js`]

function planOf(version, { backend = true } = {}) {
  return {
    version,
    backendVersion: backend ? version : '2026.11.1.1',
    frontendVersion: version,
    minClientVersion: '',
    backendCommit: 'a'.repeat(40),
    frontendCommit: 'a'.repeat(40),
  }
}

// Two releases out in full, the second one live, with every change in one log.
async function twoReleases(options) {
  const ecs = applied(options)
  const s3 = fakeS3({ ...release('2026.11.1.1', pages('2026.11.1.1')), ...release('2026.11.9.8', pages('2026.11.9.8')) })
  const put = s3.put
  s3.put = async (key, body, type) => {
    ecs.log.push(['put', key])
    return put(key, body, type)
  }
  const states = []
  for (const [at, version] of [[now.getTime() - 8 * DAY, '2026.11.1.1'], [now.getTime() - DAY, '2026.11.9.8']]) {
    const state = await register(ecs, config, planOf(version))
    for (const name of ['app', 'worker', 'ops']) await rollout(ecs, config, name, state.next[name], quick)
    await activate(s3, { version, now: new Date(at) })
    states.push(state)
  }
  ecs.log.length = 0
  return { ecs, s3, states }
}

test('a rollback puts back the pages of the previous release first, then its revisions, then its one-off tasks', async () => {
  const { ecs, s3, states } = await twoReleases()

  await rollback(ecs, s3, config, await previousTargets(ecs, config), { now, log: () => {}, wait: quick })

  const kinds = ecs.log.map(([kind]) => kind)
  assert.deepEqual(kinds, ['put', 'put', 'update', 'update', 'update', 'register', 'register'])
  assert.deepEqual(ecs.log.slice(0, 2), [['put', 'releases/2026.11.1.1/manifest.json'], ['put', 'login/index.html']])
  assert.equal(s3.body('login/index.html'), 'page of 2026.11.1.1')
  for (const name of ['app', 'worker', 'ops']) assert.equal((await ecs.service(name)).taskDefinition, states[0].next[name])
  assert.equal((await liveRelease(s3)).version, '2026.11.1.1')
  assert.equal((await live(ecs, config)).release, '2026.11.1.1')
  for (const name of ['idp-test-migrator', 'idp-test-cli']) {
    assert.equal(imageTagOf((await ecs.taskDefinition(name)).definition, name.slice('idp-test-'.length)), '2026.11.1.1')
  }
})

test('a second rollback goes one release further back, and past the first release nothing is left to return to', async () => {
  const { ecs, s3 } = await twoReleases()
  await rollback(ecs, s3, config, await previousTargets(ecs, config), { now, log: () => {}, wait: quick })

  const targets = await previousTargets(ecs, config)
  await rollback(ecs, s3, config, targets, { now, log: () => {}, wait: quick })

  assert.equal((await live(ecs, config)).release, undefined)
  assert.equal(s3.body('login/index.html'), 'page of 2026.11.1.1')
  await assert.rejects(previousTargets(ecs, config), /names no revision to return to/)
})

test('a release that failed before its pages went live returns the services alone', async () => {
  const failing = new Set()
  const { ecs, s3 } = await twoReleases({ failing })
  const state = await register(ecs, config, planOf('2026.11.9.9'))
  failing.add(state.next.worker)
  await rollout(ecs, config, 'app', state.next.app, quick)
  await assert.rejects(rollout(ecs, config, 'worker', state.next.worker, quick))
  ecs.log.length = 0

  await rollback(ecs, s3, config, state.previous, { now, log: () => {}, wait: quick })

  assert.deepEqual(ecs.log.map(([kind, name]) => `${kind} ${kind === 'update' ? name : ''}`.trim()), ['update app', 'register', 'register'])
  assert.equal((await liveRelease(s3)).version, '2026.11.9.8')
  assert.equal((await live(ecs, config)).release, '2026.11.9.8')
  assert.equal(imageTagOf((await ecs.taskDefinition('idp-test-cli')).definition, 'cli'), '2026.11.9.8')
})

test('a failed first release returns to the revisions of Terraform and leaves the bucket and the one-off tasks alone', async () => {
  const ecs = applied()
  const s3 = fakeS3(release('2026.11.9.8', pages('2026.11.9.8')))
  const state = await register(ecs, config, planOf('2026.11.9.8'))
  await rollout(ecs, config, 'app', state.next.app, quick)
  ecs.log.length = 0

  const { release: target } = await rollback(ecs, s3, config, state.previous, { now, log: () => {}, wait: quick })

  assert.equal(target, undefined)
  assert.deepEqual(ecs.log, [['update', 'app', state.previous.app]])
  assert.equal(s3.body('login/index.html'), undefined)
})

test('a rollback to pages that are gone stops before any service moves', async () => {
  const { ecs, s3 } = await twoReleases()
  s3.store.delete('releases/2026.11.1.1/login/index.html')

  await assert.rejects(rollback(ecs, s3, config, await previousTargets(ecs, config), { now, log: () => {}, wait: quick }), /misses/)

  assert.equal(ecs.log.filter(([kind]) => kind === 'update').length, 0)
})

test('options need the settings, the state is optional', () => {
  assert.deepEqual({ ...parseOptions(['--config', 'c']) }, { config: 'c' })
  assert.equal(parseOptions(['--config', 'c', '--state', 's']).state, 's')
  assert.throws(() => parseOptions(['--state', 's']), /Usage/)
})
