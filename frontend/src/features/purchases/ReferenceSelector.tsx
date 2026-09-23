import { useEffect, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { api, ApiError } from '../../api/client'
import { cachedReferencePage, offlineKinds } from '../offline/references'
import { useAuth } from '../../auth/AuthProvider'
import { useDebounced } from '../masterdata/useDebounced'
import type { ReferenceRecord, ReferencePage } from '../masterdata/catalogs'

interface Props { kind: 'products' | 'suppliers' | 'payment-methods' | 'customers' | 'container-types' | 'expense-categories'; id: string; value: ReferenceRecord | null; disabled: boolean; activeOnly?: boolean; excludeWalkIn?: boolean; onChange: (value: ReferenceRecord | null) => void }
export function ReferenceSelector({ kind, id, value, disabled, activeOnly = true, excludeWalkIn = false, onChange }: Props) {
  const { t } = useTranslation()
  const { user } = useAuth()
  const [search, setSearch] = useState('')
  const [page, setPage] = useState(0)
  const query = useDebounced(search)
  const data = useQuery({ queryKey: ['purchase-reference', user!.id, kind, query, page, activeOnly], networkMode: 'always',
    queryFn: async (): Promise<ReferencePage & { cached?: boolean; updatedAt?: number }> => {
      if (navigator.onLine === false && activeOnly && offlineKinds.includes(kind)) return cachedReferencePage(user!.id, kind, query, page)
      try { return await api<ReferencePage>(`/${kind}?activeOnly=${activeOnly}&q=${encodeURIComponent(query)}&page=${page}`) }
      catch (failure) {
        if (activeOnly && offlineKinds.includes(kind) && failure instanceof ApiError && (failure.status === 0 || failure.status >= 500)) return cachedReferencePage(user!.id, kind, query, page)
        throw failure
      }
    }, retry: false })
  const options = (data.data?.items ?? []).filter(item => !excludeWalkIn || !item.is_walk_in)
  useEffect(() => {
    if (kind === 'payment-methods' && !value && !disabled) {
      const defaultMethod = data.data?.items.find(item => item.is_default)
      if (defaultMethod) onChange(defaultMethod)
    }
  }, [kind, value, disabled, data.data, onChange])
  return <div className="reference-selector">
    {data.data?.cached && <p role="status">{t('offline.cached', { date: new Date(data.data.updatedAt!).toLocaleString() })}</p>}
    <label htmlFor={id + '-search'}>{t('purchase.searchReference', { type: t('master.' + kind) })}</label>
    <input id={id + '-search'} value={search} disabled={disabled} type="search" maxLength={200}
      onChange={event => { setSearch(event.target.value); setPage(0) }} />
    <label htmlFor={id}>{t('master.' + kind)}</label>
    <select id={id} disabled={disabled} required={excludeWalkIn || (kind !== 'payment-methods' && kind !== 'customers')} value={value?.id ?? ''}
      onChange={event => onChange(options.find(option => option.id === event.target.value) ?? (event.target.value === value?.id ? value : null))}>
      <option value="">{t(kind === 'payment-methods' ? 'purchase.defaultPayment' : kind === 'customers' && !excludeWalkIn ? 'master.walkIn' : 'purchase.choose')}</option>
      {value && !options.some(option => option.id === value.id) && <option value={value.id}>{value.name}</option>}
      {options.map(option => <option key={option.id} value={option.id}>{option.name}{option.code ? ' · ' + option.code : ''}{!option.active ? ' · ' + t('auth.inactive') : ''}</option>)}
    </select>
    {data.error && <div role="alert">{t('errors.NETWORK_ERROR')} <button type="button" onClick={() => void data.refetch()}>{t('retry')}</button></div>}
    <div className="reference-paging">
      <button type="button" disabled={disabled || page === 0 || data.isFetching} onClick={() => setPage(page - 1)}>{t('auth.previous')}</button>
      <button type="button" disabled={disabled || !data.data?.hasMore || data.isFetching} onClick={() => setPage(page + 1)}>{t('auth.next')}</button>
    </div>
  </div>
}
