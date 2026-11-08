import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { test } from 'node:test'
import vm from 'node:vm'
import { renderFiles } from './render-nginx.mjs'

const code = await readFile(new URL('spa-rewrite.js', import.meta.url), 'utf8')
const { cacheControl, cases } = JSON.parse(await readFile(new URL('spa-routes.json', import.meta.url), 'utf8'))

// The runtime of CloudFront Functions has no modules, timers or Node globals, so the script runs in an empty context.
function load() {
  const context = vm.createContext({})
  new vm.Script(code, { filename: 'spa-rewrite.js' }).runInContext(context)
  return context.handler
}

const handler = load()

// Objects made inside the context have its prototypes, which strict deep equality would compare.
const plain = (value) => JSON.parse(JSON.stringify(value))

function viewerRequest(uri, querystring = {}) {
  const request = { method: 'GET', uri, querystring, headers: { host: { value: 'acme.idp.example.com' } }, cookies: {} }
  return plain(handler({ version: '1.0', context: { eventType: 'viewer-request' }, viewer: { ip: '192.0.2.1' }, request }))
}

function viewerResponse(uri, statusCode, headers = {}) {
  const event = {
    version: '1.0',
    context: { eventType: 'viewer-response' },
    viewer: { ip: '192.0.2.1' },
    request: { method: 'GET', uri, querystring: {}, headers: {}, cookies: {} },
    response: { statusCode, statusDescription: '', headers, cookies: {} },
  }
  return plain(handler(event))
}

test('the cases cover every kind of answer', () => {
  assert.ok(cases.some((route) => route.status === 302))
  assert.ok(cases.some((route) => route.status === 301))
  assert.ok(cases.some((route) => route.status === 404))
  assert.ok(cases.some((route) => route.file && route.cache === 'pages'))
  assert.ok(cases.some((route) => route.missing))
  for (const route of cases) assert.ok(route.uri.startsWith('/'), route.uri)
})

for (const route of cases) {
  test(`viewer request ${route.uri}`, () => {
    const result = viewerRequest(route.uri)
    if (route.status !== undefined) {
      assert.equal(result.statusCode, route.status)
      assert.equal(result.headers.location?.value, route.location)
    } else {
      assert.equal(result.statusCode, undefined, 'answered instead of reading the bucket')
      assert.equal(result.uri, route.file)
    }
  })

  if (route.file !== undefined) {
    test(`viewer response ${route.uri}`, () => {
      for (const uri of new Set([route.uri, route.file])) {
        if (route.missing) {
          for (const status of [403, 404]) {
            const headers = { 'cache-control': { value: cacheControl.assets } }
            assert.equal(viewerResponse(uri, status, headers).headers['cache-control'], undefined, `${uri} ${status}`)
          }
        } else {
          for (const status of [200, 304]) {
            assert.deepEqual(viewerResponse(uri, status).headers['cache-control'], { value: cacheControl[route.cache] }, `${uri} ${status}`)
          }
        }
      }
    })
  }
}

test('a found chunk is immutable, a found page is revalidated', () => {
  for (const uri of ['/login/assets/index-B7CUXFO7.js', '/console/assets/nested/style-0a1b2c3d.css']) {
    assert.equal(viewerRequest(uri).uri, uri)
    assert.equal(viewerResponse(uri, 200).headers['cache-control'].value, cacheControl.assets)
  }
  const stale = { 'cache-control': { value: cacheControl.assets } }
  assert.equal(viewerResponse('/login/index.html', 200, stale).headers['cache-control'].value, cacheControl.pages)
})

test('an error keeps no lifetime, so it is asked for again', () => {
  for (const status of [400, 403, 404, 500, 503]) {
    const headers = { 'cache-control': { value: cacheControl.pages } }
    assert.equal(viewerResponse('/login/index.html', status, headers).headers['cache-control'], undefined, String(status))
  }
})

test('a rewrite keeps the query string and the headers', () => {
  const result = viewerRequest('/login/', { flow: { value: 'abc' } })
  assert.deepEqual(result.querystring, { flow: { value: 'abc' } })
  assert.equal(result.headers.host.value, 'acme.idp.example.com')
  assert.equal(viewerRequest('/invite', { lang: { value: 'en' } }).querystring.lang.value, 'en')
})

test('the bucket is asked only for a page or a file under assets, never for its root or another prefix', () => {
  const uris = [
    '',
    '/',
    '//',
    '//login/',
    '/login//',
    '/login/..',
    '/login/../console',
    '/login/assets/../index.html',
    '/%6Cogin/',
    '/login%2F',
    '/console/index.htm',
    '/constructor',
    '/__proto__',
    '/hasOwnProperty',
    '/toString',
    '/invite.html',
    '/console/assets',
    '/consoles/',
    '/api/',
    '/saml/sso',
  ]
  for (const uri of uris) {
    const result = viewerRequest(uri)
    if (result.statusCode !== undefined) {
      assert.ok([301, 302, 404].includes(result.statusCode), `${uri} ${result.statusCode}`)
    } else {
      assert.match(result.uri, /^\/(login|console)\/(index\.html$|assets\/)/, uri)
    }
  }
})

test('a redirect points only to an app of the same origin', () => {
  for (const uri of ['/', '/login', '/console']) {
    assert.match(viewerRequest(uri).headers.location.value, /^\/(login|console)\/$/, uri)
  }
})

test('nginx gives the same lifetimes as the function', async () => {
  const config = await renderFiles()
  for (const value of Object.values(cacheControl)) assert.ok(config.includes(`add_header Cache-Control "${value}";`), value)
})

test('the script fits the runtime of CloudFront Functions', () => {
  assert.ok(Buffer.byteLength(code) <= 10240, 'CloudFront takes at most 10 KB of code')
  assert.equal(typeof handler, 'function')
  assert.doesNotMatch(code, /^\s*(import|export)\b/m)
  assert.doesNotMatch(code, /\b(require|process|setTimeout|globalThis)\b/)
})
