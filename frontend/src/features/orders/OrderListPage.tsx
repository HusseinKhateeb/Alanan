import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { api, ApiError } from '../../api/client'
import { useAuth } from '../../auth/AuthProvider'
import { useDebounced } from '../masterdata/useDebounced'
import type { Page } from '../purchases/types'
import type { OrderSummary } from './types'

export function OrderListPage() {
  const { user, offline } = useAuth()
  const { t } = useTranslation()
  const [page, setPage] = useState(0)
  const [search, setSearch] = useState('')
  const query = useDebounced(search)
  const allowed = !!user?.permissions.includes('SALES_VIEW') && !offline
  const result = useQuery({ queryKey: ['orders', user?.id, page, query], queryFn: () => api<Page<OrderSummary>>('/orders?page=' + page + '&q=' + encodeURIComponent(query)), enabled: allowed, retry: false })
  if (!allowed) return <main><p role="alert">{t(offline ? 'orders.onlineRequired' : 'errors.FORBIDDEN')}</p></main>
  return <main><Link to="/">{t('home')}</Link><h1>{t('orders.list')}</h1>
    {user?.permissions.includes('SALES_CREATE') && <Link to="/orders/new">{t('orders.new')}</Link>}
    <p>{t('orders.notPosted')}</p>
    <div className="catalog-filters"><label htmlFor="order-search">{t('orders.search')}</label><input id="order-search" type="search" maxLength={100} value={search} onChange={event => { setSearch(event.target.value); setPage(0) }} /></div>
    {result.isPending && <p role="status">{t('master.loading')}</p>}
    {result.error && <p role="alert">{t('errors.' + (result.error instanceof ApiError ? result.error.code : 'SERVER_ERROR'))}</p>}
    {result.data?.items.length === 0 && <p>{t('master.empty')}</p>}
    <div className="reference-list">{result.data?.items.map(order => <article key={order.id}><div>
      <Link to={'/orders/' + order.id}><strong>{order.order_number}</strong></Link>
      <span>{order.customer_name} · {t('orders.deliveryDate')}: {order.order_date}</span>
      <span>{t('purchase.total')}: {order.total} ₪</span>
      <small>{t('orders.' + order.status)}</small>
    </div></article>)}</div>
    <div className="pagination"><button disabled={page === 0 || result.isFetching} onClick={() => setPage(page - 1)}>{t('auth.previous')}</button>
      <button disabled={!result.data?.hasMore || result.isFetching} onClick={() => setPage(page + 1)}>{t('auth.next')}</button></div>
  </main>
}
