import type { ReferenceRecord } from '../masterdata/catalogs'
import type { SaleRequest } from '../sales/types'

export interface OrderSummary {
  id: string; order_number: string; customer_name: string; order_date: string; total: string
  planned_paid: string; status: 'OPEN' | 'DELIVERED'; sale_id: string | null; version: number
}
export interface OrderDetail extends OrderSummary {
  customer_id: string; notes: string | null; request: SaleRequest; references: ReferenceRecord[]
}
