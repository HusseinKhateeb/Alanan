import { setAccessToken } from '../../api/client'
import 'fake-indexeddb/auto'
import { afterEach, beforeEach, expect, test, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import i18n from '../../app/i18n'
import { localDrafts } from '../../db/masterDataDrafts'
import { NewSalePage } from './NewSalePage'
import { newSale, saveSaleDraft } from './drafts'

vi.mock('../../auth/AuthProvider', () => ({ useAuth: () => ({ user: { id: 'buyer', permissions: ['SALES_CREATE', 'SALES_VIEW'] } }) }))
const customer = { id: 'customer', name: 'Supplier', active: true, version: 0 }
const product = { id: 'spice', name: 'Spice', tracking_type: 'WEIGHT', default_selling_price: '20.00', active: true, version: 0 }
beforeEach(async () => { await i18n.changeLanguage('en'); await localDrafts.saleDrafts.clear(); await localDrafts.outbox.clear(); await localDrafts.references.clear(); setAccessToken('test-token', 'buyer') })
afterEach(() => { cleanup(); vi.unstubAllGlobals(); setAccessToken(null) })
function mount() {
  return render(<QueryClientProvider client={new QueryClient()}><MemoryRouter><NewSalePage /></MemoryRouter></QueryClientProvider>)
}
function reference(url: string) {
  return { ok: true, status: 200, json: async () => ({ items: url.includes('/products') ? [product] : url.includes('/customers') ? [customer] : [], hasMore: false }) }
}
async function prepared() {
  const draft = newSale()
  draft.customer = customer
  draft.items[0] = { ...draft.items[0], reference: product, quantity: '10', unitPrice: '20' }
  draft.paidNow = '50'
  await saveSaleDraft('buyer', draft)
  return draft
}
test('persists immutable submitted data and retries the same key after remount', async () => {
  const draft = await prepared()
  const requests: string[] = []
  let fail = true
  vi.stubGlobal('fetch', vi.fn(async (url: string, options?: RequestInit) => {
    if (url === '/api/sales') {
      requests.push(String(options?.body))
      if (fail) throw new TypeError('network timeout')
      return { ok: true, status: 201, json: async () => ({ id: 'saved', invoiceNumber: 'SAL-2026-000001', total: '200.00', paid: '50.00', outstanding: '150.00', status: 'PARTIAL' }) }
    }
    return reference(url)
  }))
  const first = mount()
  fireEvent.click(await screen.findByRole('button', { name: 'Queue sale' }))
  await screen.findByRole('button', { name: 'Retry the same sale' })
  expect((await localDrafts.saleDrafts.get('buyer'))?.draft.submitted?.requestId).toBe(draft.requestId)
  first.unmount()
  fail = false
  mount()
  fireEvent.click(await screen.findByRole('button', { name: 'Retry the same sale' }))
  await screen.findByText('Sale SAL-2026-000001 saved successfully.')
  expect(requests).toHaveLength(2)
  expect(requests[1]).toBe(requests[0])
  await waitFor(async () => expect((await localDrafts.saleDrafts.get('buyer'))?.draft.receipt?.id).toBe('saved'))
})
test('rejected validation preserves the sale as needs attention', async () => {
  const draft = await prepared()
  vi.stubGlobal('fetch', vi.fn(async (url: string) => url === '/api/sales'
    ? { ok: false, status: 400, json: async () => ({ errorCode: 'PRODUCT_UNAVAILABLE' }) } : reference(url)))
  mount()
  fireEvent.click(await screen.findByRole('button', { name: 'Queue sale' }))
  await waitFor(async () => expect((await localDrafts.outbox.get('buyer:' + draft.requestId))?.status).toBe('NEEDS_ATTENTION'))
  expect((await localDrafts.saleDrafts.get('buyer'))?.draft.items[0].quantity).toBe('10')
})

test('walk-in sale requires full payment before saving', async () => {
  const draft = await prepared()
  draft.customer = null
  await saveSaleDraft('buyer', draft)
  vi.stubGlobal('fetch', vi.fn(async (url: string) => reference(url)))
  mount()
  expect(await screen.findByRole('button', { name: 'Queue sale' })).toBeDisabled()
  fireEvent.click(screen.getByRole('button', { name: 'Pay full total' }))
  expect(screen.getByRole('button', { name: 'Queue sale' })).toBeEnabled()
})

test('quick customer creation selects and persists the customer without losing the sale draft', async () => {
  const draft = await prepared()
  draft.customer = null
  await saveSaleDraft('buyer', draft)
  const created = { id: 'new-customer', name: 'New customer', phone: '0599123456', active: true, version: 0 }
  let postedBody = ''
  const fetcher = vi.fn(async (url: string, options?: RequestInit) => {
    if (url === '/api/customers/quick') { postedBody = String(options?.body); return { ok: true, status: 201, json: async () => created } }
    return reference(url)
  })
  vi.stubGlobal('fetch', fetcher)
  mount()
  fireEvent.click(await screen.findByRole('button', { name: '+ New customer' }))
  fireEvent.change(screen.getByLabelText('Name'), { target: { value: created.name } })
  fireEvent.change(screen.getByLabelText('Phone (optional)'), { target: { value: created.phone } })
  fireEvent.click(screen.getByRole('button', { name: 'Save and select customer' }))
  await waitFor(async () => expect((await localDrafts.saleDrafts.get('buyer'))?.draft.customer?.id).toBe(created.id))
  expect((await localDrafts.saleDrafts.get('buyer'))?.draft.items[0].quantity).toBe('10')
  expect(JSON.parse(postedBody)).toEqual({ name: created.name, phone: created.phone })
  expect(screen.getByRole('combobox', { name: 'Search Customers' })).toHaveValue(created.name)
  expect(screen.getByRole('button', { name: 'Queue sale' })).toBeEnabled()
})

test('customer search and selection use the same field for name and phone', async () => {
  const draft = await prepared()
  draft.customer = null
  await saveSaleDraft('buyer', draft)
  const matched = { ...customer, name: 'Husain', phone: '0599123456' }
  const fetcher = vi.fn(async (url: string) => url.includes('/customers')
    ? { ok: true, status: 200, json: async () => ({ items: [matched], hasMore: false }) }
    : reference(url))
  vi.stubGlobal('fetch', fetcher)
  mount()
  const field = await screen.findByRole('combobox', { name: 'Search Customers' })
  expect(screen.queryByRole('listbox')).not.toBeInTheDocument()
  fireEvent.focus(field)
  fireEvent.change(field, { target: { value: '0599' } })
  expect(await screen.findByRole('option', { name: /Husain.*0599123456/ })).toBeInTheDocument()
  fireEvent.click(screen.getByRole('option', { name: /Husain.*0599123456/ }))
  expect(field).toHaveValue('Husain')
  await waitFor(async () => expect((await localDrafts.saleDrafts.get('buyer'))?.draft.customer?.id).toBe(customer.id))
  expect(fetcher.mock.calls.some(call => call[0].includes('q=0599'))).toBe(true)
  fireEvent.focus(field)
  await screen.findByRole('option', { name: 'Walk-in Customer' })
  fireEvent.keyDown(field, { key: 'Enter' })
  expect(field).toHaveValue('')
  await waitFor(async () => expect((await localDrafts.saleDrafts.get('buyer'))?.draft.customer).toBeNull())
})

test('one-tap product selection keeps a new walk-in sale paid in full while quantities change', async () => {
  vi.stubGlobal('fetch', vi.fn(async (url: string) => reference(url)))
  mount()
  fireEvent.click(await screen.findByRole('button', { name: 'Spice' }))
  expect(screen.getByLabelText(/Paid now/)).toHaveValue('20.00')
  fireEvent.change(screen.getByLabelText('kg'), { target: { value: '2' } })
  expect(screen.getByLabelText(/Paid now/)).toHaveValue('40.00')
  fireEvent.change(screen.getByLabelText(/Paid now/), { target: { value: '10.00' } })
  fireEvent.change(screen.getByLabelText('kg'), { target: { value: '3' } })
  expect(screen.getByLabelText(/Paid now/)).toHaveValue('10.00')
})

test('offline form loads cached references even when network queries would be paused', async () => {
  for (const kind of ['products', 'customers', 'container-types', 'payment-methods']) {
    await localDrafts.references.put({ key: 'buyer:' + kind, userId: 'buyer', kind, records: kind === 'products' ? [product] : [], updatedAt: Date.now() })
  }
  vi.stubGlobal('navigator', { onLine: false })
  const fetcher = vi.fn(async () => { throw new TypeError('offline') })
  vi.stubGlobal('fetch', fetcher)
  mount()
  expect(await screen.findByRole('option', { name: 'Spice' })).toBeInTheDocument()
  expect(fetcher).not.toHaveBeenCalled()
})

test('single customer field can select a cached customer offline', async () => {
  await localDrafts.references.put({ key: 'buyer:customers', userId: 'buyer', kind: 'customers', records: [customer], updatedAt: Date.now() })
  vi.stubGlobal('navigator', { onLine: false })
  const fetcher = vi.fn(async () => { throw new TypeError('offline') })
  vi.stubGlobal('fetch', fetcher)
  mount()
  const field = await screen.findByRole('combobox', { name: 'Search Customers' })
  fireEvent.focus(field)
  fireEvent.change(field, { target: { value: 'Sup' } })
  fireEvent.click(await screen.findByRole('option', { name: 'Supplier' }))
  expect(field).toHaveValue('Supplier')
  await waitFor(async () => expect((await localDrafts.saleDrafts.get('buyer'))?.draft.customer?.id).toBe(customer.id))
  expect(fetcher).not.toHaveBeenCalled()
})
