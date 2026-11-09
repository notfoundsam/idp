import { execFile } from 'node:child_process'
import { parseArgs, promisify } from 'node:util'
import { backendImages, infraImages, readConfig } from './config.mjs'
import { checkVersion } from './releases.mjs'
import { aws } from './s3.mjs'

const REPOSITORY = /^([0-9]{12}\.dkr\.ecr\.[a-z0-9-]+\.amazonaws\.com)\/(.+)$/

async function command(file, args) {
  const { stdout } = await promisify(execFile)(file, args, { maxBuffer: 64 * 1024 * 1024 })
  return stdout
}

export function repositoryName(url) {
  const match = REPOSITORY.exec(url)
  if (match === null) throw new Error(`${url} is no ECR repository`)
  return match[2]
}

export async function imageExists(url, tag, run = aws) {
  try {
    await run(['ecr', 'describe-images', '--repository-name', repositoryName(url), '--image-ids', `imageTag=${tag}`, '--output', 'json'])
    return true
  } catch (failure) {
    if (/ImageNotFoundException/.test(`${failure.stderr ?? ''}${failure.message}`)) return false
    throw failure
  }
}

// Tags are immutable, so an image that a rerun of the release already pushed stays, and a missing one is built from this checkout.
export async function pushImages(images, { tag, platform, exists = imageExists, docker = (args) => command('docker', args) }) {
  checkVersion(tag)
  const pushed = []
  for (const image of images) {
    const reference = `${image.repository}:${tag}`
    if (await exists(image.repository, tag)) continue
    const buildArgs = Object.entries(image.buildArgs).flatMap(([key, value]) => ['--build-arg', `${key}=${value}`])
    await docker(['build', '--platform', platform, ...buildArgs, '--file', image.dockerfile, '--tag', reference, image.context])
    await docker(['push', reference])
    pushed.push(reference)
  }
  return pushed
}

export function parseOptions(argv) {
  const { values } = parseArgs({ args: argv, options: { config: { type: 'string' }, set: { type: 'string' }, tag: { type: 'string' } } })
  const usage = 'Usage: node images.mjs --config <settings> --set backend --tag <version> | --set infra'
  if (!values.config || !['backend', 'infra'].includes(values.set) || (values.set === 'backend') !== (values.tag !== undefined)) {
    throw new Error(usage)
  }
  return values
}

if (import.meta.main) {
  const options = parseOptions(process.argv.slice(2))
  const config = await readConfig(options.config)
  const backend = options.set === 'backend'
  const images = backend ? backendImages(config) : infraImages(config)
  const pushed = await pushImages(images, { tag: backend ? options.tag : config.infra_image_tag, platform: config.platform })
  console.log(pushed.length === 0 ? 'Every image is in the registry already' : `Pushed ${pushed.join(', ')}`)
}
