import { execFile } from 'node:child_process'
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { promisify } from 'node:util'

const DELETE_BATCH = 1000

export async function aws(args) {
  const { stdout } = await promisify(execFile)('aws', args, { maxBuffer: 256 * 1024 * 1024 })
  return stdout
}

export async function withFile(use) {
  const dir = await mkdtemp(join(tmpdir(), 'idp-s3-'))
  try {
    return await use(join(dir, 'body'))
  } finally {
    await rm(dir, { recursive: true, force: true })
  }
}

const parsed = (output) => (output.trim() === '' ? {} : JSON.parse(output))

// The AWS CLI of the runner, with the credentials of the role deploy, so the scripts need no SDK.
export function cliS3(bucket, run = aws) {
  const object = (key) => ['--bucket', bucket, '--key', key]
  return {
    async list() {
      const output = await run(['s3api', 'list-objects-v2', '--bucket', bucket, '--output', 'json'])
      return (parsed(output).Contents ?? []).map((it) => it.Key)
    },
    async get(key) {
      return withFile(async (file) => {
        await run(['s3api', 'get-object', ...object(key), file])
        return readFile(file)
      })
    },
    async put(key, body, contentType) {
      await withFile(async (file) => {
        await writeFile(file, body)
        await run(['s3api', 'put-object', ...object(key), '--content-type', contentType, '--body', file])
      })
    },
    async delete(keys) {
      for (let start = 0; start < keys.length; start += DELETE_BATCH) {
        const batch = keys.slice(start, start + DELETE_BATCH)
        const output = await withFile(async (file) => {
          await writeFile(file, JSON.stringify({ Objects: batch.map((Key) => ({ Key })), Quiet: true }))
          return run(['s3api', 'delete-objects', '--bucket', bucket, '--delete', `file://${file}`, '--output', 'json'])
        })
        const errors = parsed(output).Errors ?? []
        if (errors.length > 0) throw new Error(`S3 did not delete ${errors.map((it) => `${it.Key} (${it.Code})`).join(', ')}`)
      }
    },
  }
}
