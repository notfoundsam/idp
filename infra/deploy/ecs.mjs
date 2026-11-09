import { writeFile } from 'node:fs/promises'
import { aws, withFile } from './s3.mjs'

const parsed = (output) => (output.trim() === '' ? {} : JSON.parse(output))

function failuresOf(output, what) {
  const failures = output.failures ?? []
  if (failures.length > 0) throw new Error(`ECS refused ${what}: ${failures.map((it) => `${it.arn} (${it.reason})`).join(', ')}`)
}

// The AWS CLI of the runner, with the credentials of the role deploy, as cliS3.
export function cliEcs(cluster, run = aws) {
  const call = async (args) => parsed(await run([...args, '--output', 'json']))
  return {
    async service(name) {
      const output = await call(['ecs', 'describe-services', '--cluster', cluster, '--services', name])
      failuresOf(output, `to describe ${name}`)
      const [service] = output.services ?? []
      if (service === undefined) throw new Error(`The cluster ${cluster} has no service ${name}`)
      return { taskDefinition: service.taskDefinition, deployments: service.deployments ?? [] }
    },
    async taskDefinition(reference) {
      const output = await call(['ecs', 'describe-task-definition', '--task-definition', reference, '--include', 'TAGS'])
      return { definition: output.taskDefinition, tags: output.tags ?? [] }
    },
    async register(input) {
      const output = await withFile(async (file) => {
        await writeFile(file, JSON.stringify(input))
        return call(['ecs', 'register-task-definition', '--cli-input-json', `file://${file}`])
      })
      return output.taskDefinition.taskDefinitionArn
    },
    async updateService(name, taskDefinition) {
      await call(['ecs', 'update-service', '--cluster', cluster, '--service', name, '--task-definition', taskDefinition])
    },
    async runTask(taskDefinition, network, overrides) {
      const output = await call([
        'ecs',
        'run-task',
        '--cluster',
        cluster,
        '--launch-type',
        'FARGATE',
        '--task-definition',
        taskDefinition,
        '--network-configuration',
        JSON.stringify({ awsvpcConfiguration: network }),
        ...(overrides === undefined ? [] : ['--overrides', JSON.stringify(overrides)]),
      ])
      failuresOf(output, `to run ${taskDefinition}`)
      const [task] = output.tasks ?? []
      if (task === undefined) throw new Error(`ECS started no task of ${taskDefinition}`)
      return task.taskArn
    },
    async task(arn) {
      const output = await call(['ecs', 'describe-tasks', '--cluster', cluster, '--tasks', arn])
      failuresOf(output, `to describe ${arn}`)
      const [task] = output.tasks ?? []
      if (task === undefined) throw new Error(`The cluster ${cluster} has no task ${arn}`)
      return task
    },
  }
}
