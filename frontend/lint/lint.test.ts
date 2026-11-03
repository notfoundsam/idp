import { ESLint } from 'eslint'
import { fileURLToPath } from 'node:url'
import { describe, expect, test } from 'vitest'

const eslint = new ESLint({ cwd: fileURLToPath(new URL('..', import.meta.url)) })

async function problems(code: string, file = 'apps/login/src/fixture.ts') {
  const [result] = await eslint.lintText(code, { filePath: file })
  return (result?.messages ?? []).map((message) => message.ruleId ?? message.message)
}

describe('the lint refuses', () => {
  test.each([
    ['eval', 'eval("1")', 'no-eval'],
    ['a string run as code by a timer', 'setTimeout("alert(1)", 1)', 'no-implied-eval'],
    ['the Function constructor', 'new Function("return 1")', 'no-new-func'],
    ['loose equality', 'export const same = (a: unknown, b: unknown) => a == b', 'eqeqeq'],
    ['a listener for postMessage', 'window.addEventListener("message", () => {})', 'no-restricted-syntax'],
    ['a handler for postMessage', 'window.onmessage = () => {}', 'no-restricted-syntax'],
    ['HTML written into an element', 'document.body.innerHTML = location.hash', 'no-restricted-syntax'],
    ['HTML inserted next to an element', 'document.body.insertAdjacentHTML("beforeend", location.hash)', 'no-restricted-syntax'],
    ['document.write', 'document.write(location.hash)', 'no-restricted-syntax'],
    ['an object keyed by any string', 'export type Users = Record<string, number>', 'no-restricted-syntax'],
    ['an index signature', 'export interface Users { [login: string]: number }', 'no-restricted-syntax'],
    ['a javascript: URL', 'location.href = "javascript:alert(1)"', 'no-script-url'],
  ])('%s', async (_, code, rule) => {
    expect(await problems(code)).toContain(rule)
  })

  test('v-html in a component', async () => {
    expect(await problems('<template><p v-html="text"></p></template>', 'apps/login/src/LintFixture.vue')).toContain('vue/no-v-html')
  })

  test('loose equality in a component', async () => {
    const code = '<script setup lang="ts">\nconst a: unknown = 1\nexport const same = a == 1\n</script>\n<template><p /></template>'
    expect(await problems(code, 'apps/login/src/LintFixture.vue')).toContain('eqeqeq')
  })
})

describe('the lint accepts', () => {
  test('data with outside keys in a Map', async () => {
    expect(await problems('export const users = new Map<string, number>()\nexport const same = (a: unknown) => a === 1')).toEqual([])
  })

  test('text bound in a template', async () => {
    expect(await problems('<template><p>{{ text }}</p></template>', 'apps/login/src/LintFixture.vue')).toEqual([])
  })

  test('generated code, which it does not read', async () => {
    expect(await eslint.isPathIgnored('packages/api-client/src/generated/auth.ts')).toBe(true)
  })
})
