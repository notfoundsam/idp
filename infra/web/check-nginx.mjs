import { readFile } from 'node:fs/promises'
import { contentTypeOf } from './content-types.mjs'

const { cacheControl, cases } = JSON.parse(await readFile(new URL('spa-routes.json', import.meta.url), 'utf8'))
const LOADED = /(?:src|href)="(\/(?:login|console)\/assets\/[^"]+)"/g

async function get(base, path) {
  const response = await fetch(new URL(path, base), { redirect: 'manual' })
  return {
    status: response.status,
    location: response.headers.get('location'),
    cache: response.headers.get('cache-control'),
    type: response.headers.get('content-type'),
    body: await response.text(),
  }
}

async function expected(base, route) {
  if (route.status !== undefined) return { status: route.status, location: route.location ?? null, cache: null }
  if (route.missing) return { status: 404, location: null, cache: null }
  const file = await get(base, route.file)
  return { status: 200, location: null, cache: cacheControl[route.cache], type: contentTypeOf(route.file), body: file.body }
}

function compare(failures, uri, want, got) {
  for (const [name, value] of Object.entries(want)) {
    if (got[name] !== value) failures.push(`${uri}: ${name} ${JSON.stringify(got[name]).slice(0, 80)}, expected ${JSON.stringify(value).slice(0, 80)}`)
  }
}

export async function check(base) {
  const failures = []
  const loaded = new Set()
  for (const route of cases) {
    const want = await expected(base, route)
    const got = await get(base, route.uri)
    compare(failures, route.uri, want, got)
    if (want.status === 200) for (const [, path] of got.body.matchAll(LOADED)) loaded.add(path)
  }
  if (loaded.size === 0) failures.push('The pages load no file under assets/')
  for (const path of loaded) {
    compare(failures, path, { status: 200, cache: cacheControl.assets, type: contentTypeOf(path) }, await get(base, path))
  }
  return failures
}

if (import.meta.main) {
  const [base] = process.argv.slice(2)
  if (!base) throw new Error('Usage: node check-nginx.mjs <base URL>')
  const failures = await check(base)
  for (const failure of failures) console.error(failure)
  process.exitCode = failures.length === 0 ? 0 : 1
}
