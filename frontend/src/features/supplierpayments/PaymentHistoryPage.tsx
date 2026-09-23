import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Link, useParams, useSearchParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { api, ApiError } from '../../api/client'
import { useAuth } from '../../auth/AuthProvider'
import type { Page } from '../purchases/types'
import type { PaymentDetail, PaymentSummary } from './types'
import { CancelControl } from '../cancellations/CancelControl'

export function PaymentHistoryPage() {
  const [params] = useSearchParams()
  return <History key={params.get('supplierId')} supplier={params.get('supplierId')} />
}
function History({ supplier }: { supplier: string | null }) {
  const { t } = useTranslation()
  const { user } = useAuth()
  const [page, setPage] = useState(0)
  const allowed = !!user?.permissions.includes('PURCHASES_VIEW')
  const result = useQuery({ queryKey: ['supplier-payments', user?.id, supplier, page], enabled: allowed, retry: false,
    queryFn: () => api<Page<PaymentSummary>>('/supplier-payments?page=' + page + (supplier ? '&supplierId=' + encodeURIComponent(supplier) : '')) })
  if (!allowed) return <main><p role="alert">{t('errors.FORBIDDEN')}</p></main>
  return <main><Link to="/">{t('home')}</Link><h1>{t('payments.history')}</h1>
    {user?.permissions.includes('SUPPLIER_PAYMENTS_CREATE') && <Link to="/supplier-payments/new">{t('payments.new')}</Link>}
    {supplier && <p><Link to="/supplier-payments">{t('payments.all')}</Link></p>}
    {result.isPending && <p role="status">{t('master.loading')}</p>}
    {result.error && <p role="alert">{t('errors.' + (result.error instanceof ApiError ? result.error.code : 'SERVER_ERROR'))}</p>}
    {result.data?.items.length === 0 && <p>{t('master.empty')}</p>}
    <div className="reference-list">{result.data?.items.map(payment => <article key={payment.id}><div>
      <Link to={'/supplier-payments/' + payment.id}>{payment.supplier_name} · {payment.amount} ₪</Link>
      <span>{payment.payment_date} · {payment.payment_method_name}</span><small>{t('payments.' + payment.allocation_mode)}{payment.cancelled ? ' · ' + t('cancellation.cancelled') : ''}</small>
    </div></article>)}</div>
    <div className="pagination"><button disabled={page === 0 || result.isFetching} onClick={() => setPage(page - 1)}>{t('auth.previous')}</button>
      <button disabled={!result.data?.hasMore || result.isFetching} onClick={() => setPage(page + 1)}>{t('auth.next')}</button></div>
  </main>
}
export function PaymentDetailPage() {
  const { id } = useParams()
  const { t } = useTranslation()
  const { user } = useAuth()
  const allowed = !!user?.permissions.includes('PURCHASES_VIEW')
  const result = useQuery({ queryKey: ['supplier-payments', user?.id, id], enabled: allowed, retry: false,
    queryFn: () => api<PaymentDetail>('/supplier-payments/' + id) })
  if (!allowed) return <main><p role="alert">{t('errors.FORBIDDEN')}</p></main>
  const payment = result.data
  return <main><Link to="/supplier-payments">{t('payments.history')}</Link><h1>{t('payments.view')}</h1>
    {result.isPending && <p role="status">{t('master.loading')}</p>}
    {result.error && <p role="alert">{t('errors.' + (result.error instanceof ApiError ? result.error.code : 'SERVER_ERROR'))}</p>}
    {payment && <><h2>{payment.supplier_name}</h2><p>{payment.payment_date} · {payment.amount} ₪ · {payment.payment_method_name}</p>
      <p>{t('payments.' + payment.allocation_mode)}</p><p>{payment.notes}</p><h2>{t('payments.selectedAllocations')}</h2>
      <ul>{payment.allocations.map(item => <li key={item.invoice_id}><Link to={'/purchases/' + item.invoice_id}>{item.invoice_number}</Link>: {item.amount} ₪</li>)}</ul></>}
    {payment && user?.permissions.includes('SUPPLIER_PAYMENTS_CREATE') && user.permissions.includes('AUDIT_VIEW') && <CancelControl endpoint={'/supplier-payments/' + payment.id} kind="supplierPayment" cancelled={payment.cancellation?.reason} />}
    {payment?.cancellation && !user?.permissions.includes('AUDIT_VIEW') && <p>{t('cancellation.cancelled')}</p>}
  </main>
}
