import { readFile } from 'node:fs/promises'
import { parseArgs } from 'node:util'
import { ONE_OFFS, readConfig, SERVICES } from './config.mjs'
import { cliEcs } from './ecs.mjs'
import { activate } from './release-assets.mjs'
import { waitForRollout } from './release-tasks.mjs'
import { liveRelease } from './releases.mjs'
import { cliS3 } from './s3.mjs'
import { derive, environmentOf, imageTagOf, tagsOf } from './task-definitions.mjs'

// After a completed release: each service returns to the revision it ran before, which the revision it runs names.
export async function previousTargets(ecs, config) {
  const targets = {}
  for (const name of SERVICES) {
    const current = (await ecs.service(config.services[name].service)).taskDefinition
    const previous = tagsOf(await ecs.taskDefinition(current)).previous
    if (previous === undefined) throw new Error(`${name} runs ${current}, which names no revision to return to`)
    targets[name] = previous
  }
  return targets
}

// The pages go first: an old page works against the new backend, a new page against the old one may not. The services follow,
// and the one-off tasks get the images of the target, so the CLI and migrator match the services again. A target that is
// no release, the revision of Terraform before the first one, has no pages and no images of its own to return to.
export async function rollback(ecs, s3, config, targets, { now = new Date(), log = console.log, wait = {} } = {}) {
  const app = await ecs.taskDefinition(targets.app)
  const release = tagsOf(app).release
  if (release === undefined) {
    log(`${targets.app} is no release, so the pages stay`)
  } else {
    const frontend = environmentOf(app.definition, 'app', 'APP_VERSION')
    if ((await liveRelease(s3))?.version === frontend) log(`The pages of ${frontend} are live already`)
    else log(`${frontend} is live: ${(await activate(s3, { version: frontend, now })).join(', ')}`)
  }
  const moved = []
  for (const name of SERVICES) {
    const { service } = config.services[name]
    if ((await ecs.service(service)).taskDefinition === targets[name]) continue
    await ecs.updateService(service, targets[name])
    moved.push(name)
  }
  for (const name of moved) {
    await waitForRollout(ecs, config.services[name].service, targets[name], wait)
    log(`${name} runs ${targets[name]}`)
  }
  if (release !== undefined) {
    const imageTag = imageTagOf(app.definition, 'app')
    for (const name of ONE_OFFS) {
      const { definition } = await ecs.taskDefinition(config.tasks[name].family)
      log(`${name} runs ${await ecs.register(derive(definition, { imageTag, main: name, tags: { release } }))}`)
    }
  }
  return { release, moved }
}

export function parseOptions(argv) {
  const { values } = parseArgs({ args: argv, options: { config: { type: 'string' }, state: { type: 'string' } } })
  if (!values.config) throw new Error('Usage: node rollback.mjs --config <settings> [--state <state of the failed release>]')
  return values
}

if (import.meta.main) {
  const options = parseOptions(process.argv.slice(2))
  const config = await readConfig(options.config)
  const ecs = cliEcs(config.cluster)
  const targets = options.state === undefined ? await previousTargets(ecs, config) : JSON.parse(await readFile(options.state, 'utf8')).previous
  const { release } = await rollback(ecs, cliS3(config.bucket), config, targets)
  console.log(`Rolled back to ${release ?? 'the revisions of Terraform'}`)
}
