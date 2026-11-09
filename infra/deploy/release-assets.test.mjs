import assert from 'node:assert/strict'
import { mkdir, mkdtemp, rm, symlink, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'
import { after, test } from 'node:test'
import { DAY, fakeS3, release } from './fake-s3.mjs'
import { prune } from './prune-assets.mjs'
import { activate, filesOf, parseOptions, stage } from './release-assets.mjs'

const now = new Date('2026-11-09T12:00:00Z')
const roots = []

after(() => Promise.all(roots.map((it) => rm(it, { recursive: true, force: true }))))

async function build(files) {
  const root = await mkdtemp(join(tmpdir(), 'idp-build-'))
  roots.push(root)
  for (const [path, body] of Object.entries(files)) {
    await mkdir(dirname(join(root, path)), { recursive: true })
    await writeFile(join(root, path), body)
  }
  return root
}

const buildOf = (version) => ({
  'login/index.html': `<script src="/login/assets/index-${version}.js"></script>`,
  [`login/assets/index-${version}.js`]: `login ${version}`,
  [`login/assets/index-${version}.css`]: `body { --v: '${version}' }`,
  'console/index.html': `console page ${version}`,
  'console/assets/vendor-DxxIysAt.js': 'vue',
  [`console/assets/index-${version}.js`]: `console ${version}`,
  'console/assets/logo-AbCd1234.svg': '<svg/>',
  'console/assets/font-AbCd1234.woff2': 'font',
})

test('stage uploads every file with its Content-Type and leaves the live pages alone', async () => {
  const s3 = fakeS3({ 'login/index.html': 'live login', 'console/index.html': 'live console' })

  const files = await stage(s3, { dir: await build(buildOf('1.1')), version: '1.1' })

  assert.deepEqual(files, Object.keys(buildOf('1.1')).sort())
  assert.equal(s3.body('login/index.html'), 'live login')
  assert.equal(s3.body('console/index.html'), 'live console')
  assert.equal(s3.body('releases/1.1/login/index.html'), buildOf('1.1')['login/index.html'])
  assert.equal(s3.body('console/assets/vendor-DxxIysAt.js'), 'vue')
  const types = Object.fromEntries([...s3.store].map(([key, { contentType }]) => [key, contentType]))
  assert.deepEqual(types, {
    'console/index.html': 'test/seeded',
    'login/index.html': 'test/seeded',
    'releases/1.1/console/index.html': 'text/html; charset=utf-8',
    'releases/1.1/login/index.html': 'text/html; charset=utf-8',
    'releases/1.1/manifest.json': 'application/json; charset=utf-8',
    'login/assets/index-1.1.js': 'application/javascript; charset=utf-8',
    'login/assets/index-1.1.css': 'text/css; charset=utf-8',
    'console/assets/vendor-DxxIysAt.js': 'application/javascript; charset=utf-8',
    'console/assets/index-1.1.js': 'application/javascript; charset=utf-8',
    'console/assets/logo-AbCd1234.svg': 'image/svg+xml; charset=utf-8',
    'console/assets/font-AbCd1234.woff2': 'font/woff2',
  })
  assert.deepEqual(JSON.parse(s3.body('releases/1.1/manifest.json')), { version: '1.1', files })
  assert.equal([...s3.store.keys()].at(-1), 'releases/1.1/manifest.json')
})

test('activate marks the release live and then puts its pages in place', async () => {
  const s3 = fakeS3({ 'login/index.html': 'live login' })
  await stage(s3, { dir: await build(buildOf('1.1')), version: '1.1' })
  const order = []
  const put = s3.put
  s3.put = async (key, body, type) => {
    order.push(key)
    await put(key, body, type)
  }

  const pages = await activate(s3, { version: '1.1', now })

  assert.deepEqual(pages, ['console/index.html', 'login/index.html'])
  assert.deepEqual(order, ['releases/1.1/manifest.json', 'console/index.html', 'login/index.html'])
  assert.equal(s3.body('login/index.html'), buildOf('1.1')['login/index.html'])
  assert.equal(s3.store.get('login/index.html').contentType, 'text/html; charset=utf-8')
  const manifest = JSON.parse(s3.body('releases/1.1/manifest.json'))
  assert.equal(manifest.releasedAt, now.toISOString())
  assert.equal(manifest.files.length, Object.keys(buildOf('1.1')).length)
})

test('a rollback activates the previous release again, and pruning keeps both', async () => {
  const s3 = fakeS3()
  await stage(s3, { dir: await build(buildOf('1.0')), version: '1.0' })
  await activate(s3, { version: '1.0', now: new Date(now.getTime() - 50 * DAY) })
  await stage(s3, { dir: await build(buildOf('1.1')), version: '1.1' })
  await activate(s3, { version: '1.1', now: new Date(now.getTime() - DAY) })

  await activate(s3, { version: '1.0', now })
  const { kept, deleted } = await prune(s3, { now })

  assert.equal(s3.body('login/index.html'), buildOf('1.0')['login/index.html'])
  assert.equal(s3.body('console/index.html'), 'console page 1.0')
  assert.deepEqual(kept, ['1.0', '1.1'])
  assert.deepEqual(deleted, [])
})

test('a release that has been live is not staged again, one that was only staged is', async () => {
  const s3 = fakeS3(release('1.0', ['login/index.html', 'login/assets/index-1.0.js'], new Date(now.getTime() - DAY)))
  const before = new Map(s3.store)

  await assert.rejects(stage(s3, { dir: await build(buildOf('1.0')), version: '1.0' }), /has already been live/)
  assert.deepEqual(s3.store, before)

  await stage(s3, { dir: await build(buildOf('1.1')), version: '1.1' })
  await stage(s3, { dir: await build({ ...buildOf('1.1'), 'login/assets/index-1.1.js': 'rebuilt' }), version: '1.1' })
  assert.equal(s3.body('login/assets/index-1.1.js'), 'rebuilt')
  assert.equal(JSON.parse(s3.body('releases/1.1/manifest.json')).releasedAt, undefined)
})

test('a build that S3 would serve differently from nginx is refused before anything is uploaded', async () => {
  const builds = {
    'a file without a known type': [{ ...buildOf('1.1'), 'login/assets/index-1.1.js.map': '{}' }, /No Content-Type for login\/assets\/index-1.1.js.map/],
    'a file outside an app': [{ ...buildOf('1.1'), 'robots.txt': 'x' }, /robots.txt lies outside the directory of an app/],
    'a directory without its page': [{ ...buildOf('1.1'), 'admin/assets/index.js': 'x' }, /admin\/assets\/index.js lies outside/],
    'no page at all': [{ 'login/assets/index.js': 'x' }, /holds no app with an index.html/],
    'an app named releases': [{ ...buildOf('1.1'), 'releases/index.html': 'x' }, /may not be named releases/],
  }
  for (const [name, [files, error]] of Object.entries(builds)) {
    const s3 = fakeS3()
    await assert.rejects(stage(s3, { dir: await build(files), version: '1.1' }), error, name)
    assert.equal(s3.store.size, 0, name)
  }
})

test('a link in the build is refused', async () => {
  const root = await build(buildOf('1.1'))
  await symlink('/etc/passwd', join(root, 'login/assets/passwd.txt'))

  await assert.rejects(filesOf(root), /passwd.txt is neither a file nor a directory/)
})

test('a version that is no plain name is refused', async () => {
  const dir = await build(buildOf('1.1'))
  for (const version of ['', '../1.0', '1.0/x', '-1', '.1', '1 0', 'é', 'a'.repeat(129)]) {
    await assert.rejects(stage(fakeS3(), { dir, version }), /Unusable version/, version)
    await assert.rejects(activate(fakeS3(), { version, now }), /Unusable version/, version)
  }
})

test('activate refuses a release without a manifest or with files gone', async () => {
  const s3 = fakeS3()
  await assert.rejects(activate(s3, { version: '1.0', now }), /Release 1.0 has no manifest/)

  await stage(s3, { dir: await build(buildOf('1.0')), version: '1.0' })
  s3.store.delete('console/assets/vendor-DxxIysAt.js')
  s3.store.delete('releases/1.0/login/index.html')
  const before = new Map(s3.store)

  await assert.rejects(activate(s3, { version: '1.0', now }), /Release 1.0 misses console\/assets\/vendor-DxxIysAt.js, releases\/1.0\/login\/index.html/)
  assert.deepEqual(s3.store, before)
})

test('the options name a command, a bucket and a version, and a directory only to stage', () => {
  assert.deepEqual(parseOptions(['stage', '--bucket', 'b', '--version', '1.2.0', '--dir', '/srv/web']), {
    command: 'stage',
    bucket: 'b',
    version: '1.2.0',
    dir: '/srv/web',
  })
  assert.deepEqual(parseOptions(['activate', '--bucket', 'b', '--version', '1.2.0']), { command: 'activate', bucket: 'b', version: '1.2.0', dir: undefined })
  const usages = [
    [],
    ['prune', '--bucket', 'b', '--version', '1'],
    ['stage', '--bucket', 'b', '--version', '1'],
    ['activate', '--bucket', 'b', '--version', '1', '--dir', 'd'],
    ['activate', '--version', '1'],
    ['activate', '--bucket', 'b'],
    ['activate', 'stage', '--bucket', 'b', '--version', '1'],
  ]
  for (const argv of usages) assert.throws(() => parseOptions(argv), /Usage/, argv.join(' '))
  assert.throws(() => parseOptions(['activate', '--bucket', 'b', '--version', '../1']), /Unusable version/)
})
