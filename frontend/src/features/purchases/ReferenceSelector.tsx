import { useEffect, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { api, ApiError } from '../../api/client'
import { cachedReferencePage, offlineKinds } from '../offline/references'
import { useAuth } from '../../auth/AuthProvider'
import { useDebounced } from '../masterdata/useDebounced'
import type { ReferenceRecord, ReferencePage } from '../masterdata/catalogs'

interface Props { kind: 'products' | 'suppliers' | 'payment-methods' | 'customers' | 'container-types' | 'expense-categories'; id: string; value: ReferenceRecord | null; disabled: boolean; activeOnly?: boolean; excludeWalkIn?: boolean; quickPick?: boolean; autoFocus?: boolean; combined?: boolean; onChange: (value: ReferenceRecord | null) => void }
export function ReferenceSelector({ kind, id, value, disabled, activeOnly = true, excludeWalkIn = false, quickPick = false, autoFocus = false, combined = false, onChange }: Props) {
  const { t } = useTranslation()
  const { user } = useAuth()
  const [search, setSearch] = useState('')
  const [page, setPage] = useState(0)
  const [open, setOpen] = useState(false)
  const [highlight, setHighlight] = useState(0)
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
  const showSearch = kind !== 'payment-methods' || !!data.data?.hasMore
  useEffect(() => {
    if (kind === 'payment-methods' && !value && !disabled) {
      const defaultMethod = data.data?.items.find(item => item.is_default)
      if (defaultMethod) onChange(defaultMethod)
    }
  }, [kind, value, disabled, data.data, onChange])
  if (combined && kind === 'customers') {
    const visible = query === search ? options : []
    const choices = excludeWalkIn || search.trim() ? visible : [null, ...visible]
    function select(customer: ReferenceRecord | null) {
      onChange(customer); setSearch(''); setPage(0); setHighlight(0); setOpen(false)
    }
    return <div className="reference-selector customer-combobox">
      <label htmlFor={id}>{t('purchase.searchReference', { type: t('master.customers') })}</label>
      <input id={id} type="search" role="combobox" aria-autocomplete="list" aria-expanded={open && !disabled}
        aria-controls={id + '-options'} aria-activedescendant={open && choices[highlight] ? id + '-option-' + highlight : undefined}
        autoComplete="off" maxLength={200} disabled={disabled} autoFocus={autoFocus}
        placeholder={excludeWalkIn ? t('purchase.choose') : t('master.walkIn')}
        value={open ? search : value?.name ?? ''}
        onFocus={() => { setOpen(true); setSearch(''); setPage(0); setHighlight(0) }}
        onBlur={() => setOpen(false)}
        onChange={event => { setSearch(event.target.value); setPage(0); setHighlight(0); if (value) onChange(null) }}
        onKeyDown={event => {
          if (event.key === 'ArrowDown') { event.preventDefault(); setOpen(true); if (choices.length) setHighlight(index => Math.min(index + 1, choices.length - 1)) }
          if (event.key === 'ArrowUp') { event.preventDefault(); setHighlight(index => Math.max(index - 1, 0)) }
          if (event.key === 'Enter' && open && choices.length) { event.preventDefault(); select(choices[highlight] ?? null) }
          if (event.key === 'Escape') { event.preventDefault(); setOpen(false); event.currentTarget.blur() }
        }} />
      {open && !disabled && <div id={id + '-options'} role="listbox" className="customer-options">
        {choices.map((customer, index) => <button type="button" role="option" id={id + '-option-' + index}
          aria-selected={highlight === index} key={customer?.id ?? 'walk-in'}
          onMouseDown={event => event.preventDefault()} onClick={() => select(customer)}>
          <span>{customer?.name ?? t('master.walkIn')}</span>{customer?.phone && <small>{customer.phone}</small>}
        </button>)}
        {!choices.length && !data.isFetching && <p>{t('master.empty')}</p>}
      </div>}
      {data.data?.cached && <p role="status">{t('offline.cached', { date: new Date(data.data.updatedAt!).toLocaleString() })}</p>}
      {data.error && <div role="alert">{t('errors.NETWORK_ERROR')} <button type="button" onClick={() => void data.refetch()}>{t('retry')}</button></div>}
      {open && (page > 0 || data.data?.hasMore) && <div className="reference-paging">
        <button type="button" disabled={disabled || page === 0 || data.isFetching} onMouseDown={event => event.preventDefault()} onClick={() => { setPage(page - 1); setHighlight(0) }}>{t('auth.previous')}</button>
        <button type="button" disabled={disabled || !data.data?.hasMore || data.isFetching} onMouseDown={event => event.preventDefault()} onClick={() => { setPage(page + 1); setHighlight(0) }}>{t('auth.next')}</button>
      </div>}
    </div>
  }
  return <div className="reference-selector">
    {data.data?.cached && <p role="status">{t('offline.cached', { date: new Date(data.data.updatedAt!).toLocaleString() })}</p>}
    {showSearch && <><label htmlFor={id + '-search'}>{t('purchase.searchReference', { type: t('master.' + kind) })}</label>
      <input id={id + '-search'} value={search} disabled={disabled} type="search" maxLength={200} autoFocus={autoFocus} autoComplete="off"
        onChange={event => { setSearch(event.target.value); setPage(0) }} /></>}
    {quickPick && !disabled && options.length > 0 && <div className="reference-quick-picks" aria-label={t('purchase.searchReference', { type: t('master.' + kind) })}>
      {options.slice(0, 8).map(option => <button type="button" key={option.id} aria-pressed={value?.id === option.id}
        onClick={() => { onChange(option); setSearch(''); setPage(0) }}>{option.name}{option.code && <small>{option.code}</small>}</button>)}
    </div>}
    <label htmlFor={id}>{t('master.' + kind)}</label>
    <select id={id} disabled={disabled} required={excludeWalkIn || (kind !== 'payment-methods' && kind !== 'customers')} value={value?.id ?? ''}
      onChange={event => onChange(options.find(option => option.id === event.target.value) ?? (event.target.value === value?.id ? value : null))}>
      <option value="">{t(kind === 'payment-methods' ? 'purchase.defaultPayment' : kind === 'customers' && !excludeWalkIn ? 'master.walkIn' : 'purchase.choose')}</option>
      {value && !options.some(option => option.id === value.id) && <option value={value.id}>{value.name}</option>}
      {options.map(option => <option key={option.id} value={option.id}>{option.name}{option.code ? ' · ' + option.code : ''}{!option.active ? ' · ' + t('auth.inactive') : ''}</option>)}
    </select>
    {data.error && <div role="alert">{t('errors.NETWORK_ERROR')} <button type="button" onClick={() => void data.refetch()}>{t('retry')}</button></div>}
    {(page > 0 || data.data?.hasMore) && <div className="reference-paging">
      <button type="button" disabled={disabled || page === 0 || data.isFetching} onClick={() => setPage(page - 1)}>{t('auth.previous')}</button>
      <button type="button" disabled={disabled || !data.data?.hasMore || data.isFetching} onClick={() => setPage(page + 1)}>{t('auth.next')}</button>
    </div>}
  </div>
}
