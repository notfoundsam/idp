import assert from 'node:assert/strict'
import { test } from 'node:test'
import { isOlder, outputsOf, parseOptions, plan, versionOf } from './plan-release.mjs'

const HEAD = 'a'.repeat(40)
const released = {
  release: '2026.11.1.7',
  backendVersion: '2026.11.1.7',
  frontendVersion: '2026.11.1.7',
  minClientVersion: '',
  backendCommit: 'b'.repeat(40),
  frontendCommit: 'b'.repeat(40),
}
const terraform = { release: undefined, backendVersion: '1.0', frontendVersion: '1.0', minClientVersion: '' }
const none = { frontend: false, backend: false }
const all = { frontend: true, backend: true }
const base = { live: released, liveFrontend: { version: '2026.11.1.7' }, frontendChanges: all, backendChanges: all, version: '2026.11.9.8', commit: HEAD }

test('a version is the date and the run, so ClientVersionFilter orders releases', () => {
  assert.equal(versionOf(new Date('2026-11-09T23:30:00Z'), 8), '2026.11.9.8')
  assert.ok(isOlder('2026.11.9.8', '2026.11.10.1'))
  assert.ok(isOlder('2026.11', '2026.11.1'))
  assert.ok(!isOlder('2026.11.9.8', '2026.11.9.8'))
  assert.throws(() => versionOf(new Date(), '0'), /run number/)
})

test('a release of both sides rolls out the new images and the new pages', () => {
  assert.deepEqual(plan(base), {
    version: '2026.11.9.8',
    first: false,
    frontend: true,
    backend: true,
    rollout: true,
    smoke: true,
    frontendVersion: '2026.11.9.8',
    backendVersion: '2026.11.9.8',
    minClientVersion: '',
    frontendCommit: HEAD,
    backendCommit: HEAD,
  })
})

test('the frontend alone keeps the images that run and still rolls out, for APP_VERSION', () => {
  const planned = plan({ ...base, frontendChanges: { frontend: true, backend: false }, backendChanges: none })

  assert.equal(planned.frontend, true)
  assert.equal(planned.backend, false)
  assert.equal(planned.rollout, true)
  assert.equal(planned.backendVersion, '2026.11.1.7')
  assert.equal(planned.frontendVersion, '2026.11.9.8')
  assert.equal(planned.backendCommit, released.backendCommit)
  assert.equal(planned.frontendCommit, HEAD)
})

test('the backend alone keeps the live pages, whose version app goes on naming', () => {
  const planned = plan({ ...base, frontendChanges: none, backendChanges: { frontend: false, backend: true } })

  assert.equal(planned.frontend, false)
  assert.equal(planned.backend, true)
  assert.equal(planned.frontendVersion, '2026.11.1.7')
  assert.equal(planned.backendVersion, '2026.11.9.8')
  assert.equal(planned.frontendCommit, released.frontendCommit)
})

test('each side compares with its own live commit', () => {
  const planned = plan({ ...base, frontendChanges: { frontend: false, backend: true }, backendChanges: { frontend: true, backend: false } })

  assert.equal(planned.frontend, false)
  assert.equal(planned.backend, false)
  assert.equal(planned.rollout, false)
})

test('nothing changed releases nothing, unless full or a new minimum asks for it', () => {
  assert.equal(plan({ ...base, frontendChanges: none, backendChanges: none }).rollout, false)
  const full = plan({ ...base, frontendChanges: none, backendChanges: none, full: true })
  assert.equal(full.frontend && full.backend && full.rollout, true)
  const raised = plan({ ...base, frontendChanges: none, backendChanges: none, minClientVersion: '2026.11.1' })
  assert.equal(raised.rollout, true)
  assert.equal(raised.frontend || raised.backend, false)
  assert.equal(raised.minClientVersion, '2026.11.1')
})

test('an empty min-client-version keeps the minimum that runs', () => {
  const live = { ...released, minClientVersion: '2026.10.1' }

  assert.equal(plan({ ...base, live }).minClientVersion, '2026.10.1')
})

test('a minimum above the frontend that will be live is refused, as is an unreadable one', () => {
  assert.throws(() => plan({ ...base, frontendChanges: none, minClientVersion: '2026.11.9' }), /every page would get 426/)
  assert.throws(() => plan({ ...base, minClientVersion: 'v2' }), /Unreadable min-client-version/)
  assert.equal(plan({ ...base, minClientVersion: '2026.11.9.8' }).minClientVersion, '2026.11.9.8')
})

test('the first release goes out whole and may skip the smoke test', () => {
  const planned = plan({ ...base, live: terraform, liveFrontend: undefined, frontendChanges: none, backendChanges: none, skipSmoke: true })

  assert.equal(planned.first, true)
  assert.equal(planned.frontend && planned.backend && planned.rollout, true)
  assert.equal(planned.smoke, false)
})

test('the smoke test is skipped only while the environment has had no release', () => {
  assert.throws(() => plan({ ...base, skipSmoke: true }), /Only the first release/)
  assert.throws(() => plan({ ...base, live: terraform, skipSmoke: true }), /Only the first release/)
  assert.throws(() => plan({ ...base, liveFrontend: undefined, skipSmoke: true }), /Only the first release/)
})

test('the plan reaches the workflow as outputs', () => {
  assert.equal(outputsOf({ version: '1.2', first: false, frontendVersion: '1.1' }), 'version=1.2\nfirst=false\nfrontend-version=1.1')
})

test('options need the settings, the full commit, the run and the plan file', () => {
  const options = parseOptions(['--config', 'c.json', '--commit', HEAD, '--run-number', '8', '--out', 'p.json', '--skip-smoke'])

  assert.equal(options['skip-smoke'], true)
  assert.equal(options['min-client-version'], '')
  assert.throws(() => parseOptions(['--config', 'c.json', '--commit', 'HEAD', '--run-number', '8', '--out', 'p.json']), /Usage/)
  assert.throws(() => parseOptions(['--config', 'c.json', '--commit', HEAD, '--out', 'p.json']), /Usage/)
})
