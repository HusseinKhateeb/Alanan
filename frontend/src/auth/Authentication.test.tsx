import { afterEach, beforeEach, expect, test, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import i18n from '../app/i18n'
import { App } from '../app/App'
import { setAccessToken } from '../api/client'

vi.mock('virtual:pwa-register/react', () => ({ useRegisterSW: () => ({ needRefresh: [false] }) }))
beforeEach(async () => { setAccessToken(null); localStorage.clear(); await i18n.changeLanguage('en') })
afterEach(() => { cleanup(); vi.unstubAllGlobals() })

const user = { id: 'user-1', username: 'owner', displayName: 'Owner', preferredLanguage: 'en', permissions: ['USERS_MANAGE'] }
function response(body: unknown, status = 200) { return { ok: status >= 200 && status < 300, status, json: async () => body } }
function mount(route = '/') {
  render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
    <MemoryRouter initialEntries={[route]}><App /></MemoryRouter>
  </QueryClientProvider>)
}
function submit() {
  fireEvent.change(screen.getByLabelText('Username'), { target: { value: 'owner' } })
  fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'entered-password' } })
  fireEvent.click(screen.getByRole('button', { name: 'Sign in' }))
}
function authenticatedFetch(preferredLanguage = 'en', permissions = ['USERS_MANAGE']) {
  return vi.fn(async (url: string) => {
    if (url.endsWith('/auth/login')) return response({ accessToken: 'memory-only-token', expiresAt: new Date(Date.now() + 1800000).toISOString(), user: { ...user, preferredLanguage, permissions } })
    if (url === '/actuator/health') return response({ status: 'UP' })
    if (url.endsWith('/auth/logout') || url.endsWith('/auth/language')) return response(null, 204)
    return response([])
  })
}

test('login switches language and RTL, without persisting the token', async () => {
  vi.stubGlobal('fetch', authenticatedFetch('ar'))
  mount(); submit()
  await screen.findByRole('heading', { name: 'بداية واضحة لكل يوم.' })
  expect(document.documentElement.dir).toBe('rtl')
  expect(JSON.stringify(localStorage)).not.toContain('memory-only-token')
  expect(JSON.stringify(sessionStorage)).not.toContain('memory-only-token')
  fireEvent.click(screen.getByRole('button', { name: 'English' }))
  await waitFor(() => expect(document.documentElement.dir).toBe('ltr'))
  await screen.findByText('Backend available')
})

test('network failures preserve entered credentials for retry', async () => {
  vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('offline')))
  mount(); submit()
  expect(await screen.findByRole('alert')).toHaveTextContent('server could not be reached')
  expect(screen.getByLabelText('Username')).toHaveValue('owner')
  expect(screen.getByLabelText('Password')).toHaveValue('entered-password')
})

test('invalid credentials are translated and do not open the workspace', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response({ errorCode: 'INVALID_CREDENTIALS' }, 401)))
  mount(); submit()
  expect(await screen.findByRole('alert')).toHaveTextContent('username or password is incorrect')
  expect(screen.queryByRole('heading', { name: 'A clear start to every day.' })).not.toBeInTheDocument()
})

test('logout calls the server with the token, then closes the workspace', async () => {
  const fetch = authenticatedFetch()
  vi.stubGlobal('fetch', fetch)
  mount(); submit()
  fireEvent.click(await screen.findByRole('button', { name: 'Sign out of all sessions' }))
  await screen.findByRole('heading', { name: 'Welcome back' })
  const call = (fetch.mock.calls as unknown as [string, RequestInit][]).find(([url]) => url.endsWith('/auth/logout'))
  expect(new Headers(call?.[1].headers).get('Authorization')).toBe('Bearer memory-only-token')
})

test('employee cannot open the user directory through direct navigation', async () => {
  const fetch = authenticatedFetch('en', ['SALES_CREATE', 'SALES_VIEW'])
  vi.stubGlobal('fetch', fetch)
  mount('/users'); submit()
  expect(await screen.findByText(/do not have permission/)).toHaveAttribute('role', 'alert')
  expect(fetch.mock.calls.some(([url]) => url.includes('/users'))).toBe(false)
})

test('server token rejection requests reauthentication', async () => {
  const base = authenticatedFetch()
  vi.stubGlobal('fetch', vi.fn(async (url: string) => url.includes('/users')
    ? response({ errorCode: 'UNAUTHENTICATED' }, 401) : base(url)))
  mount('/users'); submit()
  expect(await screen.findByText('Your session has expired. Sign in again to continue.')).toBeInTheDocument()
  expect(screen.getByRole('heading', { name: 'Welcome back' })).toBeInTheDocument()
})
