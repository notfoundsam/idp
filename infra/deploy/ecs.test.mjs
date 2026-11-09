import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { test } from 'node:test'
import { cliEcs } from './ecs.mjs'

function recording(answers = {}) {
  const calls = []
  const run = async (args) => {
    const file = args.find((it) => it.startsWith('file://'))
    calls.push({ args, body: file === undefined ? undefined : JSON.parse(await readFile(file.slice('file://'.length), 'utf8')) })
    return JSON.stringify(answers[args[1]] ?? {})
  }
  return { calls, run }
}

test('the services and revisions are read through the AWS CLI in the cluster of the environment', async () => {
  const { calls, run } = recording({
    'describe-services': { services: [{ taskDefinition: 'arn:td/app:3', deployments: [{ status: 'PRIMARY' }] }], failures: [] },
    'describe-task-definition': { taskDefinition: { family: 'idp-test-app' }, tags: [{ key: 'release', value: '1.0' }] },
  })
  const ecs = cliEcs('idp-test', run)

  assert.deepEqual(await ecs.service('app'), { taskDefinition: 'arn:td/app:3', deployments: [{ status: 'PRIMARY' }] })
  assert.deepEqual(await ecs.taskDefinition('idp-test-app'), { definition: { family: 'idp-test-app' }, tags: [{ key: 'release', value: '1.0' }] })
  assert.deepEqual(
    calls.map((it) => it.args.join(' ')),
    [
      'ecs describe-services --cluster idp-test --services app --output json',
      'ecs describe-task-definition --task-definition idp-test-app --include TAGS --output json',
    ],
  )
})

test('a revision is registered from a file, a service updated to it, and a task run in its network', async () => {
  const { calls, run } = recording({
    'register-task-definition': { taskDefinition: { taskDefinitionArn: 'arn:td/app:4' } },
    'run-task': { tasks: [{ taskArn: 'arn:task/1' }], failures: [] },
  })
  const ecs = cliEcs('idp-test', run)
  const network = { subnets: ['subnet-1'], securityGroups: ['sg-1'], assignPublicIp: 'DISABLED' }

  assert.equal(await ecs.register({ family: 'idp-test-app', tags: [] }), 'arn:td/app:4')
  await ecs.updateService('app', 'arn:td/app:4')
  assert.equal(await ecs.runTask('arn:td/cli:4', network, { containerOverrides: [{ name: 'cli', command: ['cli', 'kms-check'] }] }), 'arn:task/1')

  assert.deepEqual(calls[0].body, { family: 'idp-test-app', tags: [] })
  assert.match(calls[0].args.join(' '), /^ecs register-task-definition --cli-input-json file:\/\/.+ --output json$/)
  assert.equal(calls[1].args.join(' '), 'ecs update-service --cluster idp-test --service app --task-definition arn:td/app:4 --output json')
  assert.deepEqual(calls[2].args, [
    'ecs',
    'run-task',
    '--cluster',
    'idp-test',
    '--launch-type',
    'FARGATE',
    '--task-definition',
    'arn:td/cli:4',
    '--network-configuration',
    JSON.stringify({ awsvpcConfiguration: network }),
    '--overrides',
    '{"containerOverrides":[{"name":"cli","command":["cli","kms-check"]}]}',
    '--output',
    'json',
  ])
})

test('a failure that ECS reports in its answer stops the release', async () => {
  const { run } = recording({
    'run-task': { tasks: [], failures: [{ arn: 'arn:td/cli:4', reason: 'RESOURCE:MEMORY' }] },
    'describe-services': { services: [], failures: [{ arn: 'arn:service/idp-test/app', reason: 'MISSING' }] },
    'describe-tasks': { tasks: [] },
  })
  const ecs = cliEcs('idp-test', run)

  await assert.rejects(ecs.runTask('arn:td/cli:4', {}), /ECS refused to run arn:td\/cli:4: arn:td\/cli:4 \(RESOURCE:MEMORY\)/)
  await assert.rejects(ecs.service('app'), /MISSING/)
  await assert.rejects(ecs.task('arn:task/9'), /has no task/)
})
