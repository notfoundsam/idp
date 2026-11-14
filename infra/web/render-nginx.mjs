import { readFile, writeFile } from 'node:fs/promises'
import { CONTENT_TYPES } from './content-types.mjs'

const HEADERS = '{{security_headers}}'
const TYPES = '{{content_types}}'
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

// The block replaces mime.types, so nginx knows no type that S3 would not get. nginx adds a charset per type, not per extension.
function typeLines(contentTypes) {
  const extensions = new Map()
  const charset = new Map()
  for (const [extension, value] of Object.entries(contentTypes)) {
    const [type, parameter] = value.split('; ')
    const has = parameter !== undefined
    if (charset.has(type) && charset.get(type) !== has) throw new Error(`${type} has a charset only for some extensions`)
    charset.set(type, has)
    extensions.set(type, [...(extensions.get(type) ?? []), extension])
  }
  // nginx names the charset of text/html anyway and warns about it in charset_types.
  const charsetTypes = [...charset].filter(([type, has]) => has && type !== 'text/html').map(([type]) => type)
  return [
    'types {',
    ...[...extensions].map(([type, names]) => `    ${type} ${names.join(' ')};`),
    '}',
    ...(charsetTypes.length > 0 ? [`charset_types ${charsetTypes.join(' ')};`] : []),
  ]
}

function expand(template, placeholder, lines) {
  if (!template.includes(placeholder)) throw new Error(`The template has no ${placeholder}`)
  const rendered = template
    .split('\n')
    .flatMap((line) => {
      if (line.trim() !== placeholder) return [line]
      const indent = line.slice(0, line.indexOf(placeholder))
      return lines.map((it) => indent + it)
    })
    .join('\n')
  if (rendered.includes(placeholder)) throw new Error(`${placeholder} must stand on a line of its own`)
  return rendered
}

export function render(template, headers) {
  return expand(template, HEADERS, headerLines(headers))
}

export function renderTypes(template, contentTypes) {
  return expand(template, TYPES, typeLines(contentTypes))
}

export async function renderFiles() {
  const template = await readFile(new URL('nginx.conf.template', import.meta.url), 'utf8')
  const headers = JSON.parse(await readFile(new URL('security-headers.json', import.meta.url), 'utf8'))
  return renderTypes(render(template, headers), CONTENT_TYPES)
}

if (import.meta.main) {
  const [out] = process.argv.slice(2)
  if (!out) throw new Error('Usage: node render-nginx.mjs <output file>')
  await writeFile(out, await renderFiles())
}
