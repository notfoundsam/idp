import { readdir, readFile } from 'node:fs/promises'
import { join, relative, sep } from 'node:path'
import { parseArgs } from 'node:util'
import { contentTypeOf } from '../web/content-types.mjs'
import { checkVersion, isPage, manifestBody, manifestKey, put, readManifest, RELEASES, stagedPageKey } from './releases.mjs'
import { cliS3 } from './s3.mjs'

export async function filesOf(dir) {
  const entries = await readdir(dir, { recursive: true, withFileTypes: true })
  const odd = entries.find((it) => !it.isFile() && !it.isDirectory())
  if (odd !== undefined) throw new Error(`${join(odd.parentPath, odd.name)} is neither a file nor a directory`)
  const keys = entries
    .filter((it) => it.isFile())
    .map((it) => relative(dir, join(it.parentPath, it.name)).split(sep).join('/'))
    .sort()
  const apps = new Set(keys.filter(isPage).map((key) => key.slice(0, key.indexOf('/'))))
  if (apps.size === 0) throw new Error(`${dir} holds no app with an index.html`)
  if (apps.has(RELEASES.slice(0, -1))) throw new Error(`An app may not be named ${RELEASES}`)
  for (const key of keys) {
    if (!key.includes('/') || !apps.has(key.split('/')[0])) throw new Error(`${key} lies outside the directory of an app`)
    contentTypeOf(key)
  }
  return keys
}

// The pages wait under releases/ until activate, so the live index.html still loads the files of the previous release.
export async function stage(s3, { dir, version }) {
  checkVersion(version)
  const files = await filesOf(dir)
  if ((await s3.list()).includes(manifestKey(version)) && (await readManifest(s3, version)).releasedAt !== undefined) {
    throw new Error(`Release ${version} has already been live, so its files stay as they are`)
  }
  for (const key of files) await put(s3, isPage(key) ? stagedPageKey(version, key) : key, await readFile(join(dir, key)))
  await put(s3, manifestKey(version), manifestBody({ version, files }))
  return files
}

// The manifest is marked first, so a run that fails between the two writes never leaves a live page without a kept release.
export async function activate(s3, { version, now }) {
  const keys = new Set(await s3.list())
  if (!keys.has(manifestKey(version))) throw new Error(`Release ${version} has no manifest`)
  const manifest = await readManifest(s3, version)
  const pages = manifest.files.filter(isPage)
  const needed = manifest.files.map((key) => (isPage(key) ? stagedPageKey(version, key) : key))
  const missing = needed.filter((key) => !keys.has(key))
  if (missing.length > 0) throw new Error(`Release ${version} misses ${missing.join(', ')}`)
  await put(s3, manifestKey(version), manifestBody({ ...manifest, releasedAt: now.toISOString() }))
  for (const page of pages) await put(s3, page, await s3.get(stagedPageKey(version, page)))
  return pages
}

export function parseOptions(argv) {
  const { positionals, values } = parseArgs({
    args: argv,
    allowPositionals: true,
    options: { bucket: { type: 'string' }, version: { type: 'string' }, dir: { type: 'string' } },
  })
  const [command, ...rest] = positionals
  const usage =
    'Usage: node release-assets.mjs stage --bucket <bucket> --version <version> --dir <build>' +
    ' | activate --bucket <bucket> --version <version>'
  if (rest.length > 0 || !['stage', 'activate'].includes(command) || !values.bucket || !values.version) throw new Error(usage)
  if ((command === 'stage') !== (values.dir !== undefined)) throw new Error(usage)
  return { command, bucket: values.bucket, version: checkVersion(values.version), dir: values.dir }
}

if (import.meta.main) {
  const options = parseOptions(process.argv.slice(2))
  const s3 = cliS3(options.bucket)
  if (options.command === 'stage') {
    const files = await stage(s3, options)
    console.log(`Staged ${files.length} files of ${options.version}`)
  } else {
    const pages = await activate(s3, { version: options.version, now: new Date() })
    console.log(`${options.version} is live: ${pages.join(', ')}`)
  }
}
