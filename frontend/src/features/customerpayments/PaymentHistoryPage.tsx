import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Link, useParams, useSearchParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { api, ApiError } from '../../api/client'
import { useAuth } from '../../auth/AuthProvider'
import type { Page } from '../purchases/types'
import type { PaymentDetail, PaymentSummary } from './types'

export function PaymentHistoryPage() {
  const [params] = useSearchParams()
  return <History key={params.get('customerId')} customer={params.get('customerId')} />
}
function History({ customer }: { customer: string | null }) {
  const { t } = useTranslation()
  const { user } = useAuth()
  const [page, setPage] = useState(0)
  const allowed = !!user?.permissions.includes('SALES_VIEW')
  const result = useQuery({ queryKey: ['customer-payments', user?.id, customer, page], enabled: allowed, retry: false,
    queryFn: () => api<Page<PaymentSummary>>('/customer-payments?page=' + page + (customer ? '&customerId=' + encodeURIComponent(customer) : '')) })
  if (!allowed) return <main><p role="alert">{t('errors.FORBIDDEN')}</p></main>
  return <main><Link to="/">{t('home')}</Link><h1>{t('customerPayments.history')}</h1>
    {user?.permissions.includes('CUSTOMER_PAYMENTS_CREATE') && <Link to="/customer-payments/new">{t('customerPayments.new')}</Link>}
    {customer && <p><Link to="/customer-payments">{t('customerPayments.all')}</Link></p>}
    {result.isPending && <p role="status">{t('master.loading')}</p>}
    {result.error && <p role="alert">{t('errors.' + (result.error instanceof ApiError ? result.error.code : 'SERVER_ERROR'))}</p>}
    {result.data?.items.length === 0 && <p>{t('master.empty')}</p>}
    <div className="reference-list">{result.data?.items.map(payment => <article key={payment.id}><div>
      <Link to={'/customer-payments/' + payment.id}>{payment.customer_name} · {payment.amount} ₪</Link>
      <span>{payment.payment_date} · {payment.payment_method_name}</span><small>{t('customerPayments.' + payment.allocation_mode)}</small>
    </div></article>)}</div>
    <div className="pagination"><button disabled={page === 0 || result.isFetching} onClick={() => setPage(page - 1)}>{t('auth.previous')}</button>
      <button disabled={!result.data?.hasMore || result.isFetching} onClick={() => setPage(page + 1)}>{t('auth.next')}</button></div>
  </main>
}
export function PaymentDetailPage() {
  const { id } = useParams()
  const { t } = useTranslation()
  const { user } = useAuth()
  const allowed = !!user?.permissions.includes('SALES_VIEW')
  const result = useQuery({ queryKey: ['customer-payments', user?.id, id], enabled: allowed, retry: false,
    queryFn: () => api<PaymentDetail>('/customer-payments/' + id) })
  if (!allowed) return <main><p role="alert">{t('errors.FORBIDDEN')}</p></main>
  const payment = result.data
  return <main><Link to="/customer-payments">{t('customerPayments.history')}</Link><h1>{t('customerPayments.view')}</h1>
    {result.isPending && <p role="status">{t('master.loading')}</p>}
    {result.error && <p role="alert">{t('errors.' + (result.error instanceof ApiError ? result.error.code : 'SERVER_ERROR'))}</p>}
    {payment && <><h2>{payment.customer_name}</h2><p>{payment.payment_date} · {payment.amount} ₪ · {payment.payment_method_name}</p>
      <p>{t('customerPayments.' + payment.allocation_mode)}</p><p>{payment.notes}</p><h2>{t('customerPayments.selectedAllocations')}</h2>
      <ul>{payment.allocations.map(item => <li key={item.invoice_id}><Link to={'/sales/' + item.invoice_id}>{item.invoice_number}</Link>: {item.amount} ₪</li>)}</ul></>}
  </main>
}
