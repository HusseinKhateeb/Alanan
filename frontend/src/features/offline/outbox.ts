import { apiForUser, ApiError, hasSessionFor } from '../../api/client'
import { localDrafts } from '../../db/masterDataDrafts'
import { toRequest } from '../sales/drafts'
import type { SaleDraft, SaleReceipt } from '../sales/types'
import type { QueuedSale } from './types'
import type { ReferenceRecord } from '../masterdata/catalogs'
import { localCustomerRequestId } from './localCustomers'

export function canEditRejectedSale(row: QueuedSale) {
  return row.status === 'NEEDS_ATTENTION' && ([400, 403].includes(row.errorStatus ?? 0) || row.error === 'CUSTOMER_PHONE_EXISTS')
}

export async function enqueueSale(userId: string, draft: SaleDraft) {
  const request = draft.submitted ?? toRequest(draft)
  const key = userId + ':' + request.requestId
  await localDrafts.transaction('rw', localDrafts.outbox, localDrafts.saleDrafts, async () => {
    const old = await localDrafts.outbox.get(key)
    if (old && JSON.stringify(old.request) !== JSON.stringify(request) && !canEditRejectedSale(old)) throw new ApiError('IDEMPOTENCY_CONFLICT')
    if (!old || canEditRejectedSale(old)) {
      await localDrafts.outbox.put({ key, userId, request, draft: { ...draft, submitted: request }, status: 'PENDING', createdAt: old?.createdAt ?? Date.now(), updatedAt: Date.now(), attempts: 0, retryAt: 0, leaseUntil: 0 })
    }
    await localDrafts.saleDrafts.put({ key: userId, draft: { ...draft, submitted: request }, updatedAt: Date.now() })
  })
  return key
}

export async function claimSale(userId: string, now = Date.now()): Promise<QueuedSale | undefined> {
  return localDrafts.transaction('rw', localDrafts.outbox, async () => {
    const rows = await localDrafts.outbox.where('userId').equals(userId).toArray()
    const item = rows.sort((a, b) => a.createdAt - b.createdAt).find(row =>
      (row.status === 'PENDING' && row.retryAt <= now) || (row.status === 'SYNCING' && row.leaseUntil <= now))
    if (!item) return undefined
    const claimed: QueuedSale = { ...item, status: 'SYNCING', attempts: item.attempts + 1, leaseUntil: now + 60_000, updatedAt: now }
    await localDrafts.outbox.put(claimed)
    return claimed
  })
}

export async function syncSales(userId: string, active: () => boolean = () => true) {
  while (active() && hasSessionFor(userId) && navigator.onLine !== false) {
    const claimed = await claimSale(userId)
    if (!claimed) return
    let item: QueuedSale = claimed
    try {
      if (!active()) throw new ApiError('UNAUTHENTICATED', {}, 401)
      const localId = item.request.customerId
      const customerRequestId = localId ? localCustomerRequestId(localId) : null
      if (customerRequestId) {
        const customer = item.draft.customer
        if (!customer || customer.id !== localId) throw new ApiError('SALE_REFERENCE_REQUIRED', {}, 400)
        const created = await apiForUser<ReferenceRecord>(userId, '/customers/quick', {
          method: 'POST', body: JSON.stringify({ name: customer.name, phone: customer.phone || null, requestId: customerRequestId }),
        })
        if (!active() || !hasSessionFor(userId)) throw new ApiError('UNAUTHENTICATED', {}, 401)
        const request = { ...item.request, customerId: created.id }
        const draft = { ...item.draft, customer: created, submitted: request }
        const updated = await localDrafts.transaction('rw', localDrafts.outbox, localDrafts.saleDrafts, localDrafts.references, async () => {
          const current = await localDrafts.outbox.get(item.key)
          if (current?.status !== 'SYNCING' || current.attempts !== item.attempts) return false
          await localDrafts.outbox.update(item.key, { request, draft, updatedAt: Date.now() })
          const saved = await localDrafts.saleDrafts.get(userId)
          if (saved?.draft.requestId === request.requestId) await localDrafts.saleDrafts.update(userId, { draft: { ...saved.draft, customer: created, submitted: request } })
          const key = userId + ':customers'
          const cached = await localDrafts.references.get(key)
          if (cached) await localDrafts.references.put({ ...cached, records: [...cached.records.filter(record => record.id !== localId && record.id !== created.id), created] })
          return true
        })
        if (!updated) continue
        item = { ...item, request, draft }
      }
      const receipt = await apiForUser<SaleReceipt>(userId, '/sales', { method: 'POST', body: JSON.stringify(item.request) })
      await localDrafts.transaction('rw', localDrafts.outbox, localDrafts.saleDrafts, async () => {
        await localDrafts.outbox.update(item.key, { status: 'SYNCED', receipt, error: undefined, errorStatus: undefined, leaseUntil: 0, updatedAt: Date.now() })
        const current = await localDrafts.saleDrafts.get(userId)
        if (current?.draft.requestId === item.request.requestId) await localDrafts.saleDrafts.update(userId, { draft: { ...current.draft, receipt } })
      })
    } catch (failure) {
      const error = failure instanceof ApiError ? failure : new ApiError('NETWORK_ERROR')
      // A stale worker must not overwrite a newer worker's confirmed receipt.
      await localDrafts.transaction('rw', localDrafts.outbox, async () => {
        const current = await localDrafts.outbox.get(item.key)
        if (current?.status !== 'SYNCING' || current.attempts !== item.attempts) return
        const rejected = error.status >= 400 && error.status < 500 && ![401, 408, 429].includes(error.status)
        await localDrafts.outbox.update(item.key, { status: rejected ? 'NEEDS_ATTENTION' : 'PENDING', error: error.code, errorStatus: error.status,
          leaseUntil: 0, retryAt: Date.now() + Math.min(300_000, 5000 * 2 ** Math.min(item.attempts, 6)), updatedAt: Date.now() })
      })
      if (error.status === 401 || !error.status || error.status >= 500) return
    }
  }
}
export async function retrySale(userId: string, key: string) {
  await localDrafts.transaction('rw', localDrafts.outbox, async () => {
    const row = await localDrafts.outbox.get(key)
    if (!row || row.userId !== userId || !['PENDING', 'NEEDS_ATTENTION'].includes(row.status)) return
    await localDrafts.outbox.update(key, { status: 'PENDING', retryAt: 0 })
  })
}
export async function editRejectedSale(userId: string, key: string) {
  await localDrafts.transaction('rw', localDrafts.outbox, localDrafts.saleDrafts, async () => {
    const row = await localDrafts.outbox.get(key)
    if (!row || row.userId !== userId || !canEditRejectedSale(row)) throw new ApiError('IDEMPOTENCY_CONFLICT')
    const existing = await localDrafts.saleDrafts.get(userId)
    if (existing && existing.draft.requestId !== row.request.requestId && !existing.draft.receipt && existing.draft.items.some(item => item.reference)) throw new ApiError('DRAFT_IN_USE')
    await localDrafts.saleDrafts.put({ key: userId, draft: { ...row.draft, submitted: undefined }, updatedAt: Date.now() })
  })
}
