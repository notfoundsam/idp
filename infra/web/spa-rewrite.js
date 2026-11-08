// A CloudFront Function, runtime cloudfront-js-2.0: one script with a global handler, no modules. spa-routes.json holds its cases,
// which nginx.conf.template serves the same way.
var HOME = '/console/'
var APP = /^\/(login|console)(\/.*)?$/
var ASSET = /^\/(login|console)\/assets\//
var MAIL_LINKS = {
  '/invite': '/login/index.html',
  '/reset-password': '/login/index.html',
  '/confirm-email': '/login/index.html',
}
var CACHE_PAGES = 'no-cache'
var CACHE_ASSETS = 'public, max-age=31536000, immutable'

function answer(statusCode, statusDescription, location) {
  var response = { statusCode: statusCode, statusDescription: statusDescription, headers: {} }
  if (location) response.headers.location = { value: location }
  return response
}

function route(request) {
  var uri = request.uri
  if (uri === '/') return answer(302, 'Found', HOME)
  if (Object.prototype.hasOwnProperty.call(MAIL_LINKS, uri)) {
    request.uri = MAIL_LINKS[uri]
    return request
  }
  var app = APP.exec(uri)
  if (app === null) return answer(404, 'Not Found')
  if (app[2] === undefined) return answer(301, 'Moved Permanently', '/' + app[1] + '/')
  // A missing chunk stays a miss of the bucket, not the page, so an open tab of an old release learns that it has to reload.
  if (ASSET.test(uri)) return request
  request.uri = '/' + app[1] + '/index.html'
  return request
}

// Only a found file gets a lifetime, so a missing chunk is asked for again.
function cache(request, response) {
  if (response.statusCode === 200 || response.statusCode === 304) {
    response.headers['cache-control'] = { value: ASSET.test(request.uri) ? CACHE_ASSETS : CACHE_PAGES }
  } else {
    delete response.headers['cache-control']
  }
  return response
}

function handler(event) {
  if (event.context.eventType === 'viewer-response') return cache(event.request, event.response)
  return route(event.request)
}
