import assert from 'node:assert/strict'
import { test } from 'node:test'
import { backendImages, infraImages } from './config.mjs'
import { config } from './fake-ecs.mjs'
import { imageExists, parseOptions, pushImages, repositoryName } from './images.mjs'

const REGISTRY = '111111111111.dkr.ecr.eu-central-1.amazonaws.com'

test('each backend image is built from its jar for the platform of ECS and pushed under the release', async () => {
  const calls = []

  const pushed = await pushImages(backendImages(config), {
    tag: '2026.11.9.8',
    platform: 'linux/arm64',
    exists: async () => false,
    docker: async (args) => calls.push(args.join(' ')),
  })

  assert.deepEqual(pushed, ['app', 'worker', 'ops', 'migrator'].map((it) => `${REGISTRY}/idp-test/${it}:2026.11.9.8`))
  assert.deepEqual(calls.slice(0, 2), [
    `build --platform linux/arm64 --build-arg MODULE=app --file backend/Dockerfile --tag ${REGISTRY}/idp-test/app:2026.11.9.8 backend`,
    `push ${REGISTRY}/idp-test/app:2026.11.9.8`,
  ])
})

test('an image the registry holds already stays, so a rerun pushes nothing twice', async () => {
  const calls = []
  const held = new Set([`${REGISTRY}/idp-test/pgbouncer`])

  const pushed = await pushImages(infraImages(config), {
    tag: config.infra_image_tag,
    platform: 'linux/arm64',
    exists: async (repository) => held.has(repository),
    docker: async (args) => calls.push(args.join(' ')),
  })

  assert.deepEqual(pushed, [`${REGISTRY}/idp-test/db-bootstrap:0.1.0`, `${REGISTRY}/idp-test/otel-collector:0.1.0`])
  assert.equal(calls[0], `build --platform linux/arm64 --file infra/db-bootstrap/Dockerfile --tag ${REGISTRY}/idp-test/db-bootstrap:0.1.0 infra/db-bootstrap`)
})

test('a tag that ECR or Docker would not take is refused before anything is built', async () => {
  await assert.rejects(pushImages(backendImages(config), { tag: 'latest; rm', platform: 'linux/arm64', exists: async () => false }), /Unusable/)
})

test('ECR answers whether it holds a tag, and any other failure stops the release', async () => {
  const calls = []
  const found = async (args) => calls.push(args.join(' '))
  const missing = async () => {
    throw Object.assign(new Error('Command failed'), { stderr: 'An error occurred (ImageNotFoundException) when calling DescribeImages' })
  }
  const denied = async () => {
    throw Object.assign(new Error('Command failed'), { stderr: 'An error occurred (AccessDeniedException)' })
  }

  assert.equal(await imageExists(`${REGISTRY}/idp-test/app`, '1.0', found), true)
  assert.deepEqual(calls, ['ecr describe-images --repository-name idp-test/app --image-ids imageTag=1.0 --output json'])
  assert.equal(await imageExists(`${REGISTRY}/idp-test/app`, '1.0', missing), false)
  await assert.rejects(imageExists(`${REGISTRY}/idp-test/app`, '1.0', denied), /Command failed/)
  assert.throws(() => repositoryName('docker.io/library/nginx'), /no ECR repository/)
})

test('options name the set, and only the backend takes a tag', () => {
  assert.equal(parseOptions(['--config', 'c', '--set', 'backend', '--tag', '1.0']).tag, '1.0')
  assert.equal(parseOptions(['--config', 'c', '--set', 'infra']).set, 'infra')
  assert.throws(() => parseOptions(['--config', 'c', '--set', 'infra', '--tag', '1.0']), /Usage/)
  assert.throws(() => parseOptions(['--config', 'c', '--set', 'backend']), /Usage/)
  assert.throws(() => parseOptions(['--config', 'c', '--set', 'frontend']), /Usage/)
})
