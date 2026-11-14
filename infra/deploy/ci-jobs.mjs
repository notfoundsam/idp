import { appendFile, readFile } from 'node:fs/promises'
import { parseArgs } from 'node:util'

// A change to what decides the jobs runs every job, so a new rule is checked by the jobs it could skip.
const EVERYTHING = [/^\.github\/workflows\/(ci|codeql)\.yml$/, /^\.github\/actions\//, /^Makefile$/, /^infra\/deploy\/ci-jobs\.mjs$/]

const COMPOSE = /^docker-compose\.yml$/
const CONTRACTS = /^contracts\//

// The paths each job of ci.yml reads. backend/build.gradle.kts takes the images of its tests from docker-compose.yml and builds
// the ones of AWS from infra; check-registries in the job infra reads the Gradle scripts and the pnpm lockfiles.
const PATHS = {
  backend: [/^backend\//, CONTRACTS, COMPOSE, /^dev-tools\/(postgres|pgbouncer|simplesamlphp)\//, /^infra\/(otel-collector|db-bootstrap|pgbouncer)\//],
  frontend: [/^frontend\//, CONTRACTS, COMPOSE, /^infra\/web\//],
  infra: [/^infra\//, /^\.github\//, COMPOSE, /^backend\/(.+\/)?[^/]+\.gradle\.kts$/, /^frontend\/(pnpm-lock\.yaml|pnpm-workspace\.yaml|\.npmrc)$/],
  contracts: [CONTRACTS],
}

// The whole local environment runs, so e2e runs unless every path is one it never starts: Terraform, the release scripts, the
// other workflows and the unit tests.
const NOT_E2E = [
  /^LICENSE$/,
  /^\.gitignore$/,
  /^renovate\.json$/,
  /^infra\/terraform\//,
  /^infra\/deploy\//,
  /^\.github\/workflows\/(deploy|rollback)\.yml$/,
  /^backend\/(.+\/)?src\/(test|testFixtures)\//,
  /^frontend\/(?!e2e\/).*\.test\.ts$/,
]

// CodeQL analyzes the Kotlin that Gradle compiles and every script of the repository.
const CODEQL = {
  'java-kotlin': [/^backend\//],
  'javascript-typescript': [/\.([cm]?[jt]s|vue|html)$/],
}

// No job reads a document.
const DOCUMENTS = [/^docs\//, /\.md$/]

const any = (paths, patterns) => paths.some((path) => patterns.some((it) => it.test(path)))

export function jobsFor(changed, { all = false } = {}) {
  const paths = changed.filter((path) => !DOCUMENTS.some((it) => it.test(path)))
  const everything = all || any(paths, EVERYTHING)
  const jobs = Object.fromEntries(Object.entries(PATHS).map(([name, patterns]) => [name, everything || any(paths, patterns)]))
  jobs.e2e = everything || paths.some((path) => !NOT_E2E.some((it) => it.test(path)))
  jobs.codeql = Object.keys(CODEQL).filter((language) => everything || any(paths, CODEQL[language]))
  return jobs
}

export const outputLines = (jobs) => Object.entries(jobs).map(([name, value]) => `${name}=${Array.isArray(value) ? JSON.stringify(value) : value}`)

if (import.meta.main) {
  const { values } = parseArgs({ options: { all: { type: 'boolean', default: false }, paths: { type: 'string' } } })
  if (!values.all && values.paths === undefined) throw new Error('Usage: node ci-jobs.mjs --all | --paths <file with one path per line>')
  const paths = values.all ? [] : (await readFile(values.paths, 'utf8')).split('\n').filter((it) => it !== '')
  const lines = outputLines(jobsFor(paths, { all: values.all }))
  console.log(lines.join('\n'))
  if (process.env.GITHUB_OUTPUT) await appendFile(process.env.GITHUB_OUTPUT, `${lines.join('\n')}\n`)
}
