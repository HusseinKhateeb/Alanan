import { afterEach, beforeEach, expect, test, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import i18n from '../../app/i18n'
import { HardDeleteControl } from './HardDeleteControl'

const account = { user: { roles: ['ADMIN'] }, offline: false }
vi.mock('../../auth/AuthProvider', () => ({ useAuth: () => account }))
beforeEach(async () => { await i18n.changeLanguage('en'); account.user.roles = ['ADMIN']; account.offline = false })
afterEach(() => { cleanup(); vi.unstubAllGlobals() })
function mount() {
  render(<QueryClientProvider client={new QueryClient()}><MemoryRouter>
    <HardDeleteControl kind="expenses" id="record-1" />
  </MemoryRouter></QueryClientProvider>)
}
test('only an admin can confirm permanent removal with a reason', async () => {
  const requests: string[] = []
  vi.stubGlobal('fetch', vi.fn(async (_url: string, options?: RequestInit) => {
    requests.push(String(options?.body))
    return { ok: true, status: 204 }
  }))
  mount()
  fireEvent.click(screen.getByText('Permanently remove record'))
  const button = screen.getByRole('button', { name: 'Permanently remove' })
  expect(button).toBeDisabled()
  fireEvent.change(screen.getByLabelText('Reason for permanent removal'), { target: { value: 'Duplicate entry' } })
  fireEvent.click(screen.getByLabelText('I understand this cannot be undone'))
  fireEvent.click(button)
  await waitFor(() => expect(requests).toHaveLength(1))
  expect(JSON.parse(requests[0])).toEqual({ reason: 'Duplicate entry' })
})
test('non-admins do not see permanent removal', () => {
  account.user.roles = ['EMPLOYEE']
  mount()
  expect(screen.queryByText('Permanently remove record')).not.toBeInTheDocument()
})
