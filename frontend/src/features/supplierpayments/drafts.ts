import { localDrafts } from '../../db/masterDataDrafts'
import type { PaymentDraft, PaymentRequest } from './types'
export function newPayment(): PaymentDraft {
  const now = new Date()
  return { requestId: crypto.randomUUID(), supplier: null, paymentDate: [now.getFullYear(), String(now.getMonth() + 1).padStart(2, '0'), String(now.getDate()).padStart(2, '0')].join('-'),
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
