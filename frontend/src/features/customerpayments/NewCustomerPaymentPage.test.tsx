import 'fake-indexeddb/auto'
import { afterEach, beforeEach, expect, test, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import i18n from '../../app/i18n'
import { localDrafts } from '../../db/masterDataDrafts'
import { NewCustomerPaymentPage } from './NewCustomerPaymentPage'
import { newPayment, savePaymentDraft } from './drafts'

vi.mock('../../auth/AuthProvider', () => ({ useAuth: () => ({ user: { id: 'payer', permissions: ['CUSTOMER_PAYMENTS_CREATE', 'SALES_VIEW'] } }) }))
const customer = { id: 'customer', name: 'Customer', active: true, version: 0 }
beforeEach(async () => { await i18n.changeLanguage('en'); await localDrafts.customerPaymentDrafts.clear() })
afterEach(() => { cleanup(); vi.unstubAllGlobals() })
function mount(route = '/') {
  return render(<QueryClientProvider client={new QueryClient()}><MemoryRouter initialEntries={[route]}><NewCustomerPaymentPage /></MemoryRouter></QueryClientProvider>)
}
function reference(url: string) {
  return { ok: true, status: 200, json: async () => ({ items: url.includes('/master-data/customers') ? [customer] : [], hasMore: false }) }
}
async function prepared(manual = false) {
  const draft = { ...newPayment(), customer, amount: '50', mode: manual ? 'MANUAL' as const : 'AUTO' as const,
    allocations: manual ? [{ invoiceId: 'invoice', invoiceNumber: 'PUR-1', amount: '40' }] : [] }
  await savePaymentDraft('payer', draft)
  return draft
}
test('retries the identical persisted payment after a network failure and remount', async () => {
  const draft = await prepared()
  const requests: string[] = []
  let fail = true
  vi.stubGlobal('fetch', vi.fn(async (url: string, options?: RequestInit) => {
    if (url === '/api/customer-payments') {
      requests.push(String(options?.body))
      if (fail) throw new TypeError('network timeout')
      return { ok: true, status: 201, json: async () => ({ id: 'saved', customerName: 'Customer', amount: '50.00', paymentMethodName: 'Cash', allocations: [] }) }
    }
    return reference(url)
  }))
  const first = mount()
  fireEvent.click(await screen.findByRole('button', { name: 'Save payment' }))
  await screen.findByRole('button', { name: 'Retry the same payment' })
  expect((await localDrafts.customerPaymentDrafts.get('payer'))?.draft.submitted?.requestId).toBe(draft.requestId)
  first.unmount(); fail = false; mount()
  fireEvent.click(await screen.findByRole('button', { name: 'Retry the same payment' }))
  await screen.findByText('Customer payment saved successfully.')
  expect(requests).toHaveLength(2)
  expect(requests[1]).toBe(requests[0])
  await waitFor(async () => expect((await localDrafts.customerPaymentDrafts.get('payer'))?.draft.receipt?.id).toBe('saved'))
})
test('manual allocations must match the full amount before saving', async () => {
  await prepared(true)
  vi.stubGlobal('fetch', vi.fn(async (url: string) => reference(url)))
  mount()
  expect(await screen.findByRole('button', { name: 'Save payment' })).toBeDisabled()
  fireEvent.change(screen.getByLabelText('Payment amount (₪)'), { target: { value: '40' } })
  expect(screen.getByRole('button', { name: 'Save payment' })).toBeEnabled()
})
test('overpayment rejection restores an editable draft without losing values', async () => {
  await prepared()
  vi.stubGlobal('fetch', vi.fn(async (url: string) => url === '/api/customer-payments'
    ? { ok: false, status: 400, json: async () => ({ errorCode: 'PAYMENT_EXCEEDS_OUTSTANDING' }) } : reference(url)))
  mount()
  fireEvent.click(await screen.findByRole('button', { name: 'Save payment' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('outstanding balance')
  await waitFor(() => expect(screen.getByRole('button', { name: 'Save payment' })).toBeEnabled())
  expect((await localDrafts.customerPaymentDrafts.get('payer'))?.draft.amount).toBe('50')
  expect((await localDrafts.customerPaymentDrafts.get('payer'))?.draft.submitted).toBeUndefined()
})

test('payment opened from a balance preselects the customer without replacing an existing draft', async () => {
  const fetcher = vi.fn(async (url: string) => url === '/api/customers/customer'
    ? { ok: true, status: 200, json: async () => customer } : reference(url))
  vi.stubGlobal('fetch', fetcher)
  mount('/customer-payments/new?customerId=customer')
  expect(await screen.findByRole('combobox', { name: 'Search Customers' })).toHaveValue('Customer')
  await waitFor(async () => expect((await localDrafts.customerPaymentDrafts.get('payer'))?.draft.customer?.id).toBe('customer'))
})
