import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { test } from 'node:test'
import { CONTENT_TYPES } from './content-types.mjs'
import { render, renderFiles, renderTypes } from './render-nginx.mjs'

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

function servedTypes(config) {
  const block = config.slice(config.indexOf('types {') + 'types {'.length)
  const body = block.slice(0, block.indexOf('}'))
  const served = {}
  for (const line of body.split('\n').map((it) => it.trim()).filter((it) => it !== '')) {
    const [type, ...extensions] = line.replace(/;$/, '').split(/\s+/)
    for (const extension of extensions) served[extension] = type
  }
  const charsetTypes = /^\s*charset_types ([^;]+);$/m.exec(config)[1].split(' ')
  return Object.fromEntries(
    Object.entries(served).map(([extension, type]) => [extension, charsetTypes.includes(type) || type === 'text/html' ? `${type}; charset=utf-8` : type]),
  )
}

test('nginx serves each file with the Content-Type that the upload to S3 sets', async () => {
  const config = await renderFiles()
  assert.equal(config.match(/^\s*types \{$/gm).length, 1)
  assert.deepEqual(servedTypes(config), CONTENT_TYPES)
})

test('HTML, scripts and styles name their charset', () => {
  for (const extension of ['html', 'js', 'css', 'json', 'svg']) assert.match(CONTENT_TYPES[extension], /; charset=utf-8$/, extension)
  for (const extension of ['png', 'ico', 'woff2']) assert.doesNotMatch(CONTENT_TYPES[extension], /charset/, extension)
})

test('the types are grouped by type and indented below their placeholder', () => {
  const types = { html: 'text/html; charset=utf-8', css: 'text/css; charset=utf-8', htm: 'text/html; charset=utf-8', png: 'image/png' }
  const rendered = renderTypes('a {\n  {{content_types}}\n}', types)
  assert.equal(rendered, 'a {\n  types {\n      text/html html htm;\n      text/css css;\n      image/png png;\n  }\n  charset_types text/css;\n}')
  assert.equal(renderTypes('{{content_types}}', { html: 'text/html; charset=utf-8', png: 'image/png' }), 'types {\n    text/html html;\n    image/png png;\n}')
})

test('a content type nginx or S3 would read differently is refused', () => {
  for (const value of ['text/html; charset=latin1', 'text/html;charset=utf-8', 'text/html "x"', 'text/$html', 'html', '', 1]) {
    assert.throws(() => renderTypes('{{content_types}}', { html: value }), /Unusable content type of html/, JSON.stringify(value))
  }
  for (const extension of ['HTML', 'j s', '.js', 'js;']) {
    assert.throws(() => renderTypes('{{content_types}}', { [extension]: 'text/plain' }), /Unusable extension/, extension)
  }
  assert.throws(() => renderTypes('{{content_types}}', { htm: 'text/html', html: 'text/html; charset=utf-8' }), /charset only for some/)
  assert.throws(() => renderTypes('{{content_types}}', {}), /No content types/)
  assert.throws(() => renderTypes('server {}', { png: 'image/png' }), /has no \{\{content_types\}\}/)
  assert.throws(() => renderTypes('x {{content_types}}', { png: 'image/png' }), /line of its own/)
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

test('the links of mails get the login page, uncached, and nothing below them', async () => {
  const config = await renderFiles()
  const block = config.slice(config.indexOf('location ~ ^/(invite|reset-password|confirm-email)$ {'))
  assert.notEqual(block, config)
  const body = block.slice(0, block.indexOf('}'))
  assert.match(body, /try_files \/login\/index\.html =404;/)
  assert.match(body, /add_header Cache-Control "no-cache";/)
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
