import type { ReferenceRecord } from '../masterdata/catalogs'
export interface PaymentRequest {
  requestId: string; customerId: string; paymentDate: string; amount: string; paymentMethodId: string | null
  notes: string; mode: 'AUTO' | 'MANUAL'; allocations: { invoiceId: string; amount: string }[]
}
export interface PaymentReceipt {
  id: string; customerName: string; amount: string; paymentMethodName: string
  allocations: { invoiceId: string; invoiceNumber: string; amount: string }[]
}
export interface DraftAllocation { invoiceId: string; invoiceNumber: string; amount: string }
export interface PaymentDraft {
  requestId: string; customer: ReferenceRecord | null; paymentDate: string; amount: string
  paymentMethod: ReferenceRecord | null; notes: string; mode: 'AUTO' | 'MANUAL'; allocations: DraftAllocation[]
  submitted?: PaymentRequest; receipt?: PaymentReceipt
}
export interface OpenInvoice { id: string; invoice_number: string; invoice_date: string; total: string; outstanding: string }
export interface PaymentSummary { id: string; customer_id: string; customer_name: string; amount: string; payment_date: string; payment_method_name: string; allocation_mode: string }
export interface PaymentDetail extends PaymentSummary { notes: string | null; allocations: { invoice_id: string; invoice_number: string; amount: string }[] }
