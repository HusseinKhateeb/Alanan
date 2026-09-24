import 'fake-indexeddb/auto'
import { beforeEach, afterEach, expect, test, vi } from 'vitest'
import { localDrafts } from '../../db/masterDataDrafts'
import { setAccessToken } from '../../api/client'
import { newSale } from '../sales/drafts'
import { claimSale, enqueueSale, editRejectedSale, retrySale, syncSales } from './outbox'
import { cachedReferencePage, refreshReferences } from './references'
import { saveLocalCustomer } from './localCustomers'

beforeEach(async () => {
  await localDrafts.outbox.clear(); await localDrafts.saleDrafts.clear(); await localDrafts.references.clear()
  setAccessToken('test-token', 'alice')
})
afterEach(() => { vi.unstubAllGlobals(); setAccessToken(null) })
function draft() {
  const value = newSale()
  value.items[0] = { ...value.items[0], reference: { id: 'spice', name: 'Spice', active: true, version: 0, tracking_type: 'WEIGHT' }, unitPrice: '45', quantity: '1' }
  value.paidNow = '45'
  return value
}
const receipt = { id: 'server-sale', invoiceNumber: 'SAL-1', total: '45.00', paid: '45.00', outstanding: '0.00', status: 'PAID', negativeStock: false }
const response = (status: number, value: unknown) => ({ ok: status < 400, status, json: async () => value })

test('competing tabs claim one sale and reclaim an abandoned lease', async () => {
  await enqueueSale('alice', draft())
  const claims = await Promise.all([claimSale('alice', 100), claimSale('alice', 100)])
  expect(claims.filter(Boolean)).toHaveLength(1)
  expect(await claimSale('alice', 60_099)).toBeUndefined()
  expect((await claimSale('alice', 60_101))?.attempts).toBe(2)
})
test('network loss preserves the exact request across database reopen and retry', async () => {
  const key = await enqueueSale('alice', draft())
  const bodies: string[] = []
  let online = false
  vi.stubGlobal('fetch', vi.fn(async (_url, init: RequestInit) => {
    bodies.push(String(init.body))
    if (!online) throw new TypeError('network unavailable')
    return response(201, receipt)
  }))
  await syncSales('alice')
  expect((await localDrafts.outbox.get(key))?.status).toBe('PENDING')
  localDrafts.close(); await localDrafts.open()
  online = true
  await retrySale('alice', key); await syncSales('alice')
  expect(bodies).toHaveLength(2); expect(bodies[1]).toBe(bodies[0])
  expect((await localDrafts.outbox.get(key))?.receipt).toEqual(receipt)
})
test('session expiry pauses work and another user cannot transmit the pending sale', async () => {
  const key = await enqueueSale('alice', draft())
  const fetcher = vi.fn(async () => response(401, { errorCode: 'UNAUTHENTICATED' }))
  vi.stubGlobal('fetch', fetcher)
  await syncSales('alice')
  expect((await localDrafts.outbox.get(key))?.status).toBe('PENDING')
  setAccessToken('bob-token', 'bob')
  await retrySale('alice', key); await syncSales('alice'); await syncSales('bob')
  expect(fetcher).toHaveBeenCalledTimes(1)
  setAccessToken('renewed', 'alice'); fetcher.mockImplementation(async () => response(201, receipt))
  await syncSales('alice')
  expect((await localDrafts.outbox.get(key))?.status).toBe('SYNCED')
})
test('validation failure stays visible and can be edited without replacing its key', async () => {
  const original = draft(), key = await enqueueSale('alice', original)
  vi.stubGlobal('fetch', vi.fn(async () => response(400, { errorCode: 'PRODUCT_UNAVAILABLE' })))
  await syncSales('alice')
  expect((await localDrafts.outbox.get(key))?.status).toBe('NEEDS_ATTENTION')
  await editRejectedSale('alice', key)
  const editable = (await localDrafts.saleDrafts.get('alice'))!.draft
  expect(editable.submitted).toBeUndefined(); expect(editable.requestId).toBe(original.requestId)
  editable.items[0].unitPrice = '40'; editable.paidNow = '40'
  await enqueueSale('alice', editable)
  expect((await localDrafts.outbox.get(key))?.request.items[0].unitPrice).toBe('40')
})
test('ambiguous conflict cannot be edited into a second sale', async () => {
  const original = draft(), key = await enqueueSale('alice', original)
  vi.stubGlobal('fetch', vi.fn(async () => response(409, { errorCode: 'IDEMPOTENCY_CONFLICT' })))
  await syncSales('alice')
  await expect(editRejectedSale('alice', key)).rejects.toThrow('IDEMPOTENCY_CONFLICT')
  await expect(enqueueSale('alice', { ...original, paidNow: '0' })).rejects.toThrow('IDEMPOTENCY_CONFLICT')
})
test('two workers send a queued sale once', async () => {
  await enqueueSale('alice', draft())
  const fetcher = vi.fn(async () => response(201, receipt)); vi.stubGlobal('fetch', fetcher)
  await Promise.all([syncSales('alice'), syncSales('alice')])
  expect(fetcher).toHaveBeenCalledTimes(1)
})
test('later receipt does not overwrite a newer draft', async () => {
  await enqueueSale('alice', draft())
  const newer = draft()
  await localDrafts.saleDrafts.put({ key: 'alice', draft: newer, updatedAt: Date.now() })
  vi.stubGlobal('fetch', vi.fn(async () => response(201, receipt)))
  await syncSales('alice')
  expect((await localDrafts.saleDrafts.get('alice'))?.draft.requestId).toBe(newer.requestId)
  expect((await localDrafts.saleDrafts.get('alice'))?.draft.receipt).toBeUndefined()
})
test('reference cache is paginated and isolated by user', async () => {
  vi.stubGlobal('fetch', vi.fn(async (url: string) => response(200, { items: [{ id: url, name: 'Spice', active: true, version: 0 }], hasMore: url.includes('page=0') })))
  await refreshReferences('alice')
  expect((await cachedReferencePage('alice', 'products', 'spi', 0)).items).toHaveLength(2)
  await expect(cachedReferencePage('bob', 'products', '', 0)).rejects.toThrow('OFFLINE_REFERENCES_MISSING')
})
test('failed refresh keeps the previous complete cache', async () => {
  await localDrafts.references.put({ key: 'alice:products', userId: 'alice', kind: 'products', records: [{ id: 'old', name: 'Old', active: true, version: 0 }], updatedAt: 1 })
  vi.stubGlobal('fetch', vi.fn(async (url: string) => {
    if (url.includes('page=1')) throw new TypeError('offline')
    return response(200, { items: [], hasMore: true })
  }))
  await expect(refreshReferences('alice')).rejects.toThrow('NETWORK_ERROR')
  expect((await cachedReferencePage('alice', 'products', '', 0)).items[0].id).toBe('old')
})
test('reference refresh preserves a customer waiting for an offline sale', async () => {
  const local = await saveLocalCustomer('alice', crypto.randomUUID(), 'Pending customer', '0599876543')
  vi.stubGlobal('fetch', vi.fn(async () => response(200, { items: [], hasMore: false })))
  await refreshReferences('alice')
  expect((await cachedReferencePage('alice', 'customers', 'Pending', 0)).items.map(item => item.id)).toContain(local.id)
})
test('offline queue resumes when connection returns', async () => {
  const key = await enqueueSale('alice', draft())
  const fetcher = vi.fn(async () => response(201, receipt)); vi.stubGlobal('fetch', fetcher)
  vi.stubGlobal('navigator', { onLine: false })
  await syncSales('alice'); expect(fetcher).not.toHaveBeenCalled()
  vi.stubGlobal('navigator', { onLine: true })
  await syncSales('alice')
  expect((await localDrafts.outbox.get(key))?.status).toBe('SYNCED')
})
test('queued sale creates its local customer first and then sends the canonical customer ID', async () => {
  const customer = await saveLocalCustomer('alice', crypto.randomUUID(), 'Offline customer', '0599876543')
  const sale = { ...draft(), customer }
  const key = await enqueueSale('alice', sale)
  const calls: string[] = []
  const bodies: Record<string, unknown>[] = []
  vi.stubGlobal('fetch', vi.fn(async (url: string, init: RequestInit) => {
    calls.push(url); bodies.push(JSON.parse(String(init.body)))
    if (url === '/api/customers/quick') return response(201, { ...customer, id: 'real-customer' })
    if (url === '/api/sales') return response(201, receipt)
    throw new Error('Unexpected request')
  }))
  await syncSales('alice')
  expect(calls).toEqual(['/api/customers/quick', '/api/sales'])
  expect(bodies[1].customerId).toBe('real-customer')
  expect((await localDrafts.outbox.get(key))?.status).toBe('SYNCED')
  expect((await localDrafts.saleDrafts.get('alice'))?.draft.customer?.id).toBe('real-customer')
})
test('a lost customer creation response does not send the sale or create a new request ID', async () => {
  const customer = await saveLocalCustomer('alice', crypto.randomUUID(), 'Offline customer', '')
  const key = await enqueueSale('alice', { ...draft(), customer })
  let fail = true
  const ids: string[] = []
  const fetcher = vi.fn(async (url: string, init: RequestInit) => {
    if (url === '/api/customers/quick') {
      ids.push(JSON.parse(String(init.body)).requestId)
      if (fail) throw new TypeError('response lost')
      return response(201, { ...customer, id: 'real-customer' })
    }
    if (url === '/api/sales') return response(201, receipt)
    throw new Error('Unexpected request')
  })
  vi.stubGlobal('fetch', fetcher)
  await syncSales('alice')
  expect((await localDrafts.outbox.get(key))?.status).toBe('PENDING')
  expect(fetcher).toHaveBeenCalledTimes(1)
  fail = false
  await retrySale('alice', key); await syncSales('alice')
  expect(ids).toHaveLength(2)
  expect(ids[0]).toBe(ids[1])
  expect((await localDrafts.outbox.get(key))?.status).toBe('SYNCED')
})
test('duplicate phone rejection lets the sale select the existing customer before retry', async () => {
  const customer = await saveLocalCustomer('alice', crypto.randomUUID(), 'Duplicate customer', '0599876543')
  const original = { ...draft(), customer }
  const key = await enqueueSale('alice', original)
  let saleBody = ''
  const fetcher = vi.fn(async (url: string, init?: RequestInit) => {
    if (url === '/api/customers/quick') return response(409, { errorCode: 'CUSTOMER_PHONE_EXISTS' })
    saleBody = String(init?.body)
    return response(201, receipt)
  })
  vi.stubGlobal('fetch', fetcher)
  await syncSales('alice')
  expect((await localDrafts.outbox.get(key))?.status).toBe('NEEDS_ATTENTION')
  expect(fetcher).toHaveBeenCalledTimes(1)
  await editRejectedSale('alice', key)
  const editable = (await localDrafts.saleDrafts.get('alice'))!.draft
  await enqueueSale('alice', { ...editable, customer: { id: 'existing-customer', name: 'Existing customer', active: true, version: 0 } })
  await syncSales('alice')
  expect((await localDrafts.outbox.get(key))?.status).toBe('SYNCED')
  expect(JSON.parse(saleBody).customerId).toBe('existing-customer')
})
test('old session response cannot expire a newly signed-in account', async () => {
  const key = await enqueueSale('alice', draft())
  vi.stubGlobal('fetch', vi.fn(async () => {
    setAccessToken('bob-token', 'bob')
    return response(401, { errorCode: 'UNAUTHENTICATED' })
  }))
  await syncSales('alice')
  const { hasSessionFor } = await import('../../api/client')
  expect(hasSessionFor('bob')).toBe(true)
  expect((await localDrafts.outbox.get(key))?.status).toBe('PENDING')
})
