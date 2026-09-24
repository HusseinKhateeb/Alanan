import 'fake-indexeddb/auto'
import { afterEach, beforeEach, expect, test, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import i18n from '../app/i18n'
import { App } from '../app/App'
import { setAccessToken } from '../api/client'
import { localDrafts } from '../db/masterDataDrafts'
import { saveOfflineProfile } from './offlineProfile'

vi.mock('virtual:pwa-register/react', () => ({ useRegisterSW: () => ({ needRefresh: [false] }) }))
const user = { id: 'offline-buyer', username: 'buyer', displayName: 'Buyer', preferredLanguage: 'en' as const,
  permissions: ['SALES_CREATE', 'SALES_VIEW'], roles: ['EMPLOYEE'] }
const product = { id: 'spice', name: 'Spice', tracking_type: 'WEIGHT', default_selling_price: '20.00', active: true, version: 0 }
const receipt = { id: 'sale-1', invoiceNumber: 'SAL-1', total: '20.00', paid: '20.00', outstanding: '0.00', status: 'PAID', negativeStock: false }
const response = (body: unknown, status = 200) => ({ ok: status >= 200 && status < 300, status, json: async () => body })

beforeEach(async () => {
  localStorage.clear(); setAccessToken(null); await i18n.changeLanguage('en')
  await localDrafts.saleDrafts.clear(); await localDrafts.outbox.clear(); await localDrafts.references.clear()
  saveOfflineProfile(user)
  for (const kind of ['products', 'customers', 'container-types', 'payment-methods']) {
    await localDrafts.references.put({ key: user.id + ':' + kind, userId: user.id, kind,
      records: kind === 'products' ? [product] : [], updatedAt: Date.now() })
  }
})
afterEach(() => { cleanup(); vi.unstubAllGlobals(); setAccessToken(null); localStorage.clear() })

test('offline reopen keeps the account, queues a sale, and syncs it after reconnect', async () => {
  const connection = { onLine: false }
  vi.stubGlobal('navigator', connection)
  const fetch = vi.fn(async (url: string) => {
    if (url.endsWith('/auth/refresh')) return response({ accessToken: 'online-token', expiresAt: new Date(Date.now() + 1800000).toISOString(), user })
    if (url.endsWith('/sales') && url.startsWith('/api')) return response(receipt, 201)
    if (url.startsWith('/api/')) return response({ items: [], hasMore: false })
    return response({ status: 'UP' })
  })
  vi.stubGlobal('fetch', fetch)
  render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
    <MemoryRouter initialEntries={['/sales/new']}><App /></MemoryRouter>
  </QueryClientProvider>)
  await screen.findByRole('heading', { name: 'New sale' })
  expect(screen.queryByRole('heading', { name: 'Welcome back' })).not.toBeInTheDocument()
  fireEvent.click(await screen.findByRole('button', { name: 'Spice' }))
  fireEvent.click(await screen.findByRole('button', { name: 'Queue sale' }))
  await waitFor(async () => expect((await localDrafts.outbox.toArray())[0]?.status).toBe('PENDING'))
  expect(fetch).not.toHaveBeenCalled()
  connection.onLine = true
  window.dispatchEvent(new Event('online'))
  await waitFor(async () => expect((await localDrafts.outbox.toArray())[0]?.status).toBe('SYNCED'))
  expect(fetch.mock.calls.some(([url]) => url.endsWith('/auth/refresh'))).toBe(true)
  expect(fetch.mock.calls.some(([url]) => url === '/api/sales')).toBe(true)
  expect(JSON.stringify(localStorage)).not.toContain('online-token')
})

test('connection loss at access-token renewal keeps the offline workspace available', async () => {
  let refreshCalls = 0
  vi.stubGlobal('fetch', vi.fn(async (url: string) => {
    if (url.endsWith('/auth/refresh')) {
      refreshCalls++
      if (refreshCalls === 1) return response({ accessToken: 'short-token', expiresAt: new Date(Date.now() + 1000).toISOString(), user })
      throw new TypeError('backend offline')
    }
    if (url.startsWith('/api/')) return response({ items: [], hasMore: false })
    return response({ status: 'UP' })
  }))
  render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
    <MemoryRouter><App /></MemoryRouter>
  </QueryClientProvider>)
  await screen.findByRole('heading', { name: 'Offline sales workspace' }, { timeout: 6_000 })
  expect(screen.queryByRole('heading', { name: 'Welcome back' })).not.toBeInTheDocument()
  expect(localStorage.getItem('alanan.offlineProfile')).toContain('offline-buyer')
})

test('backend outage on reopen shows the cached account instead of the login page', async () => {
  vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('backend stopped')))
  render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
    <MemoryRouter><App /></MemoryRouter>
  </QueryClientProvider>)
  await screen.findByRole('heading', { name: 'Offline sales workspace' }, { timeout: 6_000 })
  expect(screen.queryByRole('heading', { name: 'Welcome back' })).not.toBeInTheDocument()
})

test('server rejection clears the offline profile instead of allowing stale access', async () => {
  vi.stubGlobal('fetch', vi.fn(async (url: string) => url.endsWith('/auth/refresh')
    ? response({ errorCode: 'UNAUTHENTICATED' }, 401) : response({ status: 'UP' })))
  render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
    <MemoryRouter><App /></MemoryRouter>
  </QueryClientProvider>)
  await screen.findByRole('heading', { name: 'Welcome back' })
  expect(localStorage.getItem('alanan.offlineProfile')).toBeNull()
})

test('an open session enters offline sales mode when the backend stops', async () => {
  vi.stubGlobal('navigator', { onLine: true })
  let refreshCalls = 0
  vi.stubGlobal('fetch', vi.fn(async (url: string) => url.endsWith('/auth/refresh')
    ? ++refreshCalls === 1 ? response({ accessToken: 'online-token', expiresAt: new Date(Date.now() + 1800000).toISOString(), user }) : Promise.reject(new TypeError('backend stopped'))
    : url === '/actuator/health' ? response({ status: 'UP' }) : response({ items: [], hasMore: false })))
  render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
    <MemoryRouter><App /></MemoryRouter>
  </QueryClientProvider>)
  await screen.findByRole('heading', { name: 'Start an entry' })
  window.dispatchEvent(new Event('alanan:backend-unavailable'))
  await screen.findByRole('heading', { name: 'Offline sales workspace' })
  expect(screen.getByRole('link', { name: 'New sale' })).toBeInTheDocument()
  expect(screen.queryByRole('heading', { name: 'Welcome back' })).not.toBeInTheDocument()
})
