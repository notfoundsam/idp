import assert from 'node:assert/strict'
import { test } from 'node:test'
import { emptyAnalyses, judge, SEVERITY_LIMIT } from './code-scanning.mjs'

const rule = (id, severity) => ({ id, properties: severity === undefined ? {} : { 'security-severity': severity } })
const result = (ruleId, uri = 'app/src/main/kotlin/idp/app/A.kt', startLine = 7) => ({
  ruleId,
  message: { text: `found by ${ruleId}` },
  locations: [{ physicalLocation: { artifactLocation: { uri }, region: { startLine } } }],
})
const sarif = (rules, results, extensions = []) => ({ runs: [{ tool: { driver: { rules }, extensions }, results }] })

test('the limit is CVSS medium', () => {
  assert.equal(SEVERITY_LIMIT, 4.0)
})

test('a finding at or above the limit fails, one below it is only reported', () => {
  const { breaches, below } = judge([
    sarif(
      [rule('java/critical', '9.8'), rule('java/medium', '4.0'), rule('java/low', '3.9')],
      [result('java/critical'), result('java/medium', 'ops/B.kt', 3), result('java/low')],
    ),
  ])
  assert.deepEqual(breaches, [
    '9.8 java/critical at app/src/main/kotlin/idp/app/A.kt:7: found by java/critical',
    '4.0 java/medium at ops/B.kt:3: found by java/medium',
  ])
  assert.deepEqual(below, ['3.9 java/low at app/src/main/kotlin/idp/app/A.kt:7: found by java/low'])
})

test('the rule of a finding is found in a query pack as well as in the driver', () => {
  const { breaches } = judge([sarif([], [{ rule: { id: 'js/xss' }, message: { text: 'xss' } }], [{ name: 'codeql/javascript-queries', rules: [rule('js/xss', '6.1')] }])])
  assert.deepEqual(breaches, ['6.1 js/xss at unknown file: xss'])
})

test('a finding whose rule has no severity fails, so lost metadata never lets a pull request through', () => {
  const { breaches } = judge([sarif([rule('java/unrated')], [result('java/unrated'), result('java/unknown')])])
  assert.equal(breaches.length, 2)
  assert.match(breaches[0], /^no severity java\/unrated/)
  assert.match(breaches[1], /^no severity java\/unknown/)
})

test('a clean analysis passes, and a file without an analysis fails', () => {
  const clean = sarif([rule('java/critical', '9.8')], [])
  assert.deepEqual(judge([clean]), { breaches: [], below: [] })
  assert.deepEqual(emptyAnalyses([{ path: 'java.sarif', sarif: clean }, { path: 'javascript.sarif', sarif: { runs: [] } }, { path: 'x.sarif', sarif: {} }]), [
    'javascript.sarif holds no analysis',
    'x.sarif holds no analysis',
  ])
})
