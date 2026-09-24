import assert from 'node:assert/strict'
import { after, test } from 'node:test'
import http from 'node:http'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { createAppServer } from './serve-laptop-app.mjs'

const staticDir = path.resolve(fileURLToPath(new URL('../../frontend/dist', import.meta.url)))
const backend = http.createServer((request, response) => {
  response.setHeader('Set-Cookie', 'alanan_refresh=test; HttpOnly; Secure; SameSite=Strict; Path=/api/auth')
  response.writeHead(200, { 'Content-Type': 'application/json' })
  response.end(JSON.stringify({ path: request.url, token: request.headers.authorization }))
})
await new Promise(resolve => backend.listen(0, '127.0.0.1', resolve))
const frontend = createAppServer({ staticDir, backendPort: backend.address().port })
await new Promise(resolve => frontend.listen(0, '127.0.0.1', resolve))
const base = `http://127.0.0.1:${frontend.address().port}`
after(() => { frontend.close(); backend.close() })

test('serves the built app and nested routes from the same origin', async () => {
  const home = await fetch(base)
  const route = await fetch(base + '/sales/new')
  assert.equal(home.status, 200)
  assert.equal(route.status, 200)
  assert.equal(await route.text(), await home.text())
})

test('streams API requests and response cookies without serving the SPA', async () => {
  const response = await fetch(base + '/api/auth/refresh', { method: 'POST', headers: { Authorization: 'Bearer test' } })
  assert.equal(response.status, 200)
  assert.match(response.headers.get('set-cookie'), /HttpOnly; Secure; SameSite=Strict/)
  assert.deepEqual(await response.json(), { path: '/api/auth/refresh', token: 'Bearer test' })
})

test('does not expose dotfiles or turn missing assets into HTML', async () => {
  assert.equal((await fetch(base + '/.htaccess')).status, 404)
  assert.equal((await fetch(base + '/assets/missing.js')).status, 404)
})
