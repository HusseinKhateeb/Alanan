import type { ReferenceRecord } from '../masterdata/catalogs'
export interface PurchaseItem { localId: string; product: ReferenceRecord | null; quantity: string; unitCost: string }
export interface PurchaseRequest {
  requestId: string; supplierId: string; invoiceDate: string; supplierReference: string; notes: string
  paidNow: string; paymentMethodId: string | null
  items: { productId: string; quantity: string; unitCost: string }[]
}
export interface PurchaseReceipt { id: string; invoiceNumber: string; total: string; paid: string; outstanding: string; status: string }
export interface PurchaseDraft {
  requestId: string; supplier: ReferenceRecord | null; invoiceDate: string; supplierReference: string; notes: string
  paidNow: string; paymentMethod: ReferenceRecord | null; items: PurchaseItem[]
  submitted?: PurchaseRequest; receipt?: PurchaseReceipt
}
export interface PurchaseSummary { id: string; invoice_number: string; supplier_id: string; supplier_name: string; invoice_date: string; total: string; paid: string; outstanding: string; status: string }
export interface PurchaseDetail extends PurchaseSummary {
  supplier_reference: string | null; notes: string | null
  items: { product_id: string; product_name: string; tracking_type: string; quantity: string; unit_cost: string; line_total: string }[]
  payments: { id: string; payment_date: string; payment_method_name: string; amount: string }[]
}
export interface Page<T> { items: T[]; hasMore: boolean }
