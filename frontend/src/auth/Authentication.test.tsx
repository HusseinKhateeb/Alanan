import { afterEach, beforeEach, expect, test, vi } from 'vitest'
import { StrictMode } from 'react'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import i18n from '../app/i18n'
import { App } from '../app/App'
import { setAccessToken } from '../api/client'

vi.mock('virtual:pwa-register/react', () => ({ useRegisterSW: () => ({ needRefresh: [false] }) }))
beforeEach(async () => { setAccessToken(null); localStorage.clear(); await i18n.changeLanguage('en') })
afterEach(() => { cleanup(); vi.unstubAllGlobals() })

const user = { id: 'user-1', username: 'owner', displayName: 'Owner', preferredLanguage: 'en', permissions: ['USERS_MANAGE'], roles: ['ADMIN'] }
function response(body: unknown, status = 200) { return { ok: status >= 200 && status < 300, status, json: async () => body } }
function mount(route = '/', strict = false) {
  const app = <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
    <MemoryRouter initialEntries={[route]}><App /></MemoryRouter>
  </QueryClientProvider>
  render(strict ? <StrictMode>{app}</StrictMode> : app)
}
async function submit() {
  await screen.findByRole('heading', { name: 'Welcome back' }, { timeout: 5_000 })
  fireEvent.change(screen.getByLabelText('Username'), { target: { value: 'owner' } })
  fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'entered-password' } })
  fireEvent.click(screen.getByRole('button', { name: 'Sign in' }))
}
function authenticatedFetch(preferredLanguage = 'en', permissions = ['USERS_MANAGE']) {
  return vi.fn(async (url: string) => {
    if (url.endsWith('/auth/login')) return response({ accessToken: 'memory-only-token', expiresAt: new Date(Date.now() + 1800000).toISOString(), user: { ...user, preferredLanguage, permissions } })
    if (url === '/actuator/health') return response({ status: 'UP' })
    if (url.endsWith('/auth/logout-current') || url.endsWith('/auth/language')) return response(null, 204)
    return response([])
  })
}

test('login switches language and RTL, without persisting the token', async () => {
  vi.stubGlobal('fetch', authenticatedFetch('ar'))
  mount(); await submit()
  await screen.findByRole('heading', { name: 'بداية واضحة لكل يوم.' })
  expect(document.documentElement.dir).toBe('rtl')
  expect(JSON.stringify(localStorage)).not.toContain('memory-only-token')
  expect(JSON.stringify(sessionStorage)).not.toContain('memory-only-token')
  fireEvent.click(screen.getByRole('button', { name: 'English' }))
  await waitFor(() => expect(document.documentElement.dir).toBe('ltr'))
  await screen.findByText('Backend available')
})

test('reload restores the session through the refresh cookie without browser token storage', async () => {
  const fetch = vi.fn(async (url: string) => {
    if (url.endsWith('/auth/refresh')) return response({ accessToken: 'restored-token', expiresAt: new Date(Date.now() + 1800000).toISOString(), user })
    if (url === '/actuator/health') return response({ status: 'UP' })
    return response([])
  })
  vi.stubGlobal('fetch', fetch)
  mount()
  await screen.findByRole('heading', { name: 'A clear start to every day.' })
  const refresh = (fetch.mock.calls as unknown as [string, RequestInit][]).find(([url]) => url.endsWith('/auth/refresh'))
  expect(refresh?.[1].credentials).toBe('same-origin')
  expect(new Headers(refresh?.[1].headers).get('X-Alanan-Session')).toBe('refresh')
  expect(JSON.stringify(localStorage)).not.toContain('restored-token')
  expect(JSON.stringify(sessionStorage)).not.toContain('restored-token')
})

test('opening a fresh window in Strict Mode rotates the refresh cookie only once', async () => {
  let refreshCalls = 0
  const fetch = vi.fn(async (url: string) => {
    if (url.endsWith('/auth/refresh')) {
      refreshCalls++
      if (refreshCalls > 1) return response({ errorCode: 'UNAUTHENTICATED' }, 401)
      return response({ accessToken: 'new-window-token', expiresAt: new Date(Date.now() + 1800000).toISOString(), user })
    }
    if (url === '/actuator/health') return response({ status: 'UP' })
    return response([])
  })
  vi.stubGlobal('fetch', fetch)
  mount('/', true)
  await screen.findByRole('heading', { name: 'A clear start to every day.' })
  expect(refreshCalls).toBe(1)
  const call = (fetch.mock.calls as unknown as [string, RequestInit][]).find(([url]) => url.endsWith('/auth/refresh'))
  expect(new Headers(call?.[1].headers).has('Authorization')).toBe(false)
})

test('fresh window retries a temporary backend failure and restores the session', async () => {
  let refreshCalls = 0
  vi.stubGlobal('fetch', vi.fn(async (url: string) => {
    if (url.endsWith('/auth/refresh')) {
      refreshCalls++
      if (refreshCalls === 1) throw new TypeError('backend starting')
      return response({ accessToken: 'recovered-token', expiresAt: new Date(Date.now() + 1800000).toISOString(), user })
    }
    if (url === '/actuator/health') return response({ status: 'UP' })
    return response([])
  }))
  mount()
  await screen.findByRole('heading', { name: 'A clear start to every day.' })
  expect(refreshCalls).toBe(2)
})

test('network failures preserve entered credentials for retry', async () => {
  vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('offline')))
  mount(); await submit()
  expect(await screen.findByRole('alert')).toHaveTextContent('server could not be reached')
  expect(screen.getByLabelText('Username')).toHaveValue('owner')
  expect(screen.getByLabelText('Password')).toHaveValue('entered-password')
})

test('invalid credentials are translated and do not open the workspace', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response({ errorCode: 'INVALID_CREDENTIALS' }, 401)))
  mount(); await submit()
  expect(await screen.findByRole('alert')).toHaveTextContent('username or password is incorrect')
  expect(screen.queryByRole('heading', { name: 'A clear start to every day.' })).not.toBeInTheDocument()
})

test('account page shows details and signs out only this session', async () => {
  const fetch = authenticatedFetch()
  vi.stubGlobal('fetch', fetch)
  mount(); await submit()
  fireEvent.click(await screen.findByRole('link', { name: 'Owner' }))
  expect(await screen.findByRole('heading', { name: 'My account' })).toBeInTheDocument()
  expect(screen.getByText('owner')).toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: 'Sign out of this session' }))
  await screen.findByRole('heading', { name: 'Welcome back' })
  const call = (fetch.mock.calls as unknown as [string, RequestInit][]).find(([url]) => url.endsWith('/auth/logout-current'))
  expect(new Headers(call?.[1].headers).get('Authorization')).toBe('Bearer memory-only-token')
})

test('changing password signs out after the server revokes existing sessions', async () => {
  const base = authenticatedFetch()
  const fetch = vi.fn(async (url: string, options?: RequestInit) =>
    url.endsWith('/auth/password') && options?.method === 'PUT' ? response(null, 204) : base(url))
  vi.stubGlobal('fetch', fetch)
  mount(); await submit()
  fireEvent.click(await screen.findByRole('link', { name: 'Owner' }))
  await screen.findByRole('heading', { name: 'My account' })
  fireEvent.change(screen.getByLabelText('Current password'), { target: { value: 'old-password' } })
  fireEvent.change(screen.getByLabelText('New password'), { target: { value: 'a-new-strong-password' } })
  fireEvent.change(screen.getByLabelText('Confirm password'), { target: { value: 'a-new-strong-password' } })
  fireEvent.submit(screen.getByRole('button', { name: 'Save password and sign in again' }).closest('form')!)
  await screen.findByRole('heading', { name: 'Welcome back' })
  const call = (fetch.mock.calls as unknown as [string, RequestInit][]).find(([url]) => url.endsWith('/auth/password'))
  expect(JSON.parse(call?.[1].body as string)).toEqual({ currentPassword: 'old-password', newPassword: 'a-new-strong-password' })
  expect(new Headers(call?.[1].headers).get('Authorization')).toBe('Bearer memory-only-token')
})

test('employee cannot open the user directory through direct navigation', async () => {
  const fetch = authenticatedFetch('en', ['SALES_CREATE', 'SALES_VIEW'])
  vi.stubGlobal('fetch', fetch)
  mount('/users'); await submit()
  expect(await screen.findByText(/do not have permission/)).toHaveAttribute('role', 'alert')
  expect(fetch.mock.calls.some(([url]) => url.includes('/users'))).toBe(false)
})

test('admin creates an account with a role and individual permission changes', async () => {
  const base = authenticatedFetch()
  const fetch = vi.fn(async (url: string, options?: RequestInit) => {
    if (url.endsWith('/users/options')) return response({ roles: [{ name: 'EMPLOYEE', permissions: ['SALES_CREATE', 'SALES_VIEW'] }], permissions: [
      { code: 'SALES_CREATE', description: 'Create sales invoices' },
      { code: 'SALES_VIEW', description: 'View sales invoices' },
      { code: 'COST_VIEW', description: 'View cost' },
    ] })
    if (url.endsWith('/users') && options?.method === 'POST') return response({ id: 'user-2', displayName: 'Clerk', username: 'clerk', active: true, roles: ['EMPLOYEE'] }, 201)
    return base(url)
  })
  vi.stubGlobal('fetch', fetch)
  mount('/users'); await submit()
  await screen.findByRole('heading', { name: 'Add user account' })
  await screen.findByLabelText('Display name')
  fireEvent.change(screen.getByLabelText('Username', { selector: 'input[autocomplete="off"]' }), { target: { value: 'clerk' } })
  fireEvent.change(screen.getByLabelText('Display name'), { target: { value: 'Clerk' } })
  fireEvent.change(screen.getByLabelText('New password'), { target: { value: 'correct-horse-battery' } })
  fireEvent.change(screen.getByLabelText('Confirm password'), { target: { value: 'correct-horse-battery' } })
  fireEvent.click(screen.getByLabelText('Employee'))
  fireEvent.click(screen.getByLabelText('View sales'))
  fireEvent.click(screen.getByLabelText('View cost and profit'))
  fireEvent.click(screen.getByRole('button', { name: 'Create account' }))
  await screen.findByText('Account for Clerk created.')
  const call = (fetch.mock.calls as unknown as [string, RequestInit][]).find(([url, options]) => url.endsWith('/users') && options?.method === 'POST')
  const body = JSON.parse(call?.[1].body as string)
  expect(body).toMatchObject({ username: 'clerk', displayName: 'Clerk', roles: ['EMPLOYEE'], permissions: ['SALES_CREATE', 'COST_VIEW'] })
  expect(screen.getByLabelText('New password')).toHaveValue('')
})

test('user-management permission alone does not show account creation', async () => {
  const base = authenticatedFetch()
  const fetch = vi.fn(async (url: string) => url.endsWith('/auth/login')
    ? response({ accessToken: 'token', expiresAt: new Date(Date.now() + 1800000).toISOString(), user: { ...user, roles: ['EMPLOYEE'] } })
    : base(url))
  vi.stubGlobal('fetch', fetch)
  mount('/users'); await submit()
  await screen.findByRole('heading', { name: 'User directory' })
  expect(screen.queryByRole('heading', { name: 'Add user account' })).not.toBeInTheDocument()
  expect(fetch.mock.calls.some(([url]) => url.endsWith('/users/options'))).toBe(false)
})

test('server token rejection requests reauthentication', async () => {
  const base = authenticatedFetch()
  vi.stubGlobal('fetch', vi.fn(async (url: string) => url.includes('/users')
    ? response({ errorCode: 'UNAUTHENTICATED' }, 401) : base(url)))
  mount('/users'); await submit()
  expect(await screen.findByText('Your session has expired. Sign in again to continue.')).toBeInTheDocument()
  expect(screen.getByRole('heading', { name: 'Welcome back' })).toBeInTheDocument()
})
