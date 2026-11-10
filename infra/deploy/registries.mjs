import { readdir, readFile } from 'node:fs/promises'
import { join, relative } from 'node:path'
import { parseArgs } from 'node:util'

// The registries the dependencies come from, see docs/security/dependencies.md. OpenSAML is the one exception of ADR-004, and only
// through exclusiveContent, so no other group resolves from there.
const NPM = 'https://registry.npmjs.org/'
const SHIBBOLETH = 'https://build.shibboleth.net/maven/releases'
const GRADLE_DISTRIBUTIONS = 'https\\://services.gradle.org/distributions/'
const REPOSITORY = /\b(maven|mavenLocal|mavenCentral|gradlePluginPortal|google|jcenter|ivy|flatDir)\b\s*(\(|\{)/g

// Scripts declare repositories in a handful of ways, and every other way is refused rather than read.
export function checkGradle(scripts, wrapper) {
  const violations = []
  for (const [path, text] of Object.entries(scripts)) {
    for (const match of text.matchAll(REPOSITORY)) {
      const [, name] = match
      const rest = text.slice(match.index)
      if (name === 'mavenCentral' || name === 'gradlePluginPortal') continue
      if (name === 'maven' && rest.startsWith(`maven("${SHIBBOLETH}")`) && /forRepository\s*\{\s*$/.test(text.slice(0, match.index))) continue
      violations.push(`${path}: ${name}${name === 'maven' ? ` ${/^maven\s*\(\s*("[^"]*")/.exec(rest)?.[1] ?? 'without a literal URL'}` : ''}`)
    }
  }
  const settings = scripts['backend/settings.gradle.kts'] ?? ''
  if (!/repositoriesMode\.set\(RepositoriesMode\.FAIL_ON_PROJECT_REPOS\)/.test(settings)) {
    violations.push('backend/settings.gradle.kts: projects may declare repositories of their own')
  }
  const distribution = /^distributionUrl=(.*)$/m.exec(wrapper)?.[1]
  if (!distribution?.startsWith(GRADLE_DISTRIBUTIONS)) violations.push(`gradle-wrapper.properties: Gradle comes from ${distribution}`)
  if (!/^distributionSha256Sum=[0-9a-f]{64}$/m.test(wrapper)) violations.push('gradle-wrapper.properties: no distributionSha256Sum')
  return violations
}

// pnpm writes only the integrity of a package from the registry it uses, and a tarball, a directory or a commit for anything else.
export function checkPnpm({ project = 'frontend', npmrc, workspace, lockfile }) {
  const violations = []
  const settings = npmrc.split('\n').map((it) => it.trim()).filter((it) => it !== '' && !it.startsWith('#') && !it.startsWith(';'))
  if (!settings.includes(`registry=${NPM}`)) violations.push(`${project}/.npmrc: the registry is not ${NPM}`)
  for (const line of settings) {
    if (/registry\s*=/.test(line) && line !== `registry=${NPM}`) violations.push(`${project}/.npmrc: ${line.split('=')[0]}`)
  }
  for (const line of workspace.split('\n')) {
    if (/^\s*['"]?(@[^:]+:)?registr(y|ies)['"]?\s*:/.test(line)) violations.push(`${project}/pnpm-workspace.yaml: ${line.trim()}`)
  }
  if (lockfile === '') violations.push(`${project}/pnpm-lock.yaml: missing`)
  for (const [index, line] of lockfile.split('\n').entries()) {
    const resolution = /^\s+resolution: (.*)$/.exec(line)?.[1]
    if (resolution !== undefined && !/^\{integrity: sha512-[A-Za-z0-9+/=]+\}$/.test(resolution)) {
      violations.push(`${project}/pnpm-lock.yaml:${index + 1}: resolution ${resolution}`)
    }
    if (/^\s+(specifier|version): ['"]?(file:|git[+:]|https?:|github:)/.test(line)) violations.push(`${project}/pnpm-lock.yaml:${index + 1}: ${line.trim()}`)
  }
  return violations
}

// The pages and the release scripts, each a pnpm project with its own lockfile.
export const PNPM_PROJECTS = ['frontend', 'infra/deploy']

async function gradleScripts(root) {
  const entries = await readdir(join(root, 'backend'), { recursive: true })
  const paths = entries.filter((it) => it.endsWith('.gradle.kts') && !/(^|\/)(build|\.gradle|\.kotlin)\//.test(it))
  return Object.fromEntries(
    await Promise.all(paths.map(async (it) => [relative(root, join(root, 'backend', it)), await readFile(join(root, 'backend', it), 'utf8')])),
  )
}

export async function check(root) {
  const text = (path) => readFile(join(root, path), 'utf8').catch(() => '')
  const pnpm = await Promise.all(
    PNPM_PROJECTS.map(async (project) =>
      checkPnpm({
        project,
        npmrc: await text(`${project}/.npmrc`),
        workspace: await text(`${project}/pnpm-workspace.yaml`),
        lockfile: await text(`${project}/pnpm-lock.yaml`),
      }),
    ),
  )
  return [...checkGradle(await gradleScripts(root), await text('backend/gradle/wrapper/gradle-wrapper.properties')), ...pnpm.flat()]
}

if (import.meta.main) {
  const { values } = parseArgs({ options: { root: { type: 'string', default: '.' } } })
  const violations = await check(values.root)
  for (const violation of violations) console.error(`not allowed: ${violation}`)
  if (violations.length > 0) process.exitCode = 1
  else console.log('Gradle resolves from Maven Central, the Gradle Plugin Portal and Shibboleth for OpenSAML, pnpm from npm')
}
