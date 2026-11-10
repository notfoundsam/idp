import assert from 'node:assert/strict'
import { test } from 'node:test'
import { overdueUpdates } from './updates.mjs'
import { checkAcceptances, DEADLINES, emptySboms, findingsOf, judge } from './vulnerabilities.mjs'

const NOW = Date.parse('2026-11-10T00:00:00Z')
const DAY = 86_400_000
const ago = (days) => new Date(NOW - days * DAY).toISOString()
const finding = (fields) => ({ VulnerabilityID: 'CVE-2026-1', PkgName: 'org.example:lib', InstalledVersion: '1.0', FixedVersion: '1.1', ...fields })
const report = (...vulnerabilities) => ({ Results: [{ Target: 'bom.json', Vulnerabilities: vulnerabilities }] })

test('the time frames are those of docs/security/dependencies.md', () => {
  assert.deepEqual(DEADLINES, { CRITICAL: 7, HIGH: 30, MEDIUM: 90, LOW: 180, UNKNOWN: 90 })
})

test('a vulnerability within its time frame is open, one past it stops the release', () => {
  const { breaches, open } = judge(
    [
      finding({ VulnerabilityID: 'CVE-critical-new', Severity: 'CRITICAL', PublishedDate: ago(6) }),
      finding({ VulnerabilityID: 'CVE-critical-old', Severity: 'CRITICAL', PublishedDate: ago(8) }),
      finding({ VulnerabilityID: 'CVE-high-old', Severity: 'HIGH', PublishedDate: ago(31) }),
      finding({ VulnerabilityID: 'CVE-medium', Severity: 'MEDIUM', PublishedDate: ago(60) }),
      finding({ VulnerabilityID: 'CVE-low', Severity: 'LOW', PublishedDate: ago(170) }),
    ],
    [],
    NOW,
  )
  assert.deepEqual(
    breaches.map((it) => it.split(' ')[1]),
    ['CVE-critical-old', 'CVE-high-old'],
  )
  assert.equal(open.length, 3)
  assert.match(breaches[0], /^CRITICAL CVE-critical-old in org.example:lib 1.0, fixed in 1.1, due 2026-11-09$/)
})

test('a vulnerability without a date or with a severity of its own counts as overdue or as unknown', () => {
  const { breaches } = judge([finding({ Severity: 'HIGH' }), finding({ VulnerabilityID: 'CVE-2', Severity: 'WHATEVER', PublishedDate: ago(91) })], [], NOW)
  assert.equal(breaches.length, 2)
  assert.match(breaches[0], /no publication date/)
  assert.match(breaches[1], /^UNKNOWN/)
})

test('an acceptance with a reason lets the release go on until its date, and never for more than 90 days', () => {
  const overdue = finding({ Severity: 'CRITICAL', PublishedDate: ago(30), FixedVersion: '' })
  const accepted = [{ id: 'CVE-2026-1', package: 'org.example:lib', reason: 'Not reachable: only the CLI parses such files', until: '2026-12-01' }]
  assert.deepEqual(judge([overdue], accepted, NOW).breaches, [])
  assert.match(judge([overdue], accepted, NOW).open[0], /no fix yet.*accepted until 2026-12-01: Not reachable/)
  assert.equal(judge([overdue], [{ ...accepted[0], until: '2026-11-01' }], NOW).breaches.length, 1)
  assert.equal(judge([overdue], [{ ...accepted[0], package: 'other' }], NOW).breaches.length, 1)
  assert.deepEqual(checkAcceptances(accepted, NOW), [])
  assert.match(checkAcceptances([{ ...accepted[0], until: '2027-06-01' }], NOW)[0], /runs past 90 days/)
  assert.match(checkAcceptances([{ ...accepted[0], reason: ' ' }], NOW)[0], /needs id, package, reason and until/)
})

test('an SBOM without components fails, since a scan of it finds nothing', () => {
  assert.deepEqual(emptySboms([{ path: 'backend.cdx.json', bom: { components: [{ purl: 'pkg:maven/a/b@1' }] } }, { path: 'idp-login.cdx.json', bom: {} }]), [
    'idp-login.cdx.json lists no component',
  ])
})

test('the reports of both SBOMs are read once per advisory and package version', () => {
  const one = finding({ Severity: 'LOW', PublishedDate: ago(1) })
  assert.equal(findingsOf([report(one), report(one, finding({ InstalledVersion: '0.9' })), { Results: [{ Target: 'x' }] }]).length, 2)
})

test('a Renovate pull request open past its time frame stops the release, a major one after 90 days', () => {
  const pulls = [
    { number: 1, title: 'chore(deps): update vue to 3.5.45', createdAt: ago(29), labels: [{ name: 'dependencies' }] },
    { number: 2, title: 'chore(deps): update kotest to 6.2.7', createdAt: ago(31), labels: [{ name: 'dependencies' }] },
    { number: 3, title: 'chore(deps): update spring boot to 5', createdAt: ago(60), labels: [{ name: 'dependencies' }, { name: 'major' }] },
    { number: 4, title: 'chore(deps): update vite to 9', createdAt: ago(91), labels: [{ name: 'major' }] },
  ]
  assert.deepEqual(overdueUpdates(pulls, NOW), [
    '#2 chore(deps): update kotest to 6.2.7 (minor or patch), due 2026-11-09',
    '#4 chore(deps): update vite to 9 (major), due 2026-11-09',
  ])
  assert.equal(overdueUpdates([{ number: 5, title: 'x', labels: [] }], NOW).length, 1)
})
