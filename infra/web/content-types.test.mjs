import assert from 'node:assert/strict'
import { test } from 'node:test'
import { contentTypeOf } from './content-types.mjs'

test('a file gets the type of its extension, whatever its case and directory', () => {
  assert.equal(contentTypeOf('login/index.html'), 'text/html; charset=utf-8')
  assert.equal(contentTypeOf('console/assets/vendor-DxxIysAt.js'), 'application/javascript; charset=utf-8')
  assert.equal(contentTypeOf('console/assets/index-B1.CSS'), 'text/css; charset=utf-8')
  assert.equal(contentTypeOf('releases/1.2.0/manifest.json'), 'application/json; charset=utf-8')
  assert.equal(contentTypeOf('login/assets/logo-a.b.svg'), 'image/svg+xml; charset=utf-8')
  assert.equal(contentTypeOf('login/assets/font-X.woff2'), 'font/woff2')
})

test('a file without a known type is refused rather than served as anything', () => {
  for (const path of ['login/assets/index.js.map', 'login/assets/README', 'login/assets/index.','login/assets.js/index', 'login/x.constructor', 'x.toString']) {
    assert.throws(() => contentTypeOf(path), /No Content-Type for/, path)
  }
})
