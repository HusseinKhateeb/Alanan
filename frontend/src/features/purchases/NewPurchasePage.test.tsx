import 'fake-indexeddb/auto'
import { afterEach, beforeEach, expect, test, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import i18n from '../../app/i18n'
import { localDrafts } from '../../db/masterDataDrafts'
import { NewPurchasePage } from './NewPurchasePage'
import { newPurchase, savePurchaseDraft } from './drafts'

vi.mock('../../auth/AuthProvider', () => ({ useAuth: () => ({ user: { id: 'buyer', permissions: ['PURCHASES_CREATE', 'PURCHASES_VIEW'] } }) }))
const supplier = { id: 'supplier', name: 'Supplier', active: true, version: 0 }
const product = { id: 'spice', name: 'Spice', tracking_type: 'WEIGHT', active: true, version: 0 }
beforeEach(async () => { await i18n.changeLanguage('en'); await localDrafts.purchaseDrafts.clear() })
afterEach(() => { cleanup(); vi.unstubAllGlobals() })
function mount() {
  return render(<QueryClientProvider client={new QueryClient()}><MemoryRouter><NewPurchasePage /></MemoryRouter></QueryClientProvider>)
}
function reference(url: string) {
  return { ok: true, status: 200, json: async () => ({ items: url.includes('/products') ? [product] : url.includes('/suppliers') ? [supplier] : [], hasMore: false }) }
}
async function prepared() {
  const draft = newPurchase()
  draft.supplier = supplier
  draft.items[0] = { ...draft.items[0], product, quantity: '10', unitCost: '20' }
  draft.paidNow = '50'
  await savePurchaseDraft('buyer', draft)
  return draft
}
test('searches and selects a supplier in one purchase field', async () => {
  vi.stubGlobal('fetch', vi.fn(async (url: string) => reference(url)))
  mount()
  const field = await screen.findByRole('combobox', { name: 'Search Suppliers' })
  fireEvent.focus(field)
  fireEvent.change(field, { target: { value: 'Supp' } })
  fireEvent.click(await screen.findByRole('option', { name: 'Supplier' }))
  expect(field).toHaveValue('Supplier')
  await waitFor(async () => expect((await localDrafts.purchaseDrafts.get('buyer'))?.draft.supplier?.id).toBe('supplier'))
})
test('Enter on a completed purchase line adds the next item', async () => {
  await prepared()
  vi.stubGlobal('fetch', vi.fn(async (url: string) => reference(url)))
  mount()
  const cost = await screen.findByLabelText(/Cost per unit/)
  fireEvent.keyDown(cost, { key: 'Enter' })
  expect(screen.getAllByText('Item 2').length).toBeGreaterThan(0)
  await waitFor(async () => expect((await localDrafts.purchaseDrafts.get('buyer'))?.draft.items).toHaveLength(2))
})
test('persists immutable submitted data and retries the same key after remount', async () => {
  const draft = await prepared()
  const requests: string[] = []
  let fail = true
  vi.stubGlobal('fetch', vi.fn(async (url: string, options?: RequestInit) => {
    if (url === '/api/purchases') {
      requests.push(String(options?.body))
      if (fail) throw new TypeError('network timeout')
      return { ok: true, status: 201, json: async () => ({ id: 'saved', invoiceNumber: 'PUR-2026-000001', total: '200.00', paid: '50.00', outstanding: '150.00', status: 'PARTIAL' }) }
    }
    return reference(url)
  }))
  const first = mount()
  fireEvent.click(await screen.findByRole('button', { name: 'Save purchase' }))
  await screen.findByRole('button', { name: 'Retry the same purchase' })
  expect((await localDrafts.purchaseDrafts.get('buyer'))?.draft.submitted?.requestId).toBe(draft.requestId)
  first.unmount()
  fail = false
  mount()
  fireEvent.click(await screen.findByRole('button', { name: 'Retry the same purchase' }))
  await screen.findByText('Invoice PUR-2026-000001 saved successfully.')
  expect(requests).toHaveLength(2)
  expect(requests[1]).toBe(requests[0])
  expect((await localDrafts.purchaseDrafts.get('buyer'))?.draft.receipt?.id).toBe('saved')
})
test('rejected validation unlocks the draft and keeps entered items', async () => {
  await prepared()
  vi.stubGlobal('fetch', vi.fn(async (url: string) => url === '/api/purchases'
    ? { ok: false, status: 400, json: async () => ({ errorCode: 'PAYMENT_EXCEEDS_TOTAL' }) } : reference(url)))
  mount()
  fireEvent.click(await screen.findByRole('button', { name: 'Save purchase' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('Paid now cannot exceed')
  await waitFor(() => expect(screen.getByRole('button', { name: 'Save purchase' })).toBeEnabled())
  expect((await localDrafts.purchaseDrafts.get('buyer'))?.draft.items[0].quantity).toBe('10')
  expect((await localDrafts.purchaseDrafts.get('buyer'))?.draft.submitted).toBeUndefined()
})
