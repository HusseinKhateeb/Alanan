import { afterEach, beforeEach, expect, test, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import i18n from '../../app/i18n'
import { BackupPage } from './BackupPage'

const account = { user: { id: 'owner', roles: ['ADMIN'] }, offline: false }
vi.mock('../../auth/AuthProvider', () => ({ useAuth: () => account }))
beforeEach(async () => { account.user.roles = ['ADMIN']; account.offline = false; await i18n.changeLanguage('en') })
afterEach(() => { cleanup(); vi.unstubAllGlobals() })
function mount() {
  render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
    <MemoryRouter><BackupPage /></MemoryRouter>
  </QueryClientProvider>)
}
test('an admin can upload a backup for staging without sending it as JSON', async () => {
  const requests: RequestInit[] = []
  vi.stubGlobal('fetch', vi.fn(async (_url: string, options?: RequestInit) => {
    if (options?.method === 'POST') {
      requests.push(options)
      return { ok: true, status: 201, json: async () => ({ id: 'stored', source: 'UPLOAD' }) }
    }
    return { ok: true, status: 200, json: async () => [] }
  }))
  mount()
  fireEvent.change(screen.getByLabelText('Backup file'), { target: { files: [new File(['PGDMP'], 'backup.dump')] } })
  fireEvent.submit(screen.getByRole('button', { name: 'Verify and stage' }).closest('form')!)
  await screen.findByText('Backup verified and staged. The live database was not changed.')
  expect(requests).toHaveLength(1)
  expect(requests[0].body).toBeInstanceOf(FormData)
  expect(new Headers(requests[0].headers).has('Content-Type')).toBe(false)
})
test('non-admins cannot open backup management', async () => {
  account.user.roles = ['EMPLOYEE']
  mount()
  await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('do not have permission'))
  expect(screen.queryByRole('button', { name: 'Verify and stage' })).not.toBeInTheDocument()
})
