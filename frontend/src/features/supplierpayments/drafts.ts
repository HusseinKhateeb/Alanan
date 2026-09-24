import { localDrafts } from '../../db/masterDataDrafts'
import { businessToday } from '../../utils/businessDate'
import type { PaymentDraft, PaymentRequest } from './types'
export function newPayment(): PaymentDraft {
  return { requestId: crypto.randomUUID(), supplier: null, paymentDate: businessToday(),
    amount: '', paymentMethod: null, notes: '', mode: 'AUTO', allocations: [] }
}
export function savePaymentDraft(userId: string, draft: PaymentDraft) {
  return localDrafts.supplierPaymentDrafts.put({ key: userId, draft, updatedAt: Date.now() })
}
export function toPaymentRequest(draft: PaymentDraft): PaymentRequest {
  if (!draft.supplier) throw new Error('PAYMENT_SUPPLIER_REQUIRED')
  return { requestId: draft.requestId, supplierId: draft.supplier.id, paymentDate: draft.paymentDate, amount: draft.amount,
    paymentMethodId: draft.paymentMethod?.id ?? null, notes: draft.notes, mode: draft.mode,
    allocations: draft.mode === 'AUTO' ? [] : draft.allocations.map(({ invoiceId, amount }) => ({ invoiceId, amount })) }
}
