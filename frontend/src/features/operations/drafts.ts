import { localDrafts } from '../../db/masterDataDrafts'
import type { OperationDraft, OperationKind } from './types'
export function newOperation(): OperationDraft {
  const now = new Date()
  return { requestId: crypto.randomUUID(), date: [now.getFullYear(), String(now.getMonth() + 1).padStart(2, '0'), String(now.getDate()).padStart(2, '0')].join('-'), reference: null, paymentMethod: null, value: '', reason: '', notes: '' }
}
export function saveOperationDraft(userId: string, kind: OperationKind, draft: OperationDraft) {
  return localDrafts.operationDrafts.put({ key: userId + ':' + kind, draft, updatedAt: Date.now() })
}
export function operationRequest(kind: OperationKind, draft: OperationDraft): Record<string, string | null> {
  if (!draft.reference) throw new Error('OPERATION_REFERENCE_REQUIRED')
  const common = { requestId: draft.requestId, notes: draft.notes }
  return kind === 'expenses' ? { ...common, categoryId: draft.reference.id, amount: draft.value, expenseDate: draft.date, paymentMethodId: draft.paymentMethod?.id ?? null }
    : { ...common, productId: draft.reference.id, quantity: draft.value, wasteDate: draft.date, reason: draft.reason }
}
