import assert from 'node:assert/strict'
import { test } from 'node:test'
import { changedPaths, classify } from './changes.mjs'

const both = { frontend: true, backend: true }
const neither = { frontend: false, backend: false }

test('a change of the frontend alone or of the backend alone releases that side', () => {
  assert.deepEqual(classify(['frontend/apps/login/src/main.ts', 'frontend/pnpm-lock.yaml']), { frontend: true, backend: false })
  assert.deepEqual(classify(['infra/web/content-types.mjs']), { frontend: true, backend: false })
  assert.deepEqual(classify(['backend/app/src/main/kotlin/idp/app/AppApplication.kt', 'backend/Dockerfile']), { frontend: false, backend: true })
  assert.deepEqual(classify(['backend/db/src/main/resources/db/tenant/V40__x.sql']), { frontend: false, backend: true })
})

test('tests, documents, Terraform, the local environment and the release scripts release nothing', () => {
  assert.deepEqual(
    classify([
      'docs/release.md',
      'README.md',
      'backend/app/src/test/kotlin/idp/app/AppApplicationTest.kt',
      'backend/shared-kernel/src/testFixtures/kotlin/idp/sharedkernel/testing/TestDatabase.kt',
      'backend/architecture-tests/src/test/kotlin/X.kt',
      'frontend/e2e/login.spec.ts',
      'frontend/apps/login/src/steps.test.ts',
      'infra/terraform/modules/app/main.tf',
      'infra/deploy/plan-release.mjs',
      'infra/web/nginx.conf.template',
      'infra/pgbouncer/Dockerfile',
      'dev-tools/smoke.sh',
      '.github/workflows/deploy.yml',
      'Makefile',
      'docker-compose.yml',
    ]),
    neither,
  )
  assert.deepEqual(classify([]), neither)
})

test('a contract, an unknown path and a missing base release both sides', () => {
  assert.deepEqual(classify(['contracts/openapi/auth.yaml']), both)
  assert.deepEqual(classify(['docs/x.md', 'build.sh']), both)
  assert.deepEqual(classify(['backend/settings.gradle.kts', 'frontend/package.json']), both)
  assert.deepEqual(classify(null), both)
})

test('the paths come from git between the live commit and the release, and an unknown base gives none', async () => {
  const calls = []
  const git = async (args) => {
    calls.push(args.join(' '))
    if (args[0] === 'cat-file' && args[2].startsWith('gone')) throw new Error('fatal: Not a valid object name')
    return args[0] === 'diff' ? 'frontend/a.ts\nbackend/b.kt\n' : ''
  }

  assert.deepEqual(await changedPaths('abc', 'def', git), ['frontend/a.ts', 'backend/b.kt'])
  assert.deepEqual(calls, ['cat-file -e abc^{commit}', 'diff --name-only --no-renames abc def'])
  assert.equal(await changedPaths('gone', 'def', git), null)
  assert.equal(await changedPaths(undefined, 'def', git), null)
})
