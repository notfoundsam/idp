export function fakeS3(objects = {}) {
  const store = new Map()
  const deletes = []
  const s3 = {
    store,
    deletes,
    body: (key) => store.get(key)?.body.toString('utf8'),
    async list() {
      return [...store.keys()].sort()
    },
    async get(key) {
      if (!store.has(key)) throw new Error(`NoSuchKey: ${key}`)
      return store.get(key).body
    },
    async put(key, body, contentType) {
      store.set(key, { body: Buffer.from(body), contentType })
    },
    async delete(keys) {
      deletes.push([...keys])
      for (const key of keys) store.delete(key)
    },
  }
  for (const [key, body] of Object.entries(objects)) store.set(key, { body: Buffer.from(body), contentType: 'test/seeded' })
  return s3
}

export const DAY = 24 * 60 * 60 * 1000

export function manifest(version, files, releasedAt) {
  return JSON.stringify({ version, files, ...(releasedAt === undefined ? {} : { releasedAt: releasedAt.toISOString() }) })
}

export function release(version, files, releasedAt) {
  const objects = { [`releases/${version}/manifest.json`]: manifest(version, files, releasedAt) }
  for (const file of files) {
    if (file.endsWith('/index.html')) objects[`releases/${version}/${file}`] = `page of ${version}`
    else objects[file] = `${file} of ${version}`
  }
  return objects
}
