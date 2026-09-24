import { localDrafts } from '../../db/masterDataDrafts'
import type { ReferenceRecord } from '../masterdata/catalogs'

const prefix = 'local-customer-'
export function localCustomerId(requestId: string) { return prefix + requestId }
export function localCustomerRequestId(id: string) { return id.startsWith(prefix) ? id.slice(prefix.length) : null }

export async function saveLocalCustomer(userId: string, requestId: string, name: string, phone: string): Promise<ReferenceRecord> {
  const customer: ReferenceRecord = { id: localCustomerId(requestId), name, phone, active: true, version: 0 }
  const key = userId + ':customers'
  await localDrafts.transaction('rw', localDrafts.references, async () => {
    const cached = await localDrafts.references.get(key)
    const records = [...(cached?.records ?? []).filter(record => record.id !== customer.id), customer]
    await localDrafts.references.put({ key, userId, kind: 'customers', records, updatedAt: cached?.updatedAt ?? 0 })
  })
  return customer
}

export function normalizedPhone(phone?: string) {
  return (phone?.match(/\p{Decimal_Number}/gu) ?? []).map(digit => {
    const code = digit.codePointAt(0) ?? 0
    if (code >= 0x660 && code <= 0x669) return String(code - 0x660)
    if (code >= 0x6f0 && code <= 0x6f9) return String(code - 0x6f0)
    return digit.normalize('NFKC')
  }).join('')
}
