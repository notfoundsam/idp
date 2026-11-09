import assert from 'node:assert/strict'
import { test } from 'node:test'
import { DAY, fakeS3, manifest, release } from './fake-s3.mjs'
import { parseOptions, prune } from './prune-assets.mjs'

const now = new Date('2026-11-09T12:00:00Z')
const ago = (days) => new Date(now.getTime() - days * DAY)
const files = (version) => ['login/index.html', `login/assets/index-${version}.js`, `login/assets/index-${version}.css`]
const live = { 'login/index.html': 'live page' }

test('a file in the manifest of any release live in the last 30 days stays, the rest is deleted', async () => {
  const s3 = fakeS3({
    ...release('0.8', files('0.8'), ago(120)),
    ...release('0.9', files('0.9'), ago(90)),
    ...release('1.0', files('1.0'), ago(45)),
    ...release('1.1', files('1.1'), ago(29)),
    ...release('1.2', files('1.2'), ago(10)),
    ...release('1.3', files('1.3'), ago(1)),
    ...live,
    'login/assets/stray-00000000.js': 'left by hand',
    'console/assets/old-00000000.js': 'of no release',
  })

  const { kept, deleted } = await prune(s3, { now })

  assert.deepEqual(kept, ['1.3', '1.2', '1.1', '1.0'])
  for (const version of kept) {
    for (const file of files(version).filter((it) => !it.endsWith('index.html'))) assert.ok(s3.store.has(file), file)
    assert.ok(s3.store.has(`releases/${version}/manifest.json`))
    assert.ok(s3.store.has(`releases/${version}/login/index.html`))
  }
  assert.equal(s3.body('login/index.html'), 'live page')
  assert.deepEqual(deleted.sort(), [
    'console/assets/old-00000000.js',
    'login/assets/index-0.8.css',
    'login/assets/index-0.8.js',
    'login/assets/index-0.9.css',
    'login/assets/index-0.9.js',
    'login/assets/stray-00000000.js',
    'releases/0.8/login/index.html',
    'releases/0.8/manifest.json',
    'releases/0.9/login/index.html',
    'releases/0.9/manifest.json',
  ])
  for (const key of deleted) assert.ok(!s3.store.has(key), key)
})

test('a release live until a day inside the period stays, though it went live long before', async () => {
  const s3 = fakeS3({ ...release('1.0', files('1.0'), ago(200)), ...release('1.1', files('1.1'), ago(30)), ...release('1.2', files('1.2'), ago(2)), ...live })

  const { kept } = await prune(s3, { now })

  assert.deepEqual(kept, ['1.2', '1.1', '1.0'])
  assert.ok(s3.store.has('login/assets/index-1.0.js'))
})

test('vendor with an unchanged hash stays, even though the release that uploaded it is long gone', async () => {
  const vendor = 'console/assets/vendor-DxxIysAt.js'
  const s3 = fakeS3({
    ...release('1.0', ['console/index.html', vendor, 'console/assets/index-1.0.js'], ago(400)),
    ...release('1.1', ['console/index.html', 'console/assets/index-1.1.js'], ago(300)),
    ...release('1.2', ['console/index.html', 'console/assets/index-1.2.js'], ago(200)),
    ...release('1.3', ['console/index.html', vendor, 'console/assets/index-1.3.js'], ago(5)),
    'console/index.html': 'live page',
  })
  s3.store.set(vendor, { body: Buffer.from('uploaded by 1.0'), contentType: 'application/javascript; charset=utf-8' })

  const { kept, deleted } = await prune(s3, { now })

  assert.deepEqual(kept, ['1.3', '1.2'])
  assert.equal(s3.body(vendor), 'uploaded by 1.0')
  assert.ok(deleted.includes('console/assets/index-1.0.js'))
  assert.ok(!deleted.includes(vendor))
})

test('the live and the previous release stay however old, so a rollback finds its files', async () => {
  const s3 = fakeS3({ ...release('1.0', files('1.0'), ago(500)), ...release('1.1', files('1.1'), ago(300)), ...release('1.2', files('1.2'), ago(100)), ...live })

  const { kept, deleted } = await prune(s3, { now })

  assert.deepEqual(kept, ['1.2', '1.1'])
  assert.ok(s3.store.has('login/assets/index-1.1.js'))
  assert.ok(s3.store.has('releases/1.1/login/index.html'))
  assert.ok(deleted.includes('login/assets/index-1.0.js'))
})

test('a release that was staged and never went live is deleted with its pages', async () => {
  const s3 = fakeS3({ ...release('1.0', files('1.0'), ago(3)), ...release('1.1', files('1.1')), ...live })

  const { kept, deleted } = await prune(s3, { now })

  assert.deepEqual(kept, ['1.0'])
  assert.deepEqual(deleted.sort(), ['login/assets/index-1.1.css', 'login/assets/index-1.1.js', 'releases/1.1/login/index.html', 'releases/1.1/manifest.json'])
})

test('the number of days is the period', async () => {
  const s3 = fakeS3({ ...release('1.0', files('1.0'), ago(9)), ...release('1.1', files('1.1'), ago(8)), ...release('1.2', files('1.2'), ago(1)), ...live })

  assert.deepEqual((await prune(s3, { now, days: 7 })).kept, ['1.2', '1.1'])
})

test('a dry run deletes nothing and names what it would delete', async () => {
  const s3 = fakeS3({ ...release('1.0', files('1.0'), ago(3)), 'login/assets/stray-00000000.js': 'x', ...live })

  const { deleted } = await prune(s3, { now, dryRun: true })

  assert.deepEqual(deleted, ['login/assets/stray-00000000.js'])
  assert.ok(s3.store.has('login/assets/stray-00000000.js'))
  assert.deepEqual(s3.deletes, [])
})

test('nothing is deleted without a release that has been live', async () => {
  for (const objects of [{ ...live }, { ...release('1.0', files('1.0')), ...live }]) {
    const s3 = fakeS3(objects)
    await assert.rejects(prune(s3, { now }), /No release has been live/)
    assert.deepEqual(s3.deletes, [])
  }
})

test('nothing is deleted when a manifest cannot be read', async () => {
  const broken = {
    'not JSON': ['1.1', '{', /is no JSON/],
    'another version': ['1.1', manifest('1.2', files('1.2'), ago(1)), /names another version/],
    'no files': ['1.1', manifest('1.1', [], ago(1)), /lists no usable files/],
    'a file of releases/': ['1.1', manifest('1.1', ['releases/1.0/manifest.json'], ago(1)), /lists no usable files/],
    'a date it cannot read': ['1.1', JSON.stringify({ version: '1.1', files: files('1.1'), releasedAt: 'yesterday' }), /unusable releasedAt/],
    'an unusable version': ['1 1', manifest('1 1', files('1.1'), ago(1)), /Unusable version/],
  }
  for (const [name, [version, body, error]] of Object.entries(broken)) {
    const s3 = fakeS3({ ...release('1.0', files('1.0'), ago(3)), [`releases/${version}/manifest.json`]: body, 'login/assets/stray.js': 'x', ...live })
    await assert.rejects(prune(s3, { now }), error, name)
    assert.deepEqual(s3.deletes, [], name)
  }
})

test('the options name a bucket and whole days', () => {
  assert.deepEqual(parseOptions(['--bucket', 'b']), { bucket: 'b', days: 30, dryRun: false })
  assert.deepEqual(parseOptions(['--bucket', 'b', '--days', '7', '--dry-run']), { bucket: 'b', days: 7, dryRun: true })
  for (const argv of [[], ['--bucket', ''], ['--bucket', 'b', '--days', '0'], ['--bucket', 'b', '--days', '1.5'], ['--bucket', 'b', '--days=-3']]) {
    assert.throws(() => parseOptions(argv), /Usage/, argv.join(' '))
  }
  assert.throws(() => parseOptions(['--bucket', 'b', '--keep', '1']), /Unknown option/)
})
