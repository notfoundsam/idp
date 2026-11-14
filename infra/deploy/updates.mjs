import { readFile } from 'node:fs/promises'
import { parseArgs } from 'node:util'

const DAY = 86_400_000

// The update time frames of docs/security/dependencies.md: a Renovate pull request is merged, or closed with a reason, by then.
export const UPDATE_DEADLINES = { major: 90, other: 30 }

// The open pull requests of `gh pr list --label dependencies --json number,title,createdAt,labels`.
export function overdueUpdates(pulls, now) {
  return pulls
    .map((pull) => {
      const kind = pull.labels?.some((it) => it.name === 'major') ? 'major' : 'other'
      const created = Date.parse(pull.createdAt ?? '')
      return { pull, kind, due: Number.isNaN(created) ? -Infinity : created + UPDATE_DEADLINES[kind] * DAY }
    })
    .filter((it) => it.due <= now)
    .map(({ pull, kind, due }) => {
      const date = Number.isFinite(due) ? new Date(due).toISOString().slice(0, 10) : 'unknown'
      return `#${pull.number} ${pull.title} (${kind === 'major' ? 'major' : 'minor or patch'}), due ${date}`
    })
}

export function parseOptions(argv) {
  const { values } = parseArgs({ args: argv, options: { pulls: { type: 'string' } } })
  if (!values.pulls) throw new Error('Usage: node updates.mjs --pulls <gh pr list json>')
  return { pulls: values.pulls }
}

if (import.meta.main) {
  const options = parseOptions(process.argv.slice(2))
  const overdue = overdueUpdates(JSON.parse(await readFile(options.pulls, 'utf8')), Date.now())
  for (const line of overdue) console.error(process.env.GITHUB_ACTIONS === 'true' ? `::error title=Update past its time frame::${line}` : `FAIL ${line}`)
  if (overdue.length > 0) process.exitCode = 1
  else console.log('No dependency update is past its time frame')
}
