import { useState } from 'react'
import { Link } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { useQuery } from '@tanstack/react-query'
import { useAuth } from '../../auth/AuthProvider'
import { api, ApiError } from '../../api/client'
import type { Page } from '../purchases/types'
interface Entry { id: number; entity_type: string; entity_id: string; action: string; user_id: string | null; actor_name: string | null; created_at: string; old_values: string | null; new_values: string | null }
function pretty(value: string) { try { return JSON.stringify(JSON.parse(value), null, 2) } catch { return value } }
export function AuditPage() {
  const { user } = useAuth()
  const { t } = useTranslation()
  const [page, setPage] = useState(0), [type, setType] = useState(''), [from, setFrom] = useState(''), [to, setTo] = useState('')
  const allowed = !!user?.permissions.includes('AUDIT_VIEW')
  const data = useQuery({ queryKey: ['audit', user?.id, page, type, from, to], enabled: allowed, retry: false,
    queryFn: () => { const p=new URLSearchParams({ page: String(page) }); if (type) p.set('type', type); if (from) p.set('from', from); if (to) p.set('to', to); return api<Page<Entry>>('/audit?' + p) } })
  if (!allowed) return <main><p role="alert">{t('errors.FORBIDDEN')}</p></main>
  return <main><Link to="/">{t('home')}</Link><h1>{t('audit.title')}</h1>
    <fieldset className="purchase-header-fields"><legend>{t('operations.filters')}</legend>
      <label>{t('audit.entity')}<select value={type} onChange={event => { setType(event.target.value); setPage(0) }}>
        <option value="">{t('audit.all')}</option>{['PURCHASE','SALE','SUPPLIER_PAYMENT','CUSTOMER_PAYMENT','EXPENSE','WASTE','AUTH','PRODUCTS','CUSTOMERS','SUPPLIERS','CONTAINERS','PAYMENTS','EXPENSES'].map(value => <option key={value} value={value}>{t('audit.' + value)}</option>)}
      </select></label>
      <label>{t('operations.from')}<input type="date" value={from} onChange={event => { setFrom(event.target.value); setPage(0) }} /></label>
      <label>{t('operations.to')}<input type="date" value={to} onChange={event => { setTo(event.target.value); setPage(0) }} /></label>
    </fieldset>
    {data.isPending && <p role="status">{t('master.loading')}</p>}
    {data.error && <p role="alert">{t('errors.' + (data.error instanceof ApiError ? data.error.code : 'SERVER_ERROR'))}</p>}
    {data.data?.items.length === 0 && <p>{t('master.empty')}</p>}
    <div className="reference-list">{data.data?.items.map(event => <article key={event.entity_type + event.id}><div>
      <strong>{t('audit.' + event.entity_type, { defaultValue: event.entity_type })} · {event.action}</strong>
      <span>{new Date(event.created_at).toLocaleString()} · {event.actor_name ?? event.user_id ?? t('audit.system')}</span><small>{event.entity_id}</small>
      {(event.old_values || event.new_values) && <details><summary>{t('audit.changes')}</summary>
        {event.old_values && <><h3>{t('audit.before')}</h3><pre>{pretty(event.old_values)}</pre></>}
        {event.new_values && <><h3>{t('audit.after')}</h3><pre>{pretty(event.new_values)}</pre></>}
      </details>}
    </div></article>)}</div>
    <div className="pagination"><button disabled={page === 0 || data.isFetching} onClick={() => setPage(page - 1)}>{t('auth.previous')}</button><button disabled={!data.data?.hasMore || data.isFetching} onClick={() => setPage(page + 1)}>{t('auth.next')}</button></div>
  </main>
}
