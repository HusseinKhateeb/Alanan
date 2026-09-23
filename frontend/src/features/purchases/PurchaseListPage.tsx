import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Link, useSearchParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { api, ApiError } from '../../api/client'
import { useAuth } from '../../auth/AuthProvider'
import type { Page, PurchaseSummary } from './types'

export function PurchaseListPage() {
  const { user } = useAuth()
  const [params] = useSearchParams()
  const supplier = params.get('supplierId')
  return <PurchaseList key={supplier} supplier={supplier} allowed={!!user?.permissions.includes('PURCHASES_VIEW')} />
}
function PurchaseList({ supplier, allowed }: { supplier: string | null; allowed: boolean }) {
  const { t } = useTranslation()
  const { user } = useAuth()
  const [page, setPage] = useState(0)
  const result = useQuery({ queryKey: ['purchases', user!.id, supplier, page],
    queryFn: () => api<Page<PurchaseSummary>>('/purchases?page=' + page + (supplier ? '&supplierId=' + encodeURIComponent(supplier) : '')), enabled: allowed, retry: false })
  if (!allowed) return <main><p role="alert">{t('errors.FORBIDDEN')}</p></main>
  return <main><Link to="/">{t('home')}</Link><h1>{t('purchase.list')}</h1>
    {user?.permissions.includes('PURCHASES_CREATE') && <Link to="/purchases/new">{t('purchase.new')}</Link>}
    {supplier && <p><Link to="/purchases">{t('purchase.all')}</Link></p>}
    {result.isPending && <p role="status">{t('master.loading')}</p>}
    {result.error && <p role="alert">{t('errors.' + (result.error instanceof ApiError ? result.error.code : 'SERVER_ERROR'))}</p>}
    {result.data?.items.length === 0 && <p>{t('master.empty')}</p>}
    <div className="reference-list">{result.data?.items.map(invoice => <article key={invoice.id}><div>
      <Link to={'/purchases/' + invoice.id}><strong>{invoice.invoice_number}</strong></Link>
      <span>{invoice.supplier_name} · {invoice.invoice_date}</span>
      <span>{t('purchase.total')}: {invoice.total} ₪</span>
      <span>{t('purchase.outstanding')}: {invoice.outstanding} ₪</span>
      <small>{t('purchase.' + invoice.status)}</small>
    </div></article>)}</div>
    <div className="pagination"><button disabled={page === 0 || result.isFetching} onClick={() => setPage(page - 1)}>{t('auth.previous')}</button>
      <button disabled={!result.data?.hasMore || result.isFetching} onClick={() => setPage(page + 1)}>{t('auth.next')}</button></div>
  </main>
}
