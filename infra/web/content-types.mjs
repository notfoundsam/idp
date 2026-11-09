// nginx serves the static files locally with these types and S3 keeps them on each object, so both answer alike.
export const CONTENT_TYPES = {
  html: 'text/html; charset=utf-8',
  js: 'application/javascript; charset=utf-8',
  css: 'text/css; charset=utf-8',
  json: 'application/json; charset=utf-8',
  svg: 'image/svg+xml; charset=utf-8',
  txt: 'text/plain; charset=utf-8',
  png: 'image/png',
  ico: 'image/x-icon',
  woff2: 'font/woff2',
}

const EXTENSION = /\.([A-Za-z0-9]+)$/

export function contentTypeOf(path, types = CONTENT_TYPES) {
  const name = path.slice(path.lastIndexOf('/') + 1)
  const extension = EXTENSION.exec(name)?.[1].toLowerCase()
  const type = extension === undefined ? undefined : Object.hasOwn(types, extension) ? types[extension] : undefined
  if (type === undefined) throw new Error(`No Content-Type for ${path}`)
  return type
}
