import { afterEach, expect, test, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import './i18n'
import { App } from './App'

vi.mock('virtual:pwa-register/react', () => ({ useRegisterSW: () => ({ needRefresh: [false] }) }))
afterEach(() => { cleanup(); vi.unstubAllGlobals() })

test('switches language and direction, and reports actual backend availability', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, json: async () => ({ status: 'UP' }) }))
  render(<QueryClientProvider client={new QueryClient()}><MemoryRouter><App /></MemoryRouter></QueryClientProvider>)
  fireEvent.click(screen.getByRole('button', { name: 'English' }))
  expect(document.documentElement.dir).toBe('ltr')
  expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('A clear start')
  await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('Backend available'))
  fireEvent.click(screen.getByRole('button', { name: 'العربية' }))
  expect(document.documentElement.dir).toBe('rtl')
  expect(document.documentElement.lang).toBe('ar')
})

test('does not report a disconnected backend as available', async () => {
  vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Network failure')))
  render(<QueryClientProvider client={new QueryClient()}><MemoryRouter><App /></MemoryRouter></QueryClientProvider>)
  fireEvent.click(screen.getByRole('button', { name: 'English' }))
  await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('Backend unavailable'))
})
