import type { ReferenceRecord } from '../masterdata/catalogs'
import type { SaleDraft, SaleReceipt, SaleRequest } from '../sales/types'
export interface CachedReferences { key: string; userId: string; kind: string; records: ReferenceRecord[]; updatedAt: number }
export interface QueuedSale {
  key: string; userId: string; request: SaleRequest; draft: SaleDraft
  status: 'PENDING' | 'SYNCING' | 'SYNCED' | 'NEEDS_ATTENTION'
  createdAt: number; updatedAt: number; attempts: number; retryAt: number; leaseUntil: number
  error?: string; errorStatus?: number; receipt?: SaleReceipt
}
