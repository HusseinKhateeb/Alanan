import { afterEach, beforeEach, expect, test, vi } from 'vitest'
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { setAccessToken } from '../../api/client'
import i18n from '../../app/i18n'
import { OrderEditorPage } from './OrderEditorPage'

vi.mock('../../auth/AuthProvider', () => ({ useAuth: () => ({ user: { id: 'owner', permissions: ['SALES_CREATE'] }, offline: false }) }))
beforeEach(async () => { await i18n.changeLanguage('en'); setAccessToken('test-token', 'owner') })
afterEach(() => { cleanup(); vi.unstubAllGlobals(); setAccessToken(null) })

test('opens a saved order when the backend sends decimal fields as JSON numbers', async () => {
  vi.stubGlobal('fetch', vi.fn(async (url: string) => ({
    ok: true, status: 200,
    json: async () => url === '/api/orders/order-1' ? {
      id: 'order-1', order_number: 'ORD-2026-000001', customer_name: 'Customer', order_date: '2026-01-01',
      total: 45, planned_paid: 0, status: 'OPEN', sale_id: null, version: 0, notes: '',
      request: { requestId: 'request-1', customerId: 'customer-1', invoiceDate: '2026-01-01', notes: '', paidNow: 0, paymentMethodId: null,
        items: [{ productId: 'product-1', containerTypeId: null, mode: 'BY_WEIGHT', quantity: 1, requestedAmount: null, unitPrice: 45 }] },
      references: [{ id: 'product-1', name: 'Spice', active: true, tracking_type: 'WEIGHT' }],
    } : { items: [], hasMore: false },
  })))
  render(<QueryClientProvider client={new QueryClient()}><MemoryRouter initialEntries={['/orders/order-1/edit']}><Routes>
    <Route path="/orders/:id/edit" element={<OrderEditorPage />} />
  </Routes></MemoryRouter></QueryClientProvider>)
  expect(await screen.findByRole('heading', { name: 'Edit order' })).toBeInTheDocument()
  expect(screen.getByText(/Line total: 45\.00/)).toBeInTheDocument()
  expect(screen.getByRole('button', { name: 'Save changes' })).toBeEnabled()
  fireEvent.click(screen.getByRole('button', { name: '₪40 / kg' }))
  expect(screen.getByLabelText('Price per kg (₪)')).toHaveValue('40')
  expect(screen.getByText(/Line total: 40\.00/)).toBeInTheDocument()
})
