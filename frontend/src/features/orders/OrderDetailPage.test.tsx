import { afterEach, beforeEach, expect, test, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { setAccessToken } from '../../api/client'
import i18n from '../../app/i18n'
import { businessToday } from '../../utils/businessDate'
import { OrderDetailPage } from './OrderDetailPage'

vi.mock('../../auth/AuthProvider', () => ({ useAuth: () => ({ user: { id: 'owner', permissions: ['SALES_VIEW', 'SALES_CREATE'] }, offline: false }) }))
beforeEach(async () => { await i18n.changeLanguage('en'); setAccessToken('test-token', 'owner') })
afterEach(() => { cleanup(); vi.unstubAllGlobals(); setAccessToken(null) })
const order = {
  id: 'order-1', order_number: 'ORD-2026-000001', customer_name: 'Customer', order_date: '2026-01-01',
  total: '45.00', planned_paid: '0.00', status: 'OPEN', sale_id: null, version: 0, notes: '',
  request: { requestId: 'request-1', customerId: 'customer-1', invoiceDate: '2026-01-01', notes: '', paidNow: '0.00', paymentMethodId: null,
    items: [{ productId: 'product-1', containerTypeId: null, mode: 'BY_WEIGHT', quantity: '1', requestedAmount: null, unitPrice: '45.00' }] },
  references: [{ id: 'product-1', name: 'Spice', active: true, tracking_type: 'WEIGHT' }],
}
test('requires reason and confirmation before deleting an open order', async () => {
  const fetcher = vi.fn(async (url: string, options?: RequestInit) => {
    if (url === '/api/orders/order-1' && options?.method === 'DELETE') return { ok: true, status: 204, json: async () => null }
    return { ok: true, status: 200, json: async () => order }
  })
  vi.stubGlobal('fetch', fetcher)
  render(<QueryClientProvider client={new QueryClient()}><MemoryRouter initialEntries={['/orders/order-1']}><Routes>
    <Route path="/orders/:id" element={<OrderDetailPage />} /><Route path="/orders" element={<p>Orders list</p>} />
  </Routes></MemoryRouter></QueryClientProvider>)
  await screen.findByText('ORD-2026-000001')
  fireEvent.click(screen.getByText('Delete order', { selector: 'summary' }))
  const remove = screen.getByRole('button', { name: 'Delete order' })
  expect(remove).toBeDisabled()
  fireEvent.change(screen.getByLabelText('Reason for permanent removal'), { target: { value: 'Wrong order' } })
  expect(remove).toBeDisabled()
  fireEvent.click(screen.getByRole('checkbox'))
  expect(remove).toBeEnabled()
  fireEvent.click(remove)
  await waitFor(() => expect(fetcher.mock.calls.some(call => call[0] === '/api/orders/order-1' && call[1]?.method === 'DELETE')).toBe(true))
  expect(JSON.parse(String(fetcher.mock.calls.find(call => call[1]?.method === 'DELETE')?.[1]?.body))).toEqual({ version: 0, reason: 'Wrong order' })
})

test('does not offer delivery before the planned date', async () => {
  const future = new Date(Date.now() + 7 * 24 * 60 * 60 * 1000).toISOString().slice(0, 10)
  expect(future > businessToday()).toBe(true)
  vi.stubGlobal('fetch', vi.fn(async () => ({ ok: true, status: 200, json: async () => ({ ...order, order_date: future }) })))
  render(<QueryClientProvider client={new QueryClient()}><MemoryRouter initialEntries={['/orders/order-1']}><Routes>
    <Route path="/orders/:id" element={<OrderDetailPage />} />
  </Routes></MemoryRouter></QueryClientProvider>)
  expect(await screen.findByText('ORD-2026-000001')).toBeInTheDocument()
  expect(screen.getByRole('button', { name: 'Delivered - create sale invoice' })).toBeDisabled()
  expect(screen.getByText(`This order can be delivered on or after ${future}.`)).toBeInTheDocument()
})
