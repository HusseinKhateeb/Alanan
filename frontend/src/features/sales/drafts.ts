import { localDrafts } from '../../db/masterDataDrafts'
import type { SaleDraft, SaleLine, SaleRequest } from './types'
export function newItem(): SaleLine {
  return { localId: crypto.randomUUID(), kind: 'PRODUCT', reference: null, mode: 'BY_WEIGHT', quantity: '1', requestedAmount: '', unitPrice: '' }
}
export function newSale(): SaleDraft {
  const now = new Date()
  return { requestId: crypto.randomUUID(), customer: null, invoiceDate: [now.getFullYear(), String(now.getMonth() + 1).padStart(2, '0'), String(now.getDate()).padStart(2, '0')].join('-'),
    notes: '', paidNow: '0', autoPayFull: true, paymentMethod: null, items: [newItem()] }
}
export function saveSaleDraft(userId: string, draft: SaleDraft) {
  return localDrafts.saleDrafts.put({ key: userId, draft, updatedAt: Date.now() })
}
export function toRequest(draft: SaleDraft): SaleRequest {
  if (draft.items.some(item => !item.reference)) throw new Error('SALE_REFERENCE_REQUIRED')
  return { requestId: draft.requestId, customerId: draft.customer?.id ?? null, invoiceDate: draft.invoiceDate, notes: draft.notes,
    paidNow: draft.paidNow, paymentMethodId: draft.paymentMethod?.id ?? null,
    items: draft.items.map(item => ({ productId: item.kind === 'PRODUCT' ? item.reference!.id : null,
      containerTypeId: item.kind === 'CONTAINER' ? item.reference!.id : null, mode: item.mode,
      quantity: item.mode === 'BY_AMOUNT' ? null : item.quantity, requestedAmount: item.mode === 'BY_AMOUNT' ? item.requestedAmount : null, unitPrice: item.unitPrice })) }
}
