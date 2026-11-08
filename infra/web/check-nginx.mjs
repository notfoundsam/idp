import { readFile } from 'node:fs/promises'

const { cacheControl, cases } = JSON.parse(await readFile(new URL('spa-routes.json', import.meta.url), 'utf8'))

async function get(base, path) {
  const response = await fetch(new URL(path, base), { redirect: 'manual' })
  return {
    status: response.status,
    location: response.headers.get('location'),
    cache: response.headers.get('cache-control'),
    body: await response.text(),
  }
}

async function expected(base, route) {
  if (route.status !== undefined) return { status: route.status, location: route.location ?? null, cache: null }
  if (route.missing) return { status: 404, location: null, cache: null }
  const file = await get(base, route.file)
  return { status: 200, location: null, cache: cacheControl[route.cache], body: file.body }
}

export async function check(base) {
  const failures = []
  for (const route of cases) {
    const want = await expected(base, route)
    const got = await get(base, route.uri)
    for (const [name, value] of Object.entries(want)) {
      if (got[name] !== value) failures.push(`${route.uri}: ${name} ${JSON.stringify(got[name]).slice(0, 80)}, expected ${JSON.stringify(value).slice(0, 80)}`)
    }
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
