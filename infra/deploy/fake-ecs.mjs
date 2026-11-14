const ACCOUNT = 'arn:aws:ecs:eu-central-1:111111111111'
const REGISTRY = '111111111111.dkr.ecr.eu-central-1.amazonaws.com/idp-test'

export const config = {
  region: 'eu-central-1',
  cluster: 'idp-test',
  bucket: 'idp-test-static-111111111111',
  platform: 'linux/arm64',
  infra_image_tag: '0.1.0',
  repositories: Object.fromEntries(
    ['app', 'worker', 'ops', 'migrator', 'pgbouncer', 'db-bootstrap', 'otel-collector'].map((name) => [name, `${REGISTRY}/${name}`]),
  ),
  services: Object.fromEntries(['app', 'worker', 'ops'].map((name) => [name, { service: name, family: `idp-test-${name}` }])),
  tasks: Object.fromEntries(
    ['migrator', 'cli', 'role-passwords'].map((name) => [
      name,
      { family: `idp-test-${name}`, network: { subnets: ['subnet-1'], securityGroups: [`sg-${name}`], assignPublicIp: 'DISABLED' } },
    ]),
  ),
}

const IMAGES = { app: 'app', worker: 'worker', ops: 'ops', migrator: 'migrator', cli: 'ops', 'role-passwords': 'db-bootstrap' }

// A revision as Terraform registers it: every container on the image of var.release.
export function definitionOf(name, tag, environment = {}) {
  const container = (containerName, image) => ({
    name: containerName,
    image: `${REGISTRY}/${image}:${tag}`,
    environment: Object.entries(environment).map(([key, value]) => ({ name: key, value })),
    secrets: [{ name: 'DB_PASSWORD', valueFrom: 'arn:aws:secretsmanager:eu-central-1:111111111111:secret:idp-test/db/x' }],
  })
  return {
    family: `idp-test-${name}`,
    taskRoleArn: `arn:aws:iam::111111111111:role/idp-test-${name}-task`,
    executionRoleArn: `arn:aws:iam::111111111111:role/idp-test-${name}-execution`,
    networkMode: 'awsvpc',
    requiresCompatibilities: ['FARGATE'],
    cpu: '512',
    memory: '1024',
    runtimePlatform: { operatingSystemFamily: 'LINUX', cpuArchitecture: 'ARM64' },
    volumes: [{ name: 'run' }],
    containerDefinitions: [
      ...(name === 'ops' ? [{ ...container('idp-metadata', 'ops'), essential: false }] : []),
      container(name === 'role-passwords' ? 'db-bootstrap' : name, IMAGES[name]),
    ],
  }
}

// ECS in memory. Every call that changes something lands in log, which a test shares with the fake of S3 to check the order.
export function fakeEcs({ log = [], failing = new Set(), exits = {} } = {}) {
  const revisions = new Map()
  const latest = new Map()
  const services = new Map()
  const tasks = new Map()
  const ecs = {
    log,
    revisions,
    services,
    seed(input, tags = {}) {
      return ecs.registerNow({ ...input, tags: Object.entries(tags).map(([key, value]) => ({ key, value })) })
    },
    registerNow(input) {
      const { tags = [], ...definition } = structuredClone(input)
      for (const container of definition.containerDefinitions) {
        if (container.environment) container.environment = container.environment.filter((it) => it.value !== '')
      }
      const revision = (latest.get(definition.family) ?? 0) + 1
      const taskDefinitionArn = `${ACCOUNT}:task-definition/${definition.family}:${revision}`
      revisions.set(taskDefinitionArn, {
        definition: { ...definition, taskDefinitionArn, revision, status: 'ACTIVE', registeredAt: '2026-11-09T00:00:00Z' },
        tags,
      })
      latest.set(definition.family, revision)
      return taskDefinitionArn
    },
    run(name, arn) {
      services.set(name, { taskDefinition: arn, deployments: [{ status: 'PRIMARY', taskDefinition: arn, rolloutState: 'COMPLETED' }] })
    },
    async service(name) {
      if (!services.has(name)) throw new Error(`ServiceNotFoundException: ${name}`)
      return structuredClone(services.get(name))
    },
    async taskDefinition(reference) {
      const arn = /:[0-9]+$/.test(reference) ? reference : `${ACCOUNT}:task-definition/${reference}:${latest.get(reference)}`
      if (!revisions.has(arn)) throw new Error(`ClientException: Unable to describe task definition ${reference}`)
      return structuredClone(revisions.get(arn))
    },
    async register(input) {
      const arn = ecs.registerNow(input)
      log.push(['register', arn])
      return arn
    },
    async updateService(name, taskDefinition) {
      log.push(['update', name, taskDefinition])
      const before = services.get(name).taskDefinition
      if (failing.has(taskDefinition)) {
        services.set(name, {
          taskDefinition: before,
          deployments: [
            { status: 'PRIMARY', taskDefinition: before, rolloutState: 'COMPLETED' },
            { status: 'ACTIVE', taskDefinition, rolloutState: 'FAILED', rolloutStateReason: 'tasks failed to start' },
          ],
        })
      } else {
        ecs.run(name, taskDefinition)
      }
    },
    async runTask(taskDefinition, network, overrides) {
      const taskArn = `${ACCOUNT}:task/idp-test/${tasks.size + 1}`
      log.push(['run', taskDefinition, overrides?.containerOverrides?.[0]?.command?.join(' ')])
      const { definition } = await ecs.taskDefinition(taskDefinition)
      const command = overrides?.containerOverrides?.[0]?.command?.join(' ')
      const exitCode = exits[command] ?? exits[definition.family] ?? 0
      tasks.set(taskArn, {
        taskArn,
        network,
        lastStatus: 'STOPPED',
        stoppedReason: 'Essential container in task exited',
        containers: definition.containerDefinitions.map((it) => ({ name: it.name, exitCode })),
      })
      return taskArn
    },
    async task(arn) {
      return structuredClone(tasks.get(arn))
    },
  }
  return ecs
}

// An environment that Terraform applied with release 1.0 and that no release reached yet.
export function applied(options) {
  const ecs = fakeEcs(options)
  for (const name of ['app', 'worker', 'ops']) {
    const environment = name === 'app' ? { APP_VERSION: '1.0', MIN_CLIENT_VERSION: '' } : {}
    ecs.run(name, ecs.seed(definitionOf(name, '1.0', environment)))
  }
  for (const name of ['migrator', 'cli', 'role-passwords']) ecs.seed(definitionOf(name, name === 'role-passwords' ? '0.1.0' : '1.0'))
  return ecs
}
