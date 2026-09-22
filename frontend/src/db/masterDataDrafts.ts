import Dexie, { type Table } from 'dexie'
import type { ReferenceInput } from '../features/masterdata/catalogs'

interface Draft { key: string; input: ReferenceInput; updatedAt: number }
class LocalDrafts extends Dexie {
  masterDataDrafts!: Table<Draft, string>
  constructor() { super('alanan'); this.version(1).stores({ masterDataDrafts: 'key, updatedAt' }) }
}
export const localDrafts = new LocalDrafts()
export function saveMasterDataDraft(key: string, input: ReferenceInput) {
  return localDrafts.masterDataDrafts.put({ key, input, updatedAt: Date.now() })
}
// Draft keys contain the authenticated user ID. Logout never removes unsaved work.
