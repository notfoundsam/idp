// @vitest-environment node
import { fileURLToPath } from 'node:url'
import { build, type Plugin } from 'vite'
import { describe, expect, test } from 'vitest'
import { VENDOR } from './vite.config'

interface Chunk {
  type: 'chunk' | 'asset'
  name: string
  fileName: string
  isEntry?: boolean
  moduleIds?: string[]
}

const root = fileURLToPath(new URL('.', import.meta.url))

/** Stands in for a change of the app alone: texts in modules of the entry and of a lazy page. */
const editedApp: Plugin = {
  name: 'edited-app',
  transform(code, id) {
    if (id.endsWith('/src/context.ts')) return code.replace('The console is not provided', 'The console is missing')
    if (id.endsWith('/src/messages.ts')) return code.replace('Something went wrong. Try again.', 'Something failed. Try again.')
    return null
  },
}

async function chunks(options: { version?: string; plugins?: Plugin[] } = {}): Promise<Chunk[]> {
  const result = await build({
    root,
    configFile: `${root}vite.config.ts`,
    logLevel: 'silent',
    define: { __APP_VERSION__: JSON.stringify(options.version ?? '1.0.0') },
    plugins: options.plugins ?? [],
    build: { write: false },
  })
  const outputs = Array.isArray(result) ? result : [result]
  return outputs.flatMap((it) => ('output' in it ? (it.output as Chunk[]) : [])).filter((it) => it.type === 'chunk')
}

const vendorOf = (all: Chunk[]) => {
  const vendor = all.filter((it) => it.name === VENDOR)
  expect(vendor).toHaveLength(1)
  return vendor[0] as Chunk
}

const appFiles = (all: Chunk[]) => all.filter((it) => it.name !== VENDOR).map((it) => it.fileName)

describe('the vendor chunk', { timeout: 120_000 }, () => {
  test('holds Vue, Vue Router, Pinia and TanStack Query, and nothing of the app', async () => {
    const all = await chunks()
    const vendor = vendorOf(all)
    expect(vendor.fileName).toMatch(/^assets\/vendor-[A-Za-z0-9_-]+\.js$/)
    const modules = vendor.moduleIds ?? []
    for (const library of ['@vue/runtime-core', 'vue-router', 'pinia', '@tanstack/vue-query', '@tanstack/query-core']) {
      expect(modules.some((id) => id.includes(`/node_modules/${library}/`))).toBe(true)
    }
    expect(modules.filter((id) => !id.includes('/node_modules/'))).toEqual([])
    const elsewhere = all.filter((it) => it.name !== VENDOR).flatMap((it) => it.moduleIds ?? [])
    expect(elsewhere.filter((id) => id.includes('/node_modules/'))).toEqual([])
  })

  test('keeps its hash when only the app changes, so browsers keep it cached across such a release', async () => {
    const before = await chunks()
    const edited = await chunks({ plugins: [editedApp] })
    const released = await chunks({ version: '1.1.0' })

    expect(vendorOf(edited).fileName).toBe(vendorOf(before).fileName)
    expect(vendorOf(released).fileName).toBe(vendorOf(before).fileName)
    expect(appFiles(edited)).not.toEqual(appFiles(before))
    expect(appFiles(released)).not.toEqual(appFiles(before))
  })
})
