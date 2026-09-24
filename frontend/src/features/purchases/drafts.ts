import { localDrafts } from '../../db/masterDataDrafts'
import { businessToday } from '../../utils/businessDate'
import type { PurchaseDraft, PurchaseItem, PurchaseRequest } from './types'

export function newItem(): PurchaseItem { return { localId: crypto.randomUUID(), product: null, quantity: '', unitCost: '' } }
export function newPurchase(): PurchaseDraft {
  const date = businessToday()
  return { requestId: crypto.randomUUID(), supplier: null, invoiceDate: date, supplierReference: '', notes: '', paidNow: '0', paymentMethod: null, items: [newItem()] }
}
export function savePurchaseDraft(userId: string, draft: PurchaseDraft) {
  return localDrafts.purchaseDrafts.put({ key: userId, draft, updatedAt: Date.now() })
}
export function toRequest(draft: PurchaseDraft): PurchaseRequest {
  if (!draft.supplier || draft.items.some(item => !item.product)) throw new Error('PURCHASE_REFERENCE_REQUIRED')
  return { requestId: draft.requestId, supplierId: draft.supplier.id, invoiceDate: draft.invoiceDate,
    supplierReference: draft.supplierReference, notes: draft.notes, paidNow: draft.paidNow,
    paymentMethodId: draft.paymentMethod?.id ?? null,
    items: draft.items.map(item => ({ productId: item.product!.id, quantity: item.quantity, unitCost: item.unitCost })) }
}
