import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { useAuth } from '../../auth/AuthProvider'
import { api, ApiError } from '../../api/client'
import type { Page } from '../purchases/types'
interface Balance { id: string; name: string; purchased: string; paid: string; outstanding: string }
export function SupplierBalancesPage() {
  const { user } = useAuth()
  const { t } = useTranslation()
  const [page, setPage] = useState(0)
  const allowed = !!user?.permissions.includes('PURCHASES_VIEW')
  const query = useQuery({ queryKey: ['supplier-balances', user!.id, page], queryFn: () => api<Page<Balance>>('/supplier-balances?page=' + page), enabled: allowed, retry: false })
  if (!allowed) return <main><p role="alert">{t('errors.FORBIDDEN')}</p></main>
  return <main><Link to="/">{t('home')}</Link><h1>{t('purchase.supplierBalances')}</h1>
    {query.isPending && <p role="status">{t('master.loading')}</p>}
    {query.error && <p role="alert">{t('errors.' + (query.error instanceof ApiError ? query.error.code : 'SERVER_ERROR'))}</p>}
    {query.data?.items.length === 0 && <p>{t('master.empty')}</p>}
    <div className="reference-list">{query.data?.items.map(supplier => <article key={supplier.id}><div>
      <strong>{supplier.name}</strong><span>{t('purchase.purchased')}: {supplier.purchased} ₪</span>
      <span>{t('purchase.paid')}: {supplier.paid} ₪</span><span>{t('purchase.outstanding')}: {supplier.outstanding} ₪</span>
      <Link to={'/purchases?supplierId=' + supplier.id}>{t('purchase.list')}</Link>
      <Link to={'/supplier-payments?supplierId=' + supplier.id}>{t('payments.history')}</Link>
    </div></article>)}</div>
    <div className="pagination"><button disabled={page === 0 || query.isFetching} onClick={() => setPage(page - 1)}>{t('auth.previous')}</button>
      <button disabled={!query.data?.hasMore || query.isFetching} onClick={() => setPage(page + 1)}>{t('auth.next')}</button></div>
  </main>
}
