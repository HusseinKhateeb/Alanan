import type { ReferenceRecord } from '../masterdata/catalogs'
export type OperationKind = 'expenses' | 'waste'
export interface OperationRecord {
  id: string; category_name?: string; amount?: string; expense_date?: string; payment_method_name?: string
  product_name?: string; quantity?: string; tracking_type?: string; waste_date?: string; reason?: string
  notes?: string | null; negative_stock?: boolean; cost_total?: string | null
}
export interface OperationDraft {
  requestId: string; date: string; reference: ReferenceRecord | null; paymentMethod: ReferenceRecord | null
  value: string; reason: string; notes: string; submitted?: Record<string, string | null>; receipt?: OperationRecord
}
