import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { test } from 'node:test'

// staging proves production only while both roots stay the same Terraform. These lines alone may differ, each by its value.
const DIFFERENCES = {
  'main.tf': [/^ {2}environment = "[a-z]+"$/m, /^ {2}profile = "[a-z]+"$/m],
  'variables.tf': [/^ {2}default {5}= "10\.[0-9]+\.0\.0\/16"$/m, /^ {2}default {5}= "db\.[a-z0-9.]+"$/m, /^ {2}default {5}= "cache\.[a-z0-9.]+"$/m],
  'outputs.tf': [],
  'versions.tf': [/^ {4}key {10}= "[a-z]+\/terraform\.tfstate"$/m],
}

const ROOTS = new URL('../terraform/environments/', import.meta.url)

const normalized = (text, patterns) => patterns.reduce((it, pattern) => it.replace(pattern, (line) => `${line.split('=')[0]}= <value>`), text)

for (const [file, patterns] of Object.entries(DIFFERENCES)) {
  test(`the ${file} of staging and production differ in the values of DIFFERENCES alone`, async () => {
    const [staging, production] = await Promise.all(['staging', 'production'].map((root) => readFile(new URL(`${root}/${file}`, ROOTS), 'utf8')))
    for (const pattern of patterns) {
      assert.match(staging, pattern, `staging lost ${pattern}`)
      assert.match(production, pattern, `production lost ${pattern}`)
    }
    assert.equal(normalized(production, patterns), normalized(staging, patterns))
  })
}

test('a change in one root alone is caught', () => {
  const patterns = DIFFERENCES['main.tf']
  const root = 'locals {\n  environment = "staging"\n  profile = "staging"\n}\n'
  assert.equal(normalized(root, patterns), normalized(root.replaceAll('staging', 'production'), patterns))
  assert.notEqual(normalized(root, patterns), normalized(`${root}module "extra" {}\n`, patterns))
})
