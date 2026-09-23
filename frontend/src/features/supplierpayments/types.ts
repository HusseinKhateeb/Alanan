import type { ReferenceRecord } from '../masterdata/catalogs'
export interface PaymentRequest {
  requestId: string; supplierId: string; paymentDate: string; amount: string; paymentMethodId: string | null
  notes: string; mode: 'AUTO' | 'MANUAL'; allocations: { invoiceId: string; amount: string }[]
}
export interface PaymentReceipt {
  id: string; supplierName: string; amount: string; paymentMethodName: string
  allocations: { invoiceId: string; invoiceNumber: string; amount: string }[]
}
export interface DraftAllocation { invoiceId: string; invoiceNumber: string; amount: string }
export interface PaymentDraft {
  requestId: string; supplier: ReferenceRecord | null; paymentDate: string; amount: string
  paymentMethod: ReferenceRecord | null; notes: string; mode: 'AUTO' | 'MANUAL'; allocations: DraftAllocation[]
  submitted?: PaymentRequest; receipt?: PaymentReceipt
}
export interface OpenInvoice { id: string; invoice_number: string; invoice_date: string; total: string; outstanding: string }
export interface PaymentSummary { id: string; supplier_id: string; supplier_name: string; amount: string; payment_date: string; payment_method_name: string; allocation_mode: string; cancelled: boolean }
export interface PaymentDetail extends PaymentSummary { notes: string | null; allocations: { invoice_id: string; invoice_number: string; amount: string }[]; cancellation: { reason: string; created_at: string } | null }
