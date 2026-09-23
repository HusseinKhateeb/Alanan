import { useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { useAuth } from '../../auth/AuthProvider'
import { api, ApiError } from '../../api/client'
import { catalogs, canRead, initialInput, payload, type Catalog, type ReferencePage, type ReferenceRecord } from './catalogs'
import { ReferenceForm } from './ReferenceForm'
import { useDebounced } from './useDebounced'

export function MasterDataPage() {
  const { catalog } = useParams()
  if (!catalogs.includes(catalog as Catalog)) return <MissingCatalog />
  return <CatalogPage key={catalog} catalog={catalog as Catalog} />
}
function MissingCatalog() {
  const { t } = useTranslation()
  return <main><h1>{t('notFound')}</h1><Link to="/">{t('home')}</Link></main>
}
function CatalogPage({ catalog }: { catalog: Catalog }) {
  const { t } = useTranslation()
  const { user } = useAuth()
  const queryClient = useQueryClient()
  const [query, setQuery] = useState('')
  const debounced = useDebounced(query)
  const [page, setPage] = useState(0)
  const [activeOnly, setActiveOnly] = useState(false)
  const [editor, setEditor] = useState<ReferenceRecord | 'new' | null>(null)
  const [saved, setSaved] = useState(false)
  const [archivePending, setArchivePending] = useState<string | null>(null)
  const [archiveError, setArchiveError] = useState<ApiError | null>(null)
  const allowed = canRead(catalog, user!.permissions)
  const manage = user!.permissions.includes('SETTINGS_MANAGE')
  const records = useQuery({ queryKey: ['master', user!.id, catalog, debounced, page, activeOnly],
    queryFn: () => api<ReferencePage>(`/${catalog}?q=${encodeURIComponent(debounced)}&page=${page}&activeOnly=${activeOnly}`),
    enabled: allowed, retry: false })
  async function setArchived(record: ReferenceRecord) {
    if (archivePending || (record.active && !window.confirm(t('master.archiveConfirm', { name: record.name })))) return
    setArchivePending(record.id); setArchiveError(null); setSaved(false)
    try {
      const input = { ...initialInput(record), active: !record.active }
      await api(`/${catalog}/${record.id}`, { method: 'PUT', body: JSON.stringify(payload(catalog, input)) })
      void queryClient.invalidateQueries({ queryKey: ['master'] })
      void queryClient.invalidateQueries({ queryKey: ['purchase-reference'] })
      setSaved(true)
    } catch (failure) { setArchiveError(failure instanceof ApiError ? failure : new ApiError('SERVER_ERROR')) }
    finally { setArchivePending(null) }
  }
  if (!allowed) return <main><p role="alert">{t('errors.FORBIDDEN')}</p><Link to="/">{t('home')}</Link></main>
  return <main>
    <Link to="/">{t('home')}</Link>
    <nav className="catalog-nav" aria-label={t('master.title')}>{catalogs.filter(kind => canRead(kind, user!.permissions)).map(kind =>
      <Link key={kind} to={'/master/' + kind} aria-current={kind === catalog ? 'page' : undefined}>{t('master.' + kind)}</Link>)}</nav>
    <div className="catalog-heading"><h1>{t('master.' + catalog)}</h1>
      {manage && <button onClick={() => { setSaved(false); setEditor('new') }}>{t('master.add')}</button>}</div>
    <div className="catalog-filters"><label htmlFor="catalog-search">{t('master.search')}</label>
      <input id="catalog-search" type="search" value={query} maxLength={200} onChange={event => { setQuery(event.target.value); setPage(0) }} />
      <label className="checkbox"><input type="checkbox" checked={activeOnly} onChange={event => { setActiveOnly(event.target.checked); setPage(0) }} />{t('master.activeOnly')}</label></div>
    {saved && <p role="status">{t('master.saved')}</p>}
    {archiveError && <p role="alert" className="error">{t('errors.' + archiveError.code, { defaultValue: t('errors.SERVER_ERROR') })}</p>}
    {editor && manage && <ReferenceForm key={editor === 'new' ? 'new' : editor.id} catalog={catalog}
      record={editor === 'new' ? undefined : editor} onClose={() => { setEditor(null); void queryClient.invalidateQueries({ queryKey: ['master'] }) }} onSelect={setEditor}
      onSaved={() => { setEditor(null); setSaved(true); void queryClient.invalidateQueries({ queryKey: ['master'] }); void queryClient.invalidateQueries({ queryKey: ['customer-phone'] }) }} />}
    {records.isPending && <p role="status">{t('master.loading')}</p>}
    {records.error && <p role="alert">{t('errors.' + (records.error instanceof ApiError ? records.error.code : 'SERVER_ERROR'), { defaultValue: t('errors.SERVER_ERROR') })}</p>}
    {records.data?.items.length === 0 && <p>{t('master.empty')}</p>}
    <div className="reference-list">{records.data?.items.map(record => <article key={record.id}>
      <div><strong>{record.is_walk_in ? t('master.walkIn') : record.name}</strong>
        {record.code && <span>{record.code}</span>}
        {record.phone && <span dir="ltr">{record.phone}</span>}
        {(record.default_price || record.default_selling_price) && <span>{record.default_price ?? record.default_selling_price} ₪ {catalog === 'products' ? t(record.tracking_type === 'WEIGHT' ? 'master.perKg' : 'master.perPiece') : ''}</span>}
        <small>{t(record.active ? 'auth.active' : 'auth.inactive')}{record.is_default ? ' · ' + t('master.isDefault') : ''}</small>
      </div>
      {manage && !record.is_walk_in && <div className="record-actions"><button onClick={() => { setSaved(false); setEditor(record) }}>{t('master.edit')}</button>
        <button className={record.active ? 'danger-button' : ''} disabled={!!archivePending || (catalog === 'payment-methods' && !!record.is_default)} onClick={() => void setArchived(record)}>
          {t(record.active ? 'master.archive' : 'master.restore')}</button></div>}
    </article>)}</div>
    <div className="pagination"><button disabled={page === 0 || records.isFetching} onClick={() => setPage(page - 1)}>{t('auth.previous')}</button>
      <button disabled={!records.data?.hasMore || records.isFetching} onClick={() => setPage(page + 1)}>{t('auth.next')}</button></div>
  </main>
}
