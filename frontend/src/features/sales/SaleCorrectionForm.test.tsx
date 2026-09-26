import { afterEach, beforeEach, expect, test, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import i18n from '../../app/i18n'
import { SaleCorrectionForm } from './SaleCorrectionForm'
import type { SaleDetail } from './types'

vi.mock('../../auth/AuthProvider', () => ({ useAuth: () => ({ user: { id: 'owner' }, offline: false }) }))
beforeEach(async () => { await i18n.changeLanguage('en') })
afterEach(() => { cleanup(); vi.unstubAllGlobals() })
const invoice = {
  id: 'sale-1', invoice_number: 'SAL-2026-000001', customer_id: 'customer-1', customer_name: 'Customer', customer_is_walk_in: false,
  invoice_date: '2026-01-01', total: '45.00', paid: '0.00', paid_at_sale: '0.00', outstanding: '45.00', status: 'OPEN',
  negative_stock: false, version: 0, payment_method_id: null, payment_method_name: null, notes: null, payments: [],
  items: [{ product_id: 'product-1', container_type_id: null, item_name: 'Spice', item_type: 'WEIGHT_PRODUCT',
    selling_mode: 'BY_WEIGHT', quantity: '1.000', unit_price: '45.00', requested_amount: null, line_total: '45.00' }],
} as SaleDetail

test('corrects an existing sale line and sends versioned invoice details', async () => {
  const fetcher = vi.fn(async (url: string, options?: RequestInit) => url === '/api/sales/sale-1' && options?.method === 'PUT'
    ? { ok: true, status: 200, json: async () => ({}) }
    : { ok: true, status: 200, json: async () => ({ items: [], hasMore: false }) })
  vi.stubGlobal('fetch', fetcher)
  const saved = vi.fn()
  render(<QueryClientProvider client={new QueryClient()}><SaleCorrectionForm sale={invoice} onSaved={saved} /></QueryClientProvider>)
  fireEvent.click(screen.getByRole('button', { name: 'Correct sale invoice' }))
  fireEvent.change(screen.getByLabelText('Price per kg (₪)'), { target: { value: '50.00' } })
  fireEvent.change(screen.getByLabelText('Reason for correction'), { target: { value: 'Wrong price' } })
  fireEvent.click(screen.getByRole('button', { name: 'Save correction' }))
  await waitFor(() => expect(saved).toHaveBeenCalledOnce())
  const call = fetcher.mock.calls.find(call => call[0] === '/api/sales/sale-1')
  expect(call).toBeDefined()
  expect(JSON.parse(String(call?.[1]?.body))).toMatchObject({ version: 0, customerId: 'customer-1', paidNow: '0.00',
    reason: 'Wrong price', items: [{ productId: 'product-1', quantity: '1.000', unitPrice: '50.00' }] })
})
test('pay-full shortcut accounts for customer payments already recorded later', () => {
  vi.stubGlobal('fetch', vi.fn(async () => ({ ok: true, status: 200, json: async () => ({ items: [], hasMore: false }) })))
  render(<QueryClientProvider client={new QueryClient()}><SaleCorrectionForm sale={{ ...invoice, paid: '20.00', outstanding: '25.00' }} onSaved={vi.fn()} /></QueryClientProvider>)
  fireEvent.click(screen.getByRole('button', { name: 'Correct sale invoice' }))
  fireEvent.click(screen.getByRole('button', { name: 'Pay full total' }))
  expect(screen.getByLabelText(/Paid now/)).toHaveValue('25.00')
  expect(screen.getByText(/Outstanding: 0.00/)).toBeInTheDocument()
})
