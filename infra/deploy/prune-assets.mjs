import { parseArgs } from 'node:util'
import { readManifests, RELEASES } from './releases.mjs'
import { cliS3 } from './s3.mjs'

const DAY = 24 * 60 * 60 * 1000

// A release stays while a page of it may still be open: it was live at some point of the period. The previous release
// stays however old, so a rollback finds its files.
export async function prune(s3, { now, days = 30, dryRun = false }) {
  const keys = await s3.list()
  const released = (await readManifests(s3, keys))
    .filter((it) => it.releasedAt !== undefined)
    .sort((a, b) => Date.parse(b.releasedAt) - Date.parse(a.releasedAt))
  if (released.length === 0) throw new Error('No release has been live, so nothing is pruned')
  const since = now.getTime() - days * DAY
  const kept = released.filter((_, index) => index < 2 || Date.parse(released[index - 1].releasedAt) >= since)
  const files = new Set(kept.flatMap((it) => it.files))
  const own = kept.map((it) => `${RELEASES}${it.version}/`)
  const deleted = keys.filter((key) => !files.has(key) && !own.some((prefix) => key.startsWith(prefix)))
  if (!dryRun) await s3.delete(deleted)
  return { kept: kept.map((it) => it.version), deleted }
}

export function parseOptions(argv) {
  const { values } = parseArgs({
    args: argv,
    options: { bucket: { type: 'string' }, days: { type: 'string', default: '30' }, 'dry-run': { type: 'boolean', default: false } },
  })
  const days = Number(values.days)
  if (!values.bucket || !/^[1-9][0-9]*$/.test(values.days)) {
    throw new Error('Usage: node prune-assets.mjs --bucket <bucket> [--days <whole days, 30 by default>] [--dry-run]')
  }
  return { bucket: values.bucket, days, dryRun: values['dry-run'] }
}

if (import.meta.main) {
  const options = parseOptions(process.argv.slice(2))
  const { kept, deleted } = await prune(cliS3(options.bucket), { ...options, now: new Date() })
  console.log(`Kept the files of ${kept.join(', ')}`)
  for (const key of deleted) console.log(`${options.dryRun ? 'Would delete' : 'Deleted'} ${key}`)
}
