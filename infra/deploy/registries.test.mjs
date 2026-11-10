import assert from 'node:assert/strict'
import { test } from 'node:test'
import { checkGradle, checkPnpm } from './registries.mjs'

const SETTINGS = `pluginManagement { repositories { gradlePluginPortal()
    mavenCentral() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        exclusiveContent {
            forRepository { maven("https://build.shibboleth.net/maven/releases") { name = "shibboleth" } }
            filter { includeGroup("org.opensaml") }
        }
    }
}`
const WRAPPER = `distributionSha256Sum=${'a'.repeat(64)}\ndistributionUrl=https\\://services.gradle.org/distributions/gradle-9.8.2-bin.zip\n`
const NPMRC = 'registry=https://registry.npmjs.org/\n'
const LOCK = `packages:

  vue@3.5.44:
    resolution: {integrity: sha512-AbC+/=}

importers:
  apps/login:
    dependencies:
      '@idp/ui':
        specifier: workspace:*
        version: link:../../packages/ui
`
const gradle = (scripts, wrapper = WRAPPER) => checkGradle({ 'backend/settings.gradle.kts': SETTINGS, ...scripts }, wrapper)
const pnpm = (files) => checkPnpm({ npmrc: NPMRC, workspace: 'packages:\n  - apps/*\n', lockfile: LOCK, ...files })

test('Maven Central, the Plugin Portal and Shibboleth inside exclusiveContent pass', () => {
  assert.deepEqual(gradle({}), [])
  assert.deepEqual(pnpm({}), [])
})

test('another repository in any script fails, also Shibboleth outside exclusiveContent', () => {
  assert.deepEqual(gradle({ 'backend/app/build.gradle.kts': 'repositories { maven("https://jitpack.io") }' }), [
    'backend/app/build.gradle.kts: maven "https://jitpack.io"',
  ])
  assert.equal(gradle({ 'backend/db/build.gradle.kts': 'repositories { maven { url = uri("https://repo.example") } }' }).length, 1)
  assert.equal(gradle({ 'backend/db/build.gradle.kts': 'repositories { mavenLocal() }' }).length, 1)
  assert.equal(gradle({ 'backend/db/build.gradle.kts': 'repositories { google() }' }).length, 1)
  assert.equal(gradle({ 'backend/db/build.gradle.kts': 'repositories { maven("https://build.shibboleth.net/maven/releases") }' }).length, 1)
})

test('projects allowed their own repositories, or a wrapper from elsewhere or unpinned, fail', () => {
  assert.match(checkGradle({ 'backend/settings.gradle.kts': 'repositories { mavenCentral() }' }, WRAPPER)[0], /repositories of their own/)
  assert.match(gradle({}, WRAPPER.replace('services.gradle.org', 'mirror.example'))[0], /Gradle comes from/)
  assert.match(gradle({}, WRAPPER.replace(/distributionSha256Sum=.*\n/, ''))[0], /no distributionSha256Sum/)
})

test('another npm registry, a scoped one or a package from a tarball, git or a file fails', () => {
  assert.match(pnpm({ npmrc: 'registry=https://npm.example/\n' })[0], /the registry is not/)
  assert.match(pnpm({ npmrc: `${NPMRC}@acme:registry=https://npm.example/\n` })[0], /@acme:registry/)
  assert.match(pnpm({ workspace: 'registry: https://npm.example/\n' })[0], /pnpm-workspace.yaml/)
  assert.match(pnpm({ lockfile: LOCK.replace('{integrity: sha512-AbC+/=}', '{tarball: https://evil.example/vue.tgz}') })[0], /resolution \{tarball/)
  assert.match(pnpm({ lockfile: LOCK.replace('link:../../packages/ui', 'git+https://github.com/x/y.git') })[0], /git\+https/)
  assert.match(pnpm({ lockfile: LOCK.replace('workspace:*', 'file:../vendor/ui.tgz') })[0], /file:/)
})
