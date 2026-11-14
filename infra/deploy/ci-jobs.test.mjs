import assert from 'node:assert/strict'
import { test } from 'node:test'
import { jobsFor, outputLines } from './ci-jobs.mjs'

const NOTHING = { backend: false, frontend: false, infra: false, contracts: false, e2e: false, codeql: [] }
const BOTH_LANGUAGES = ['java-kotlin', 'javascript-typescript']
const EVERY_JOB = { backend: true, frontend: true, infra: true, contracts: true, e2e: true, codeql: BOTH_LANGUAGES }

test('documents alone run no job', () => {
  assert.deepEqual(jobsFor(['docs/release.md', 'README.md', 'infra/terraform/README.md', 'docs/adr/009-terraform.md']), NOTHING)
})

test('Terraform alone runs only the job infra', () => {
  assert.deepEqual(jobsFor(['infra/terraform/modules/data/main.tf']), { ...NOTHING, infra: true })
})

test('the release scripts run the job infra and the CodeQL of the scripts, not the environment', () => {
  assert.deepEqual(jobsFor(['infra/deploy/alarms.mjs']), { ...NOTHING, infra: true, codeql: ['javascript-typescript'] })
})

test('Kotlin runs the backend, the environment and the CodeQL of Kotlin', () => {
  assert.deepEqual(jobsFor(['backend/app/src/main/kotlin/idp/app/App.kt']), { ...NOTHING, backend: true, e2e: true, codeql: ['java-kotlin'] })
})

test('a test of the backend skips the environment, which runs the jars', () => {
  assert.equal(jobsFor(['backend/app/src/test/kotlin/idp/app/AppTest.kt']).e2e, false)
  assert.equal(jobsFor(['backend/shared-kernel/src/testFixtures/kotlin/idp/sharedkernel/testing/TestDatabase.kt']).e2e, false)
})

test('a Gradle script runs the job infra too, whose check of the registries reads it', () => {
  assert.equal(jobsFor(['backend/app/build.gradle.kts']).infra, true)
  assert.equal(jobsFor(['backend/settings.gradle.kts']).infra, true)
  assert.equal(jobsFor(['backend/app/src/main/kotlin/idp/app/App.kt']).infra, false)
})

test('a page runs the frontend, the environment and the CodeQL of the scripts', () => {
  assert.deepEqual(jobsFor(['frontend/apps/console/src/console.ts']), { ...NOTHING, frontend: true, e2e: true, codeql: ['javascript-typescript'] })
})

test('a unit test of a page skips the environment, a scenario of it does not', () => {
  assert.equal(jobsFor(['frontend/apps/console/src/console.test.ts']).e2e, false)
  assert.equal(jobsFor(['frontend/e2e/admin-users.spec.ts']).e2e, true)
  assert.equal(jobsFor(['frontend/e2e/support/login.test.ts']).e2e, true)
})

test('the pnpm lockfile runs the job infra too', () => {
  assert.equal(jobsFor(['frontend/pnpm-lock.yaml']).infra, true)
  assert.equal(jobsFor(['frontend/apps/console/package.json']).infra, false)
})

test('a contract runs both sides, their comparison with the release and the environment', () => {
  assert.deepEqual(jobsFor(['contracts/openapi/admin.yaml']), { ...NOTHING, backend: true, frontend: true, contracts: true, e2e: true })
})

test('docker-compose.yml runs every side that takes an image from it', () => {
  assert.deepEqual(jobsFor(['docker-compose.yml']), { ...NOTHING, backend: true, frontend: true, infra: true, e2e: true })
})

test('the images of AWS that the backend tests build run the backend', () => {
  for (const path of ['infra/otel-collector/config.yaml', 'infra/db-bootstrap/bootstrap-owner.sh', 'infra/pgbouncer/Dockerfile']) {
    assert.equal(jobsFor([path]).backend, true, path)
  }
})

test('the files of the web server run the frontend, whose tests check them', () => {
  assert.equal(jobsFor(['infra/web/nginx.conf.template']).frontend, true)
})

test('the SP of the local environment runs the backend, whose interop test starts it', () => {
  assert.equal(jobsFor(['dev-tools/simplesamlphp/authsources.php']).backend, true)
})

test('the release workflows run only the job infra, which lints them', () => {
  assert.deepEqual(jobsFor(['.github/workflows/deploy.yml']), { ...NOTHING, infra: true })
})

test('what decides the jobs runs every job', () => {
  for (const path of ['.github/workflows/ci.yml', '.github/workflows/codeql.yml', '.github/actions/ci-jobs/action.yml', 'Makefile', 'infra/deploy/ci-jobs.mjs']) {
    assert.deepEqual(jobsFor([path]), EVERY_JOB, path)
  }
})

test('a push to main runs every job, whatever it changed', () => {
  assert.deepEqual(jobsFor([], { all: true }), EVERY_JOB)
})

test('an unknown path runs the environment, which might start it', () => {
  assert.deepEqual(jobsFor(['tools/new.sh']), { ...NOTHING, e2e: true })
})

test('the outputs are one line per job, the CodeQL languages as JSON', () => {
  assert.deepEqual(outputLines(jobsFor(['frontend/apps/login/src/main.ts'])), [
    'backend=false',
    'frontend=true',
    'infra=false',
    'contracts=false',
    'e2e=true',
    'codeql=["javascript-typescript"]',
  ])
  assert.deepEqual(outputLines(jobsFor(['docs/testing.md'])).at(-1), 'codeql=[]')
})
