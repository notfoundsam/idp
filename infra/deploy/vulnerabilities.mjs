import { readFile } from 'node:fs/promises'
import { parseArgs } from 'node:util'

const DAY = 86_400_000

// The time frames of docs/security/dependencies.md, in days from the publication of the advisory.
export const DEADLINES = { CRITICAL: 7, HIGH: 30, MEDIUM: 90, LOW: 180, UNKNOWN: 90 }
export const LONGEST_ACCEPTANCE = 90

// The vulnerabilities of Trivy reports over the SBOMs, one per advisory and package version.
export function findingsOf(reports) {
  const seen = new Map()
  for (const report of reports) {
    for (const result of report.Results ?? []) {
      for (const vulnerability of result.Vulnerabilities ?? []) {
        const key = `${vulnerability.VulnerabilityID} ${vulnerability.PkgName}@${vulnerability.InstalledVersion}`
        if (!seen.has(key)) seen.set(key, vulnerability)
      }
    }
  }
  return [...seen.values()]
}

// A scan of an empty SBOM finds nothing, so a build that lost its components must not read as a clean one.
export function emptySboms(sboms) {
  return sboms.filter(({ bom }) => !(bom.components?.length > 0)).map(({ path }) => `${path} lists no component`)
}

// An acceptance names an advisory and a package, why the release may go on, and a date of at most 90 days ahead.
export function checkAcceptances(acceptances, now) {
  return acceptances.flatMap((it) => {
    const until = Date.parse(it.until ?? '')
    if (typeof it.id !== 'string' || typeof it.package !== 'string' || typeof it.reason !== 'string' || it.reason.trim() === '' || Number.isNaN(until)) {
      return [`An acceptance needs id, package, reason and until: ${JSON.stringify(it)}`]
    }
    return until - now > LONGEST_ACCEPTANCE * DAY ? [`The acceptance of ${it.id} in ${it.package} runs past ${LONGEST_ACCEPTANCE} days`] : []
  })
}

// A vulnerability without a date counts as overdue, so a gap in the database never lets a release through.
export function judge(findings, acceptances, now) {
  const breaches = []
  const open = []
  for (const finding of findings) {
    const severity = DEADLINES[finding.Severity] === undefined ? 'UNKNOWN' : finding.Severity
    const published = Date.parse(finding.PublishedDate ?? '')
    const due = Number.isNaN(published) ? -Infinity : published + DEADLINES[severity] * DAY
    const accepted = acceptances.find((it) => it.id === finding.VulnerabilityID && it.package === finding.PkgName && Date.parse(it.until) > now)
    const line =
      `${severity} ${finding.VulnerabilityID} in ${finding.PkgName} ${finding.InstalledVersion}` +
      `${finding.FixedVersion ? `, fixed in ${finding.FixedVersion}` : ', no fix yet'}` +
      `${Number.isFinite(due) ? `, due ${new Date(due).toISOString().slice(0, 10)}` : ', no publication date'}`
    if (accepted !== undefined) open.push(`${line}, accepted until ${accepted.until}: ${accepted.reason}`)
    else if (due <= now) breaches.push(line)
    else open.push(line)
  }
  return { breaches, open }
}

if (import.meta.main) {
  const { values } = parseArgs({ options: { sbom: { type: 'string', multiple: true }, report: { type: 'string', multiple: true }, accepted: { type: 'string' } } })
  if (!values.sbom?.length || !values.report?.length || !values.accepted) {
    throw new Error('Usage: node vulnerabilities.mjs --sbom <cdx json> [--sbom ...] --report <trivy json> [--report ...] --accepted <accepted-vulnerabilities.json>')
  }
  const now = Date.now()
  const json = async (path) => JSON.parse(await readFile(path, 'utf8'))
  const acceptances = await json(values.accepted)
  const sboms = await Promise.all(values.sbom.map(async (path) => ({ path, bom: await json(path) })))
  const reports = await Promise.all(values.report.map(json))
  const { breaches, open } = judge(findingsOf(reports), acceptances, now)
  const problems = [...emptySboms(sboms), ...checkAcceptances(acceptances, now), ...breaches]
  for (const line of open) console.log(process.env.GITHUB_ACTIONS === 'true' ? `::warning title=Vulnerability::${line}` : `open ${line}`)
  for (const line of problems) console.error(process.env.GITHUB_ACTIONS === 'true' ? `::error title=Vulnerability past its time frame::${line}` : `FAIL ${line}`)
  if (problems.length > 0) process.exitCode = 1
  else console.log(`No vulnerability is past its time frame, ${open.length} open within it`)
}
