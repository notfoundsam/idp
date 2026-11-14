import { readFile } from 'node:fs/promises'

export const SERVICES = ['app', 'worker', 'ops']
export const ONE_OFFS = ['migrator', 'cli']
const TASKS = [...ONE_OFFS, 'role-passwords']
const BACKEND_IMAGES = ['app', 'worker', 'ops', 'migrator']
const INFRA_IMAGES = ['pgbouncer', 'db-bootstrap', 'otel-collector']

const text = (value) => typeof value === 'string' && value !== ''

// The SSM parameter /<name>/release of modules/release, as the workflow saved it.
export function parseConfig(body) {
  let config
  try {
    config = JSON.parse(body)
  } catch {
    throw new Error('The settings of the release are no JSON')
  }
  const missing = [
    ...['region', 'cluster', 'bucket', 'platform', 'infra_image_tag'].filter((key) => !text(config?.[key])),
    ...[...BACKEND_IMAGES, ...INFRA_IMAGES].filter((name) => !text(config?.repositories?.[name])).map((name) => `repositories.${name}`),
    ...SERVICES.filter((name) => !text(config?.services?.[name]?.service) || !text(config.services[name].family)).map((name) => `services.${name}`),
    ...TASKS.filter((name) => !text(config?.tasks?.[name]?.family) || !Array.isArray(config.tasks[name].network?.subnets)).map(
      (name) => `tasks.${name}`,
    ),
  ]
  if (missing.length > 0) throw new Error(`The settings of the release miss ${missing.join(', ')}`)
  return config
}

export async function readConfig(path) {
  return parseConfig(await readFile(path, 'utf8'))
}

export const backendImages = (config) => BACKEND_IMAGES.map((name) => ({ name, repository: config.repositories[name], context: 'backend', dockerfile: 'backend/Dockerfile', buildArgs: { MODULE: name } }))

export const infraImages = (config) =>
  INFRA_IMAGES.map((name) => ({ name, repository: config.repositories[name], context: `infra/${name}`, dockerfile: `infra/${name}/Dockerfile`, buildArgs: {} }))
