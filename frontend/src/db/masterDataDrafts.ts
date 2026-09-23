import type { PaymentDraft as CustomerPaymentDraft } from '../features/customerpayments/types'
import Dexie, { type Table } from 'dexie'
import type { ReferenceInput } from '../features/masterdata/catalogs'
import type { PurchaseDraft } from '../features/purchases/types'
import type { PaymentDraft } from '../features/supplierpayments/types'
import type { SaleDraft } from '../features/sales/types'

interface Draft { key: string; input: ReferenceInput; updatedAt: number }
class LocalDrafts extends Dexie {
  masterDataDrafts!: Table<Draft, string>
  purchaseDrafts!: Table<{ key: string; draft: PurchaseDraft; updatedAt: number }, string>
  supplierPaymentDrafts!: Table<{ key: string; draft: PaymentDraft; updatedAt: number }, string>
  saleDrafts!: Table<{ key: string; draft: SaleDraft; updatedAt: number }, string>
  customerPaymentDrafts!: Table<{ key: string; draft: CustomerPaymentDraft; updatedAt: number }, string>
  constructor() {
    super('alanan')
    this.version(1).stores({ masterDataDrafts: 'key, updatedAt' })
    this.version(2).stores({ masterDataDrafts: 'key, updatedAt', purchaseDrafts: 'key, updatedAt' })
    this.version(3).stores({ masterDataDrafts: 'key, updatedAt', purchaseDrafts: 'key, updatedAt', supplierPaymentDrafts: 'key, updatedAt' })
    this.version(5).stores({ masterDataDrafts: 'key, updatedAt', purchaseDrafts: 'key, updatedAt', supplierPaymentDrafts: 'key, updatedAt', saleDrafts: 'key, updatedAt', customerPaymentDrafts: 'key, updatedAt' })
    this.version(4).stores({ masterDataDrafts: 'key, updatedAt', purchaseDrafts: 'key, updatedAt', supplierPaymentDrafts: 'key, updatedAt', saleDrafts: 'key, updatedAt' })
  }
}
export const localDrafts = new LocalDrafts()
export function saveMasterDataDraft(key: string, input: ReferenceInput) {
  return localDrafts.masterDataDrafts.put({ key, input, updatedAt: Date.now() })
}
// Draft keys contain the authenticated user ID. Logout never removes unsaved work.
