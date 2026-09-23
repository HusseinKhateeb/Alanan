import { useQuery } from '@tanstack/react-query'
import { Link, useParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { api, ApiError } from '../../api/client'
import { useAuth } from '../../auth/AuthProvider'
import type { SaleDetail } from './types'
export function SaleDetailPage() {
  const { id } = useParams()
  const { t } = useTranslation()
  const { user } = useAuth()
  const allowed = !!user?.permissions.includes('SALES_VIEW')
  const result = useQuery({ queryKey: ['sales', user?.id, id], enabled: allowed, retry: false, queryFn: () => api<SaleDetail>('/sales/' + id) })
  if (!allowed) return <main><p role="alert">{t('errors.FORBIDDEN')}</p></main>
  const sale = result.data
  return <main><Link to="/sales">{t('sales.list')}</Link><h1>{sale?.invoice_number ?? t('purchase.view')}</h1>
    {result.isPending && <p role="status">{t('master.loading')}</p>}
    {result.error && <p role="alert">{t('errors.' + (result.error instanceof ApiError ? result.error.code : 'SERVER_ERROR'))}</p>}
    {sale && <><p>{sale.customer_name} · {sale.invoice_date}</p><p>{t('purchase.' + sale.status)}</p>
      <p>{t('customerPayments.initialPayment')}: {sale.paid_at_sale} ₪ · {sale.payment_method_name}</p>
      {sale.negative_stock && <p role="alert">{t('sales.negativeStock')}</p>}
      <div className="reference-list">{sale.items.map((item, index) => <article key={index}><div><strong>{item.item_name}</strong>
        <span>{t('sales.' + item.selling_mode)} · {item.quantity} {t(item.item_type === 'WEIGHT_PRODUCT' ? 'sales.kg' : 'sales.pieces')} × {item.unit_price} ₪</span>
        {item.requested_amount && <span>{t('sales.requestedAmount')}: {item.requested_amount} ₪</span>}
        <span>{t('purchase.lineTotal')}: {item.line_total} ₪</span>
        {item.item_type === 'SPICE_CONTAINER' && <small>{t('sales.containerHelp')}</small>}
        {user?.permissions.includes('COST_VIEW') && <small>{t('sales.cost')}: {item.cost_total === null || item.cost_total === undefined ? t('purchase.unknownCost') : item.cost_total + ' ₪'}</small>}
      </div></article>)}</div>
      <p>{t('purchase.total')}: {sale.total} ₪ · {t('purchase.paid')}: {sale.paid} ₪ · {t('purchase.outstanding')}: {sale.outstanding} ₪</p>
      <h2>{t('customerPayments.history')}</h2>
      <ul>{sale.payments.map(payment => <li key={payment.id}><Link to={'/customer-payments/' + payment.id}>{payment.payment_date} · {payment.amount} ₪ · {payment.payment_method_name}</Link></li>)}</ul>
      <p>{sale.notes}</p></>}
  </main>
}
