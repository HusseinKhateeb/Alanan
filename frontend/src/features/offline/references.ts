import { apiForUser, ApiError } from '../../api/client'
import { localDrafts } from '../../db/masterDataDrafts'
import type { ReferencePage, ReferenceRecord } from '../masterdata/catalogs'
export const offlineKinds = ['products', 'customers', 'container-types', 'payment-methods']
export async function refreshReferences(userId: string) {
  for (const kind of offlineKinds) {
    const records: ReferenceRecord[] = []
    for (let page = 0; ; page++) {
      if (page >= 200) throw new ApiError('CACHE_LIMIT')
      const result = await apiForUser<ReferencePage>(userId, '/' + kind + '?activeOnly=true&page=' + page)
      records.push(...result.items)
      if (!result.hasMore) break
    }
    await localDrafts.references.put({ key: userId + ':' + kind, userId, kind, records, updatedAt: Date.now() })
  }
}
export async function cachedReferencePage(userId: string, kind: string, query: string, page: number): Promise<ReferencePage & { cached: true; updatedAt: number }> {
  const cached = await localDrafts.references.get(userId + ':' + kind)
  if (!cached) throw new ApiError('OFFLINE_REFERENCES_MISSING')
  const search = query.toLocaleLowerCase()
  const records = cached.records.filter(item => [item.name, item.code, item.phone].some(value => value?.toLocaleLowerCase().includes(search)))
  return { items: records.slice(page * 50, (page + 1) * 50), hasMore: records.length > (page + 1) * 50, cached: true, updatedAt: cached.updatedAt }
}
