import { readFile } from 'node:fs/promises'
import { expect, test } from 'vitest'
import { contracts, generate, outputOf } from '../scripts/generate'

test.each(contracts)('the committed types of %s match the contract, pnpm generate renews them', async (contract) => {
  expect(await readFile(outputOf(contract), 'utf8')).toBe(await generate(contract))
})

test('the types of auth name the operations of the login flow', async () => {
  const types = await generate('auth')
  for (const operation of ['startFlow', 'getFlow', 'completeFlow']) expect(types).toContain(`    ${operation}: {`)
  expect(types).toContain('"/api/flows/{id}": {')
})

test('a contract that does not exist is refused', async () => {
  await expect(generate('missing')).rejects.toThrow()
})
