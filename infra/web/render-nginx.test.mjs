import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { test } from 'node:test'
import { render, renderFiles } from './render-nginx.mjs'

const headers = JSON.parse(await readFile(new URL('security-headers.json', import.meta.url), 'utf8'))

function blocks(config) {
  const found = []
  const open = []
  for (const raw of config.split('\n')) {
    const line = raw.replace(/#.*/, '').trim()
    if (line.endsWith('{')) {
      open.push({ name: line.slice(0, -1).trim(), headers: [] })
    } else if (line === '}') {
      found.push(open.pop())
    } else if (line.startsWith('add_header ') && open.length > 0) {
      open.at(-1).headers.push(line.split(/\s+/)[1])
    }
  }
  return found
}

function directives(policy) {
  return new Map(
    policy
      .split(';')
      .map((part) => part.trim().split(/\s+/))
      .filter((part) => part[0] !== '')
      .map(([name, ...sources]) => [name, sources]),
  )
}

test('every block that sets its own headers repeats the security headers', async () => {
  const found = blocks(await renderFiles())
  const own = found.filter((block) => block.headers.length > 0)
  assert.ok(own.some((block) => block.name === 'server'))
  assert.ok(own.length >= 3)
  for (const block of own) {
    for (const name of Object.keys(headers)) assert.ok(block.headers.includes(name), `${block.name} lacks ${name}`)
  }
})

test('the static files are served without listings and with their charset', async () => {
  const config = await renderFiles()
  assert.match(config, /^\s*autoindex off;$/m)
  assert.match(config, /^\s*charset utf-8;$/m)
  assert.match(config, /^\s*server_tokens off;$/m)
})

test('only found files are cached, so a missing chunk is asked for again', async () => {
  const caching = (await renderFiles()).split('\n').filter((line) => line.includes('add_header Cache-Control'))
  assert.ok(caching.some((line) => line.includes('immutable')))
  for (const line of caching) assert.doesNotMatch(line, /\balways;/)
})

test('the policy runs scripts of the own origin only and closes everything it does not need', () => {
  const policy = directives(headers['Content-Security-Policy'])
  assert.deepEqual(policy.get('default-src'), ["'none'"])
  assert.deepEqual(policy.get('script-src'), ["'self'"])
  for (const name of ['object-src', 'base-uri', 'frame-ancestors', 'form-action']) assert.deepEqual(policy.get(name), ["'none'"], name)
  for (const [name, sources] of policy) {
    for (const source of sources) assert.ok(["'self'", "'none'"].includes(source), `${name} allows ${source}`)
  }
})

test('the transport, sniffing and referrer headers match those of the backend', () => {
  assert.equal(headers['Strict-Transport-Security'], 'max-age=31536000; includeSubDomains')
  assert.equal(headers['X-Content-Type-Options'], 'nosniff')
  assert.equal(headers['Referrer-Policy'], 'no-referrer')
})

test('each header line keeps the indentation of its placeholder', () => {
  const rendered = render('a {\n    {{security_headers}}\n}', { 'X-One': '1', 'X-Two': "'self'" })
  assert.equal(rendered, `a {\n    add_header X-One "1" always;\n    add_header X-Two "'self'" always;\n}`)
})

test('a value nginx would read differently is refused', () => {
  for (const value of ['a"b', 'a\\b', '$host', 'a\nb', '', 'é']) {
    assert.throws(() => render('{{security_headers}}', { 'X-Test': value }), /Unusable value of X-Test/, JSON.stringify(value))
  }
  assert.throws(() => render('{{security_headers}}', { 'X-Test': 1 }), /Unusable value/)
})

test('a header name that is no token is refused', () => {
  for (const name of ['X Test', 'X:Test', '-X', 'X"']) {
    assert.throws(() => render('{{security_headers}}', { [name]: 'value' }), /Unusable header name/, name)
  }
})

test('a template without its own placeholder line or without headers is refused', () => {
  assert.throws(() => render('server {}', { 'X-Test': 'value' }), /has no/)
  assert.throws(() => render('x {{security_headers}}', { 'X-Test': 'value' }), /line of its own/)
  assert.throws(() => render('{{security_headers}}', {}), /No security headers/)
})
