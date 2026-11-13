import assert from 'node:assert/strict'
import { test } from 'node:test'
import { definitionOf } from './fake-ecs.mjs'
import { derive, environmentOf, imageTagOf, tagsOf } from './task-definitions.mjs'

const described = (definition) => ({
  ...definition,
  taskDefinitionArn: 'arn:aws:ecs:eu-central-1:111111111111:task-definition/idp-test-ops:7',
  revision: 7,
  status: 'ACTIVE',
  requiresAttributes: [{ name: 'ecs.capability.secrets.asm.environment-variables' }],
  compatibilities: ['EC2', 'FARGATE'],
  registeredAt: '2026-11-09T00:00:00Z',
  registeredBy: 'arn:aws:sts::111111111111:assumed-role/idp-test-github-terraform/x',
})

test('a derived revision keeps the roles, the secrets and the size, and every container takes the new tag', () => {
  const input = derive(described(definitionOf('ops', '1.0')), { imageTag: '2026.11.9.4', main: 'ops', tags: { release: '2026.11.9.4' } })

  assert.deepEqual(Object.keys(input).sort(), [
    'containerDefinitions',
    'cpu',
    'executionRoleArn',
    'family',
    'memory',
    'networkMode',
    'requiresCompatibilities',
    'runtimePlatform',
    'tags',
    'taskRoleArn',
    'volumes',
  ])
  assert.equal(input.taskRoleArn, 'arn:aws:iam::111111111111:role/idp-test-ops-task')
  assert.deepEqual(
    input.containerDefinitions.map((it) => it.image),
    ['idp-metadata', 'ops'].map(() => '111111111111.dkr.ecr.eu-central-1.amazonaws.com/idp-test/ops:2026.11.9.4'),
  )
  assert.deepEqual(input.containerDefinitions[1].secrets, definitionOf('ops', '1.0').containerDefinitions[1].secrets)
  assert.deepEqual(input.tags, [{ key: 'release', value: '2026.11.9.4' }])
})

test('only the named variables of the main container change, and the described revision stays as it was', () => {
  const definition = described(definitionOf('app', '1.0', { APP_VERSION: '1.0', MIN_CLIENT_VERSION: '', DB_USERNAME: 'app_runtime' }))

  const input = derive(definition, { imageTag: '2.0', main: 'app', environment: { APP_VERSION: '1.5', MIN_CLIENT_VERSION: '1.2' } })

  assert.equal(environmentOf(input, 'app', 'APP_VERSION'), '1.5')
  assert.equal(environmentOf(input, 'app', 'MIN_CLIENT_VERSION'), '1.2')
  assert.equal(environmentOf(input, 'app', 'DB_USERNAME'), 'app_runtime')
  assert.equal(environmentOf(definition, 'app', 'APP_VERSION'), '1.0')
  assert.equal(imageTagOf(definition, 'app'), '1.0')
  assert.equal(imageTagOf(input, 'app'), '2.0')
})

test('a variable that ECS dropped as empty is added again, and an empty value leaves the variable out as ECS would', () => {
  const definition = described(definitionOf('app', '1.0', { APP_VERSION: '1.0', DB_USERNAME: 'app_runtime' }))

  const raised = derive(definition, { imageTag: '2.0', main: 'app', environment: { MIN_CLIENT_VERSION: '1.2' } })
  assert.equal(environmentOf(raised, 'app', 'MIN_CLIENT_VERSION'), '1.2')
  assert.equal(environmentOf(raised, 'app', 'DB_USERNAME'), 'app_runtime')

  const lowered = derive(described(raised), { imageTag: '3.0', main: 'app', environment: { MIN_CLIENT_VERSION: '' } })
  assert.deepEqual(
    lowered.containerDefinitions[0].environment.map((it) => it.name),
    ['APP_VERSION', 'DB_USERNAME'],
  )
})

test('a missing container and an image without a tag are refused', () => {
  const definition = described(definitionOf('worker', '1.0'))

  assert.throws(() => derive(definition, { imageTag: '2.0', main: 'app' }), /has no container app/)
  definition.containerDefinitions[0].image = '111111111111.dkr.ecr.eu-central-1.amazonaws.com/idp-test/worker@sha256:abc'
  assert.throws(() => derive(definition, { imageTag: '2.0', main: 'worker' }), /has no tag/)
  assert.throws(() => imageTagOf(definition, 'worker'), /has no tag/)
})

test('tags read as a map', () => {
  assert.deepEqual(tagsOf({ tags: [{ key: 'release', value: '1.1' }, { key: 'previous', value: 'arn' }] }), { release: '1.1', previous: 'arn' })
  assert.deepEqual(tagsOf({}), {})
})
