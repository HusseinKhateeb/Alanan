import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { useAuth } from '../../auth/AuthProvider'
import { api, ApiError } from '../../api/client'
import type { Page } from '../purchases/types'
interface Balance { id: string; name: string; sold: string; paid: string; outstanding: string }
export function CustomerBalancesPage() {
  const { user } = useAuth()
  const { t } = useTranslation()
  const [page, setPage] = useState(0)
  const allowed = !!user?.permissions.includes('SALES_VIEW')
  const query = useQuery({ queryKey: ['customer-balances', user?.id, page], queryFn: () => api<Page<Balance>>('/customer-balances?page=' + page), enabled: allowed, retry: false })
  if (!allowed) return <main><p role="alert">{t('errors.FORBIDDEN')}</p></main>
  return <main><Link to="/">{t('home')}</Link><h1>{t('sales.customerBalances')}</h1>
    {query.isPending && <p role="status">{t('master.loading')}</p>}
    {query.error && <p role="alert">{t('errors.' + (query.error instanceof ApiError ? query.error.code : 'SERVER_ERROR'))}</p>}
    {query.data?.items.length === 0 && <p>{t('master.empty')}</p>}
    <div className="reference-list">{query.data?.items.map(customer => <article key={customer.id}><div>
      <strong>{customer.name}</strong><span>{t('sales.sold')}: {customer.sold} ₪</span>
      <span>{t('purchase.paid')}: {customer.paid} ₪</span><span>{t('purchase.outstanding')}: {customer.outstanding} ₪</span>
      <Link to={'/sales?customerId=' + customer.id}>{t('sales.list')}</Link>
      <Link to={'/customer-payments?customerId=' + customer.id}>{t('customerPayments.history')}</Link>
      {user?.permissions.includes('CUSTOMER_PAYMENTS_CREATE') && <Link to={'/customer-payments/new?customerId=' + customer.id}>{t('customerPayments.new')}</Link>}
    </div></article>)}</div>
    <div className="pagination"><button disabled={page === 0 || query.isFetching} onClick={() => setPage(page - 1)}>{t('auth.previous')}</button>
      <button disabled={!query.data?.hasMore || query.isFetching} onClick={() => setPage(page + 1)}>{t('auth.next')}</button></div>
  </main>
}
