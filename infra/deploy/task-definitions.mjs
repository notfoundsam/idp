// What register-task-definition takes back from a described revision. The rest, such as the ARN, the revision and the
// status, belongs to the revision ECS made of it.
const REGISTRATION = [
  'family',
  'taskRoleArn',
  'executionRoleArn',
  'networkMode',
  'containerDefinitions',
  'volumes',
  'placementConstraints',
  'requiresCompatibilities',
  'cpu',
  'memory',
  'pidMode',
  'ipcMode',
  'proxyConfiguration',
  'inferenceAccelerators',
  'ephemeralStorage',
  'runtimePlatform',
  'enableFaultInjection',
]

const IMAGE = /^([^@\s]+\/[^:@\s]+):([^:@/\s]+)$/

function containerOf(definition, name) {
  const container = definition.containerDefinitions.find((it) => it.name === name)
  if (container === undefined) throw new Error(`${definition.family} has no container ${name}`)
  return container
}

export function imageTagOf(definition, name) {
  const match = IMAGE.exec(containerOf(definition, name).image)
  if (match === null) throw new Error(`The image of ${name} in ${definition.family} has no tag`)
  return match[2]
}

export function environmentOf(definition, name, variable) {
  return containerOf(definition, name).environment?.find((it) => it.name === variable)?.value
}

export const tagsOf = (described) => Object.fromEntries((described.tags ?? []).map(({ key, value }) => [key, value]))

// Every container runs an image of the release, the metadata container of ops too, so each one takes the new tag. Only the
// variables named here change, and only in the main container. ECS drops a variable whose value is empty, so an empty value
// leaves the variable out and a value the described revision lacks is added.
export function derive(definition, { imageTag, main, environment = {}, tags = {} }) {
  const input = Object.fromEntries(REGISTRATION.filter((key) => definition[key] !== undefined).map((key) => [key, structuredClone(definition[key])]))
  for (const container of input.containerDefinitions) {
    const match = IMAGE.exec(container.image)
    if (match === null) throw new Error(`The image of ${container.name} in ${definition.family} has no tag`)
    container.image = `${match[1]}:${imageTag}`
  }
  const target = containerOf(input, main)
  for (const [name, value] of Object.entries(environment)) {
    const others = (target.environment ?? []).filter((it) => it.name !== name)
    target.environment = value === '' ? others : [...others, { name, value }]
  }
  input.tags = Object.entries(tags).map(([key, value]) => ({ key, value }))
  return input
}
