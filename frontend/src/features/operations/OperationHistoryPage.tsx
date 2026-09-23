import { useState } from 'react'
import { Link } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { useQuery } from '@tanstack/react-query'
import { api, ApiError } from '../../api/client'
import { useAuth } from '../../auth/AuthProvider'
import { ReferenceSelector } from '../purchases/ReferenceSelector'
import type { ReferenceRecord } from '../masterdata/catalogs'
import type { Page } from '../purchases/types'
import type { OperationKind, OperationRecord } from './types'
import { RecordSummary } from './OperationEntryPage'
export function OperationHistoryPage({ kind }: { kind: OperationKind }) {
  const { user } = useAuth()
  return <History key={kind + user?.id} kind={kind} />
}
function History({ kind }: { kind: OperationKind }) {
  const { user } = useAuth()
  const { t } = useTranslation()
  const [page, setPage] = useState(0)
  const [from, setFrom] = useState(''), [to, setTo] = useState('')
  const [reference, setReference] = useState<ReferenceRecord | null>(null)
  const allowed = !!user?.permissions.includes(kind === 'expenses' ? 'EXPENSES_CREATE' : 'INVENTORY_ADJUST')
  const query = useQuery({ queryKey: [kind, user?.id, page, from, to, reference?.id], enabled: allowed, retry: false,
    queryFn: () => {
      const params = new URLSearchParams({ page: String(page) })
      if (from) params.set('from', from)
      if (to) params.set('to', to)
      if (reference) params.set(kind === 'expenses' ? 'categoryId' : 'productId', reference.id)
      return api<Page<OperationRecord>>('/' + kind + '?' + params.toString())
    } })
  if (!allowed) return <main><p role="alert">{t('errors.FORBIDDEN')}</p></main>
  return <main><Link to="/">{t('home')}</Link><h1>{t('operations.' + kind)}</h1><Link to={'/' + kind + '/new'}>{t('operations.new.' + kind)}</Link>
    <fieldset className="purchase-header-fields"><legend>{t('operations.filters')}</legend>
      <label>{t('operations.from')}<input type="date" value={from} onChange={event => { setFrom(event.target.value); setPage(0) }} /></label>
      <label>{t('operations.to')}<input type="date" value={to} onChange={event => { setTo(event.target.value); setPage(0) }} /></label>
      <ReferenceSelector kind={kind === 'expenses' ? 'expense-categories' : 'products'} id="history-reference" value={reference} disabled={false} activeOnly={false} onChange={value => { setReference(value); setPage(0) }} />
      <button onClick={() => { setFrom(''); setTo(''); setReference(null); setPage(0) }}>{t('operations.clear')}</button>
    </fieldset>
    {query.isPending && <p role="status">{t('master.loading')}</p>}
    {query.error && <p role="alert">{t('errors.' + (query.error instanceof ApiError ? query.error.code : 'SERVER_ERROR'))}</p>}
    {query.data?.items.length === 0 && <p>{t('master.empty')}</p>}
    <div className="reference-list">{query.data?.items.map(record => <article key={record.id}><div><RecordSummary record={record} kind={kind} /></div></article>)}</div>
    <div className="pagination"><button disabled={page === 0 || query.isFetching} onClick={() => setPage(page - 1)}>{t('auth.previous')}</button><button disabled={!query.data?.hasMore || query.isFetching} onClick={() => setPage(page + 1)}>{t('auth.next')}</button></div>
  </main>
}
