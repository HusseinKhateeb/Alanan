import type { ReferenceRecord } from '../masterdata/catalogs'
export type SaleMode = 'BY_WEIGHT' | 'BY_AMOUNT' | 'BY_PIECE'
export interface SaleLine {
  localId: string; kind: 'PRODUCT' | 'CONTAINER'; reference: ReferenceRecord | null
  mode: SaleMode; quantity: string; requestedAmount: string; unitPrice: string
}
export interface SaleRequest {
  requestId: string; customerId: string | null; invoiceDate: string; notes: string; paidNow: string; paymentMethodId: string | null
  items: { productId: string | null; containerTypeId: string | null; mode: SaleMode; quantity: string | null; requestedAmount: string | null; unitPrice: string }[]
}
export interface SaleReceipt { id: string; invoiceNumber: string; total: string; paid: string; outstanding: string; status: string; negativeStock: boolean }
export interface SaleDraft {
  requestId: string; customer: ReferenceRecord | null; invoiceDate: string; notes: string; paidNow: string
  paymentMethod: ReferenceRecord | null; items: SaleLine[]; submitted?: SaleRequest; receipt?: SaleReceipt
}
export interface SaleSummary { id: string; invoice_number: string; customer_id: string; customer_name: string; invoice_date: string; total: string; paid: string; outstanding: string; status: string; negative_stock: boolean }
export interface SaleDetail extends SaleSummary {
  paid_at_sale: string
  payments: { id: string; payment_date: string; payment_method_name: string; amount: string }[]
  notes: string | null; payment_method_name: string | null
  items: { item_name: string; item_type: string; selling_mode: SaleMode; quantity: string; unit_price: string; requested_amount: string | null; line_total: string; cost_at_sale?: string | null; cost_total?: string | null }[]
}
