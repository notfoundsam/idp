import assert from 'node:assert/strict'
import { existsSync } from 'node:fs'
import { readFile, writeFile } from 'node:fs/promises'
import { test } from 'node:test'
import { cliS3 } from './s3.mjs'

function recorder(answer = () => '') {
  const calls = []
  const run = async (args) => {
    const call = { args, files: {} }
    for (const arg of args) {
      const path = arg.startsWith('file://') ? arg.slice('file://'.length) : arg
      if (path.startsWith('/') && existsSync(path)) call.files[arg] = await readFile(path, 'utf8')
    }
    calls.push(call)
    return answer(args, call)
  }
  return { calls, run }
}

test('list names every key the CLI lists, and none of an empty bucket', async () => {
  const listed = { Contents: [{ Key: 'login/index.html', Size: 1 }, { Key: 'releases/1.0/manifest.json', Size: 2 }] }
  const { calls, run } = recorder(() => JSON.stringify(listed))

  assert.deepEqual(await cliS3('idp-static', run).list(), ['login/index.html', 'releases/1.0/manifest.json'])
  assert.deepEqual(calls[0].args, ['s3api', 'list-objects-v2', '--bucket', 'idp-static', '--output', 'json'])
  assert.deepEqual(await cliS3('idp-static', async () => '').list(), [])
  assert.deepEqual(await cliS3('idp-static', async () => '{"RequestCharged": null}').list(), [])
})

test('put hands the CLI the body and the Content-Type', async () => {
  const { calls, run } = recorder()

  await cliS3('idp-static', run).put('login/index.html', Buffer.from('<p>é</p>'), 'text/html; charset=utf-8')

  const [{ args, files }] = calls
  assert.deepEqual(args.slice(0, 8), ['s3api', 'put-object', '--bucket', 'idp-static', '--key', 'login/index.html', '--content-type', 'text/html; charset=utf-8'])
  assert.equal(args[8], '--body')
  assert.equal(files[args[9]], '<p>é</p>')
  assert.ok(!existsSync(args[9]), 'the body is left behind')
})

test('get reads the file the CLI writes', async () => {
  const { calls, run } = recorder(async (args) => {
    await writeFile(args.at(-1), '{"version":"1.0"}')
    return '{"ContentType": "application/json"}'
  })

  const body = await cliS3('idp-static', run).get('releases/1.0/manifest.json')

  assert.equal(body.toString('utf8'), '{"version":"1.0"}')
  assert.deepEqual(calls[0].args.slice(0, 6), ['s3api', 'get-object', '--bucket', 'idp-static', '--key', 'releases/1.0/manifest.json'])
})

test('delete sends at most a thousand keys a call and nothing for no keys', async () => {
  const { calls, run } = recorder()
  const keys = Array.from({ length: 2001 }, (_, index) => `login/assets/chunk-${index}.js`)

  await cliS3('idp-static', run).delete([])
  assert.equal(calls.length, 0)

  await cliS3('idp-static', run).delete(keys)
  const batches = calls.map(({ args, files }) => {
    assert.deepEqual(args.slice(0, 4), ['s3api', 'delete-objects', '--bucket', 'idp-static'])
    return JSON.parse(files[args[5]])
  })
  assert.deepEqual(batches.map((it) => it.Objects.length), [1000, 1000, 1])
  assert.deepEqual(batches.flatMap((it) => it.Objects.map((object) => object.Key)), keys)
  assert.ok(batches.every((it) => it.Quiet === true))
})

test('a key that S3 refused to delete fails the call', async () => {
  const refused = { Errors: [{ Key: 'login/assets/a.js', Code: 'AccessDenied', Message: 'Access Denied' }] }
  const s3 = cliS3('idp-static', async () => JSON.stringify(refused))

  await assert.rejects(s3.delete(['login/assets/a.js']), /S3 did not delete login\/assets\/a.js \(AccessDenied\)/)
})

test('a failing CLI fails the call', async () => {
  const s3 = cliS3('idp-static', async () => {
    throw new Error('aws exited with 254')
  })

  await assert.rejects(s3.list(), /254/)
  await assert.rejects(s3.put('login/index.html', 'x', 'text/html; charset=utf-8'), /254/)
  await assert.rejects(s3.get('login/index.html'), /254/)
  await assert.rejects(s3.delete(['x']), /254/)
})
