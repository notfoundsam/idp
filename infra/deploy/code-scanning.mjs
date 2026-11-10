import { readFile } from 'node:fs/promises'
import { parseArgs } from 'node:util'

// CVSS medium and above, the security-severity that CodeQL gives its security queries.
export const SEVERITY_LIMIT = 4.0

function rulesOf(run) {
  const rules = new Map()
  for (const component of [run.tool?.driver, ...(run.tool?.extensions ?? [])]) {
    for (const rule of component?.rules ?? []) rules.set(rule.id, rule)
  }
  return rules
}

function where(result) {
  const location = result.locations?.[0]?.physicalLocation
  const line = location?.region?.startLine
  return `${location?.artifactLocation?.uri ?? 'unknown file'}${line === undefined ? '' : `:${line}`}`
}

// A result without a severity counts as one above the limit, so a rule that lost its metadata never lets a pull request through.
export function judge(sarifs) {
  const breaches = []
  const below = []
  for (const sarif of sarifs) {
    for (const run of sarif.runs ?? []) {
      const rules = rulesOf(run)
      for (const result of run.results ?? []) {
        const id = result.ruleId ?? result.rule?.id ?? 'unknown rule'
        const severity = Number.parseFloat(rules.get(id)?.properties?.['security-severity'] ?? '')
        const line = `${Number.isNaN(severity) ? 'no severity' : severity.toFixed(1)} ${id} at ${where(result)}: ${result.message?.text ?? ''}`
        if (Number.isNaN(severity) || severity >= SEVERITY_LIMIT) breaches.push(line)
        else below.push(line)
      }
    }
  }
  return { breaches, below }
}

// An analysis that produced no run looked at nothing, so it must not read as a clean one.
export function emptyAnalyses(sarifs) {
  return sarifs.filter(({ sarif }) => !(sarif.runs?.length > 0)).map(({ path }) => `${path} holds no analysis`)
}

if (import.meta.main) {
  const { values } = parseArgs({ options: { sarif: { type: 'string', multiple: true } } })
  if (!values.sarif?.length) throw new Error('Usage: node code-scanning.mjs --sarif <file> [--sarif ...]')
  const sarifs = await Promise.all(values.sarif.map(async (path) => ({ path, sarif: JSON.parse(await readFile(path, 'utf8')) })))
  const { breaches, below } = judge(sarifs.map(({ sarif }) => sarif))
  const problems = [...emptyAnalyses(sarifs), ...breaches]
  const actions = process.env.GITHUB_ACTIONS === 'true'
  const limit = SEVERITY_LIMIT.toFixed(1)
  for (const line of below) console.log(actions ? `::warning title=Code scanning::${line}` : `below ${line}`)
  for (const line of problems) console.error(actions ? `::error title=Code scanning at or above ${limit}::${line}` : `FAIL ${line}`)
  if (problems.length > 0) process.exitCode = 1
  else console.log(`No finding at or above ${limit}, ${below.length} below it`)
}
