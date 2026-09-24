import 'fake-indexeddb/auto'
import { afterEach, beforeEach, expect, test, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import i18n from '../../app/i18n'
import { ReferenceForm } from './ReferenceForm'
import { localDrafts } from '../../db/masterDataDrafts'

vi.mock('../../auth/AuthProvider', () => ({ useAuth: () => ({ user: { id: 'test-owner' } }) }))
beforeEach(async () => { await i18n.changeLanguage('en'); await localDrafts.masterDataDrafts.clear() })
afterEach(() => { cleanup(); vi.unstubAllGlobals() })
function mount(catalog: 'products' | 'customers', onSaved = vi.fn(), onSelect = vi.fn()) {
  return render(<QueryClientProvider client={new QueryClient()}><ReferenceForm catalog={catalog} onSaved={onSaved} onClose={vi.fn()} onSelect={onSelect} /></QueryClientProvider>)
}

test('preserves a user-scoped IndexedDB draft across remount and failed save', async () => {
  vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('offline')))
  const first = mount('customers')
  await waitFor(() => expect(screen.getByLabelText('Name')).toBeEnabled())
  fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'Draft customer' } })
  await waitFor(async () => expect((await localDrafts.masterDataDrafts.get('test-owner:customers:new'))?.input.name).toBe('Draft customer'))
  first.unmount()
  mount('customers')
  await waitFor(() => expect(screen.getByLabelText('Name')).toHaveValue('Draft customer'))
  fireEvent.click(screen.getByRole('button', { name: 'Save record' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('server could not be reached')
  expect(screen.getByLabelText('Name')).toHaveValue('Draft customer')
})

test('shows a normalized phone match and selects the existing customer', async () => {
  const existing = { id: 'existing', name: 'Known Customer', active: true, version: 0 }
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => ({ items: [existing], hasMore: false }) }))
  const select = vi.fn()
  mount('customers', vi.fn(), select)
  await waitFor(() => expect(screen.getByLabelText('Name')).toBeEnabled())
  fireEvent.change(screen.getByLabelText('Phone (optional)'), { target: { value: '0599 123 456' } })
  await screen.findByText('This phone already belongs to Known Customer.')
  expect(screen.getByRole('button', { name: 'Save record' })).toBeDisabled()
  fireEvent.click(screen.getByRole('button', { name: 'Select existing customer' }))
  expect(select).toHaveBeenCalledWith(existing)
})

test('sends decimal price strings and clears draft only after successful save', async () => {
  const fetch = vi.fn().mockResolvedValue({ ok: true, status: 201, json: async () => ({ id: 'saved' }) })
  vi.stubGlobal('fetch', fetch)
  const saved = vi.fn()
  mount('products', saved)
  await waitFor(() => expect(screen.getByLabelText('Name')).toBeEnabled())
  fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'Turmeric' } })
  fireEvent.change(screen.getByLabelText('Product code'), { target: { value: 'SP-1' } })
  fireEvent.change(screen.getByLabelText('Default purchase cost (₪ per unit, optional)'), { target: { value: '22.123456' } })
  fireEvent.change(screen.getByLabelText('Default selling price (₪ per unit)'), { target: { value: '45.25' } })
  fireEvent.click(screen.getByRole('button', { name: 'Save record' }))
  await waitFor(() => expect(saved).toHaveBeenCalled())
  const sent = JSON.parse(fetch.mock.calls[0][1].body)
  expect(sent.price).toBe('45.25')
  expect(sent.defaultCost).toBe('22.123456')
  expect(sent.trackingType).toBe('WEIGHT')
  expect(await localDrafts.masterDataDrafts.get('test-owner:products:new')).toBeUndefined()
})
