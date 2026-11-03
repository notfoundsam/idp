import { writeFile } from 'node:fs/promises'
import openapiTS, { astToString } from 'openapi-typescript'

export const contracts = ['auth', 'account'] as const

const contractsDir = new URL('../../../../contracts/openapi/', import.meta.url)

export function outputOf(contract: string): URL {
  return new URL(`../src/generated/${contract}.ts`, import.meta.url)
}

export async function generate(contract: string): Promise<string> {
  const banner = `// Generated from contracts/openapi/${contract}.yaml by \`pnpm generate\`. Do not edit.\n\n`
  return banner + astToString(await openapiTS(new URL(`${contract}.yaml`, contractsDir)))
}

if (import.meta.main) {
  for (const contract of contracts) await writeFile(outputOf(contract), await generate(contract))
}
