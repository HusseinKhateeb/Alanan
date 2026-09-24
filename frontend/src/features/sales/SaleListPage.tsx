import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Link, useSearchParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { api, ApiError } from '../../api/client'
import { useAuth } from '../../auth/AuthProvider'
import { useDebounced } from '../masterdata/useDebounced'
import type { Page } from '../purchases/types'
import type { SaleSummary } from './types'

export function SaleListPage() {
  const { user } = useAuth()
  const [params] = useSearchParams()
  const customer = params.get('customerId')
  return <SaleList key={customer} customer={customer} allowed={!!user?.permissions.includes('SALES_VIEW')} />
}
function SaleList({ customer, allowed }: { customer: string | null; allowed: boolean }) {
  const { t } = useTranslation()
  const { user } = useAuth()
  const [page, setPage] = useState(0)
  const [search, setSearch] = useState('')
  const query = useDebounced(search)
  const result = useQuery({ queryKey: ['sales', user!.id, customer, query, page],
    queryFn: () => api<Page<SaleSummary>>('/sales?page=' + page + (customer ? '&customerId=' + encodeURIComponent(customer) : '') + '&q=' + encodeURIComponent(query)), enabled: allowed, retry: false })
  if (!allowed) return <main><p role="alert">{t('errors.FORBIDDEN')}</p></main>
  return <main><Link to="/">{t('home')}</Link><h1>{t('sales.list')}</h1>
    {user?.permissions.includes('SALES_CREATE') && <Link to="/sales/new">{t('sales.new')}</Link>}
    {customer && <p><Link to="/sales">{t('sales.all')}</Link></p>}
    <div className="catalog-filters"><label htmlFor="sale-search">{t('sales.search')}</label><input id="sale-search" type="search" maxLength={100} value={search}
      onChange={event => { setSearch(event.target.value); setPage(0) }} /></div>
    {result.isPending && <p role="status">{t('master.loading')}</p>}
    {result.error && <p role="alert">{t('errors.' + (result.error instanceof ApiError ? result.error.code : 'SERVER_ERROR'))}</p>}
    {result.data?.items.length === 0 && <p>{t('master.empty')}</p>}
    <div className="reference-list">{result.data?.items.map(invoice => <article key={invoice.id}><div>
      <Link to={'/sales/' + invoice.id}><strong>{invoice.invoice_number}</strong></Link>
      <span>{invoice.customer_name} · {invoice.invoice_date}</span>
      <span>{t('purchase.total')}: {invoice.total} ₪</span>
      <span>{t('purchase.outstanding')}: {invoice.outstanding} ₪</span>
      <small>{t('purchase.' + invoice.status)}</small>
    </div></article>)}</div>
    <div className="pagination"><button disabled={page === 0 || result.isFetching} onClick={() => setPage(page - 1)}>{t('auth.previous')}</button>
      <button disabled={!result.data?.hasMore || result.isFetching} onClick={() => setPage(page + 1)}>{t('auth.next')}</button></div>
  </main>
}
