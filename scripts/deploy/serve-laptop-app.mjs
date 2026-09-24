import http from 'node:http'
import { createReadStream } from 'node:fs'
import { stat } from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

const types = {
  '.css': 'text/css; charset=utf-8', '.html': 'text/html; charset=utf-8',
  '.ico': 'image/x-icon', '.js': 'text/javascript; charset=utf-8',
  '.json': 'application/json; charset=utf-8', '.png': 'image/png',
  '.svg': 'image/svg+xml', '.webmanifest': 'application/manifest+json',
  '.woff2': 'font/woff2',
}

export function createAppServer({ staticDir, backendPort }) {
  const root = path.resolve(staticDir)
  return http.createServer(async (request, response) => {
    let pathname
    try { pathname = new URL(request.url, 'http://localhost').pathname; pathname = decodeURIComponent(pathname) }
    catch { response.writeHead(400).end(); return }
    if (pathname === '/api' || pathname.startsWith('/api/') || pathname === '/actuator' || pathname.startsWith('/actuator/')) {
      const headers = { ...request.headers, host: `127.0.0.1:${backendPort}` }
      const upstream = http.request({ hostname: '127.0.0.1', port: backendPort, method: request.method,
        path: request.url, headers }, reply => {
        response.writeHead(reply.statusCode ?? 502, reply.headers)
        reply.pipe(response)
      })
      upstream.on('error', () => { if (!response.headersSent) response.writeHead(502); response.end() })
      request.pipe(upstream)
      return
    }
    if (!['GET', 'HEAD'].includes(request.method)) { response.writeHead(405).end(); return }
    if (pathname.split('/').some(segment => segment.startsWith('.'))) { response.writeHead(404).end(); return }
    const candidate = path.resolve(root, '.' + pathname)
    if (candidate !== root && !candidate.startsWith(root + path.sep)) { response.writeHead(404).end(); return }
    let file = candidate === root ? path.join(root, 'index.html') : candidate
    let info = await stat(file).catch(() => null)
    if (!info?.isFile()) {
      if (path.extname(pathname)) { response.writeHead(404).end(); return }
      file = path.join(root, 'index.html')
      info = await stat(file).catch(() => null)
    }
    if (!info?.isFile()) { response.writeHead(404).end(); return }
    response.writeHead(200, {
      'Content-Type': types[path.extname(file)] ?? 'application/octet-stream',
      'Content-Length': info.size,
      'Cache-Control': pathname.startsWith('/assets/') ? 'public, max-age=31536000, immutable' : 'no-store',
      'X-Content-Type-Options': 'nosniff',
    })
    if (request.method === 'HEAD') response.end()
    else createReadStream(file).on('error', () => response.destroy()).pipe(response)
  })
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  const root = path.resolve(process.env.ALANAN_STATIC_DIR ?? fileURLToPath(new URL('../../frontend/dist', import.meta.url)))
  const webPort = Number(process.env.ALANAN_WEB_PORT ?? 8090)
  const backendPort = Number(process.env.ALANAN_BACKEND_PORT ?? 8081)
  if (![webPort, backendPort].every(port => Number.isInteger(port) && port > 0 && port < 65536))
    throw new Error('Invalid web or backend port')
  createAppServer({ staticDir: root, backendPort }).listen(webPort, '127.0.0.1', () => {
    process.stdout.write(`Alanan local web server: http://127.0.0.1:${webPort}\n`)
  })
}
