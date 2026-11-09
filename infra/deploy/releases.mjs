import { contentTypeOf } from '../web/content-types.mjs'

export const RELEASES = 'releases/'
const VERSION = /^[0-9A-Za-z][0-9A-Za-z._-]{0,127}$/
const MANIFEST = /^releases\/([^/]+)\/manifest\.json$/
const PAGE = /^[a-z0-9-]+\/index\.html$/

export function checkVersion(version) {
  if (typeof version !== 'string' || !VERSION.test(version)) throw new Error(`Unusable version: ${version}`)
  return version
}

export const manifestKey = (version) => `${RELEASES}${checkVersion(version)}/manifest.json`
export const stagedPageKey = (version, page) => `${RELEASES}${checkVersion(version)}/${page}`
export const isPage = (key) => PAGE.test(key)

export async function put(s3, key, body) {
  await s3.put(key, body, contentTypeOf(key))
}

export function parseManifest(version, body) {
  let manifest
  try {
    manifest = JSON.parse(body.toString('utf8'))
  } catch {
    throw new Error(`The manifest of ${version} is no JSON`)
  }
  if (manifest?.version !== version) throw new Error(`The manifest of ${version} names another version`)
  const { files, releasedAt } = manifest
  if (!Array.isArray(files) || files.length === 0 || files.some((it) => typeof it !== 'string' || it.startsWith(RELEASES))) {
    throw new Error(`The manifest of ${version} lists no usable files`)
  }
  if (releasedAt !== undefined && (typeof releasedAt !== 'string' || Number.isNaN(Date.parse(releasedAt)))) {
    throw new Error(`The manifest of ${version} has an unusable releasedAt`)
  }
  return manifest
}

export async function readManifest(s3, version) {
  return parseManifest(version, await s3.get(manifestKey(version)))
}

export async function readManifests(s3, keys) {
  const versions = keys.map((key) => MANIFEST.exec(key)?.[1]).filter((it) => it !== undefined)
  return Promise.all(versions.map((version) => readManifest(s3, version)))
}

// The release whose pages are in place: activate marks it last, a rollback included.
export async function liveRelease(s3) {
  const released = (await readManifests(s3, await s3.list())).filter((it) => it.releasedAt !== undefined)
  return released.sort((a, b) => Date.parse(b.releasedAt) - Date.parse(a.releasedAt))[0]
}

export function manifestBody(manifest) {
  return `${JSON.stringify(manifest, null, 2)}\n`
}
