import { execFile } from 'node:child_process'
import { promisify } from 'node:util'

// Paths that never reach what a release deploys: tests, documents, the local environment, Terraform, which its own workflow
// applies, and the release scripts, which run from the checkout. Everything else counts, an unknown path for both sides.
const NEITHER = [
  /^docs\//,
  /\.md$/,
  /^LICENSE$/,
  /^\.github\//,
  /^\.gitignore$/,
  /^renovate\.json$/,
  /^Makefile$/,
  /^docker-compose\.yml$/,
  /^dev-tools\//,
  /^infra\/terraform\//,
  /^infra\/deploy\//,
  /^infra\/(pgbouncer|db-bootstrap|otel-collector)\//,
  /^infra\/web\/(?!content-types\.mjs$)/,
  /^frontend\/e2e\//,
  /^frontend\/lint\//,
  /^frontend\/.*\.test\.ts$/,
  /^backend\/[^/]+\/src\/test\//,
  /^backend\/[^/]+\/src\/testFixtures\//,
  /^backend\/architecture-tests\//,
]
const FRONTEND = [/^frontend\//, /^infra\/web\/content-types\.mjs$/]
const BACKEND = [/^backend\//]
const BOTH = [/^contracts\//]

const matches = (patterns, path) => patterns.some((it) => it.test(path))

// No list means no base to compare with, so both sides go out.
export function classify(paths) {
  if (paths === null) return { frontend: true, backend: true }
  const relevant = paths.filter((path) => !matches(NEITHER, path))
  const unknown = relevant.some((path) => !matches([...FRONTEND, ...BACKEND, ...BOTH], path))
  return {
    frontend: unknown || relevant.some((path) => matches([...FRONTEND, ...BOTH], path)),
    backend: unknown || relevant.some((path) => matches([...BACKEND, ...BOTH], path)),
  }
}

async function git(args) {
  const { stdout } = await promisify(execFile)('git', args, { maxBuffer: 64 * 1024 * 1024 })
  return stdout
}

// A base that this checkout does not hold, such as a commit of a branch that is gone, gives no list.
export async function changedPaths(base, head, run = git) {
  if (base === undefined) return null
  try {
    await run(['cat-file', '-e', `${base}^{commit}`])
  } catch {
    return null
  }
  const output = await run(['diff', '--name-only', '--no-renames', base, head])
  return output.split('\n').filter((it) => it !== '')
}
