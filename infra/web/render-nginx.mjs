import { readFile, writeFile } from 'node:fs/promises'

const PLACEHOLDER = '{{security_headers}}'
const NAME = /^[A-Za-z][A-Za-z0-9-]*$/
// nginx would read a quote or a backslash as syntax and a dollar sign as a variable.
const VALUE = /^[\x20-\x7e]+$/
const UNSAFE = /["\\$]/

function headerLines(headers) {
  const entries = Object.entries(headers)
  if (entries.length === 0) throw new Error('No security headers')
  return entries.map(([name, value]) => {
    if (!NAME.test(name)) throw new Error(`Unusable header name: ${name}`)
    if (typeof value !== 'string' || !VALUE.test(value) || UNSAFE.test(value)) throw new Error(`Unusable value of ${name}`)
    return `add_header ${name} "${value}" always;`
  })
}

export function render(template, headers) {
  const lines = headerLines(headers)
  if (!template.includes(PLACEHOLDER)) throw new Error(`The template has no ${PLACEHOLDER}`)
  const rendered = template
    .split('\n')
    .flatMap((line) => {
      if (line.trim() !== PLACEHOLDER) return [line]
      const indent = line.slice(0, line.indexOf(PLACEHOLDER))
      return lines.map((header) => indent + header)
    })
    .join('\n')
  if (rendered.includes(PLACEHOLDER)) throw new Error(`${PLACEHOLDER} must stand on a line of its own`)
  return rendered
}

export async function renderFiles() {
  const template = await readFile(new URL('nginx.conf.template', import.meta.url), 'utf8')
  const headers = JSON.parse(await readFile(new URL('security-headers.json', import.meta.url), 'utf8'))
  return render(template, headers)
}

if (import.meta.main) {
  const [out] = process.argv.slice(2)
  if (!out) throw new Error('Usage: node render-nginx.mjs <output file>')
  await writeFile(out, await renderFiles())
}
