import { apiForUser, ApiError, hasSessionFor } from '../../api/client'
import { localDrafts } from '../../db/masterDataDrafts'
import { toRequest } from '../sales/drafts'
import type { SaleDraft, SaleReceipt } from '../sales/types'
import type { QueuedSale } from './types'

export async function enqueueSale(userId: string, draft: SaleDraft) {
  const request = draft.submitted ?? toRequest(draft)
  const key = userId + ':' + request.requestId
  await localDrafts.transaction('rw', localDrafts.outbox, localDrafts.saleDrafts, async () => {
    const old = await localDrafts.outbox.get(key)
    if (old && JSON.stringify(old.request) !== JSON.stringify(request) && !(old.status === 'NEEDS_ATTENTION' && [400, 403].includes(old.errorStatus ?? 0))) throw new ApiError('IDEMPOTENCY_CONFLICT')
    if (!old || (old.status === 'NEEDS_ATTENTION' && [400, 403].includes(old.errorStatus ?? 0))) {
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
    const item = await claimSale(userId)
    if (!item) return
    try {
      if (!active()) throw new ApiError('UNAUTHENTICATED', {}, 401)
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
    if (!row || row.userId !== userId || row.status !== 'NEEDS_ATTENTION' || ![400, 403].includes(row.errorStatus ?? 0)) throw new ApiError('IDEMPOTENCY_CONFLICT')
    const existing = await localDrafts.saleDrafts.get(userId)
    if (existing && existing.draft.requestId !== row.request.requestId && !existing.draft.receipt && existing.draft.items.some(item => item.reference)) throw new ApiError('DRAFT_IN_USE')
    await localDrafts.saleDrafts.put({ key: userId, draft: { ...row.draft, submitted: undefined }, updatedAt: Date.now() })
  })
}
