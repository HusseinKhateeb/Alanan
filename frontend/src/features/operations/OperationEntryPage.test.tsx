import 'fake-indexeddb/auto'
import { afterEach, beforeEach, expect, test, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import i18n from '../../app/i18n'
import { localDrafts } from '../../db/masterDataDrafts'
import { OperationEntryPage } from './OperationEntryPage'
import { OperationHistoryPage } from './OperationHistoryPage'
import { newOperation, saveOperationDraft } from './drafts'
import type { OperationKind } from './types'

vi.mock('../../auth/AuthProvider', () => ({ useAuth: () => ({ user: { id: 'operator', permissions: ['EXPENSES_CREATE', 'INVENTORY_ADJUST'] } }) }))
const category = { id: 'category', name: 'Rent', active: true, version: 0 }
const product = { id: 'product', name: 'Honey', active: true, version: 0, tracking_type: 'PIECE' }
beforeEach(async () => { await i18n.changeLanguage('en'); await localDrafts.operationDrafts.clear() })
afterEach(() => { cleanup(); vi.unstubAllGlobals() })
function mount(kind: OperationKind, history = false) {
  return render(<QueryClientProvider client={new QueryClient()}><MemoryRouter>{history ? <OperationHistoryPage kind={kind} /> : <OperationEntryPage kind={kind} />}</MemoryRouter></QueryClientProvider>)
}
function reference(url: string) {
  return { ok: true, status: 200, json: async () => ({ items: url.includes('expense-categories') ? [category] : url.includes('/products') ? [product] : [], hasMore: false }) }
}
async function prepared(kind: OperationKind) {
  const draft = { ...newOperation(), reference: kind === 'expenses' ? category : product, value: kind === 'expenses' ? '125.50' : '2', reason: 'Damaged' }
  await saveOperationDraft('operator', kind, draft)
  return draft
}
test('expense retry after remount uses the exact persisted request', async () => {
  const draft = await prepared('expenses'), requests: string[] = []
  let fail = true
  vi.stubGlobal('fetch', vi.fn(async (url: string, options?: RequestInit) => {
    if (url === '/api/expenses') {
      requests.push(String(options?.body))
      if (fail) throw new TypeError('offline')
      return { ok: true, status: 201, json: async () => ({ id: 'saved', category_name: 'Rent', amount: '125.50', expense_date: draft.date, payment_method_name: 'Cash' }) }
    }
    return reference(url)
  }))
  const first = mount('expenses')
  fireEvent.click(await screen.findByRole('button', { name: 'Save entry' }))
  await waitFor(() => expect(screen.getByRole('button', { name: 'Retry the same entry' })).toBeEnabled())
  expect((await localDrafts.operationDrafts.get('operator:expenses'))?.draft.submitted?.requestId).toBe(draft.requestId)
  first.unmount(); fail = false; mount('expenses')
  fireEvent.click(await screen.findByRole('button', { name: 'Retry the same entry' }))
  await screen.findByText('Record saved successfully.')
  expect(requests).toHaveLength(2); expect(requests[1]).toBe(requests[0])
})
test('waste validation preserves quantity and reason and unlocks editing', async () => {
  await prepared('waste')
  vi.stubGlobal('fetch', vi.fn(async (url: string) => url === '/api/waste'
    ? { ok: false, status: 400, json: async () => ({ errorCode: 'PRODUCT_UNAVAILABLE' }) } : reference(url)))
  mount('waste')
  fireEvent.click(await screen.findByRole('button', { name: 'Save entry' }))
  await screen.findByRole('alert')
  await waitFor(() => expect(screen.getByLabelText('Pieces')).toBeEnabled())
  expect(screen.getByLabelText('Pieces')).toHaveValue('2')
  expect(screen.getByLabelText('Reason for waste')).toHaveValue('Damaged')
  expect((await localDrafts.operationDrafts.get('operator:waste'))?.draft.submitted).toBeUndefined()
})
test('waste receipt displays negative-stock warning without inventing costs', async () => {
  await prepared('waste')
  vi.stubGlobal('fetch', vi.fn(async (url: string) => url === '/api/waste'
    ? { ok: true, status: 201, json: async () => ({ id: 'saved', product_name: 'Honey', quantity: '2.000', tracking_type: 'PIECE', negative_stock: true }) } : reference(url)))
  mount('waste'); fireEvent.click(await screen.findByRole('button', { name: 'Save entry' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('calculated stock below zero')
  expect(screen.queryByText(/Cost of waste/)).not.toBeInTheDocument()
})
test('expense history sends date and category filters and clears them', async () => {
  const urls: string[] = []
  vi.stubGlobal('fetch', vi.fn(async (url: string) => { urls.push(url); return reference(url) }))
  mount('expenses', true)
  await screen.findByRole('option', { name: 'Rent' })
  fireEvent.change(screen.getByLabelText('From date'), { target: { value: '2026-01-01' } })
  fireEvent.change(screen.getByLabelText('Expense categories'), { target: { value: 'category' } })
  await waitFor(() => expect(urls.some(url => url.includes('/expenses?') && url.includes('from=2026-01-01') && url.includes('categoryId=category'))).toBe(true))
  fireEvent.click(screen.getByRole('button', { name: 'Clear filters' }))
  expect(screen.getByLabelText('From date')).toHaveValue('')
})
