import { useQuery } from '@tanstack/react-query'
import { Link, useParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { api, ApiError } from '../../api/client'
import { useAuth } from '../../auth/AuthProvider'
import type { PurchaseDetail } from './types'
export function PurchaseDetailPage() {
  const { id } = useParams()
  const { user } = useAuth()
  const { t } = useTranslation()
  const allowed = !!user?.permissions.includes('PURCHASES_VIEW')
  const result = useQuery({ queryKey: ['purchases', user!.id, 'detail', id], queryFn: () => api<PurchaseDetail>('/purchases/' + id), enabled: allowed, retry: false })
  if (!allowed) return <main><p role="alert">{t('errors.FORBIDDEN')}</p></main>
  const invoice = result.data
  return <main><Link to="/purchases">{t('purchase.list')}</Link>
    {result.isPending && <p role="status">{t('master.loading')}</p>}
    {result.error && <p role="alert">{t('errors.' + (result.error instanceof ApiError ? result.error.code : 'SERVER_ERROR'))}</p>}
    {invoice && <>
      <h1>{invoice.invoice_number}</h1><p>{invoice.supplier_name} · {invoice.invoice_date} · {t('purchase.' + invoice.status)}</p>
      {invoice.supplier_reference && <p>{t('purchase.reference')}: {invoice.supplier_reference}</p>}
      <div className="reference-list">{invoice.items.map((item, index) => <article key={index}><div><strong>{item.product_name}</strong>
        <span>{item.quantity} {t(item.tracking_type === 'WEIGHT' ? 'master.WEIGHT' : 'master.PIECE')} × {item.unit_cost} ₪</span>
        <span>{item.line_total} ₪</span></div></article>)}</div>
      <p>{t('purchase.total')}: {invoice.total} ₪ · {t('purchase.paid')}: {invoice.paid} ₪ · {t('purchase.outstanding')}: {invoice.outstanding} ₪</p>
      <h2>{t('purchase.payments')}</h2>
      {invoice.payments.length === 0 && <p>{t('master.empty')}</p>}
      {invoice.payments.map(payment => <p key={payment.id}>{payment.payment_date} · {payment.payment_method_name} · {payment.amount} ₪</p>)}
      {invoice.notes && <p>{invoice.notes}</p>}
    </>}
  </main>
}
