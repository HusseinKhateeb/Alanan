import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { Link, useParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { api, ApiError, downloadPdf } from '../../api/client'
import { useAuth } from '../../auth/AuthProvider'
import type { SaleDetail } from './types'
import { ReferenceSelector } from '../purchases/ReferenceSelector'
import type { ReferenceRecord } from '../masterdata/catalogs'
export function SaleDetailPage() {
  const { id } = useParams()
  const { t } = useTranslation()
  const { user } = useAuth()
  const client = useQueryClient()
  const [reason, setReason] = useState('')
  const [refundConfirmed, setRefundConfirmed] = useState(false)
  const [refundMethod, setRefundMethod] = useState<ReferenceRecord | null>(null)
  const [cancelling, setCancelling] = useState(false)
  const [cancelError, setCancelError] = useState<ApiError | null>(null)
  const [pdfError, setPdfError] = useState<ApiError | null>(null)
  const [downloading, setDownloading] = useState(false)
  const allowed = !!user?.permissions.includes('SALES_VIEW')
  const result = useQuery({ queryKey: ['sales', user?.id, id], enabled: allowed, retry: false, queryFn: () => api<SaleDetail>('/sales/' + id) })
  if (!allowed) return <main><p role="alert">{t('errors.FORBIDDEN')}</p></main>
  const sale = result.data
  async function download() {
    if (!sale || downloading) return
    setDownloading(true); setPdfError(null)
    try { await downloadPdf('/sales/' + sale.id + '/pdf', 'sale-' + sale.invoice_number + '.pdf') }
    catch (failure) { setPdfError(failure instanceof ApiError ? failure : new ApiError('SERVER_ERROR')) }
    finally { setDownloading(false) }
  }
  async function cancel(event: FormEvent) {
    event.preventDefault()
    if (!sale || cancelling) return
    setCancelling(true); setCancelError(null)
    try {
      await api('/sales/' + sale.id + '/cancel', { method: 'POST', body: JSON.stringify({ reason: reason.trim(), refundConfirmed, refundMethodId: refundMethod?.id ?? null }) })
      for (const key of ['sales', 'dashboard', 'inventory', 'customer-balances']) void client.invalidateQueries({ queryKey: [key] })
    } catch (failure) { setCancelError(failure instanceof ApiError ? failure : new ApiError('SERVER_ERROR')) }
    finally { setCancelling(false) }
  }
  return <main><Link to="/sales">{t('sales.list')}</Link><h1>{sale?.invoice_number ?? t('purchase.view')}</h1>
    {sale && <button type="button" onClick={() => void download()} disabled={downloading}>{t('invoice.downloadPdf')}</button>}
    {pdfError && <p role="alert">{t('errors.' + pdfError.code, { defaultValue: t('errors.SERVER_ERROR') })}</p>}
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
      {sale.cancellation && <section className="cancellation-record"><h2>{t('sales.cancelled')}</h2><p>{sale.cancellation.reason}</p>
        <p>{t('sales.refundRecorded')}: {sale.cancellation.refund_amount} ₪{sale.cancellation.refund_method_name ? ' · ' + sale.cancellation.refund_method_name : ''}</p></section>}
      {sale.status !== 'CANCELLED' && user?.permissions.includes('SALES_EDIT') && <details className="cancel-panel"><summary>{t('sales.cancelInvoice')}</summary>
        <p>{t('sales.cancelHelp')}</p><form onSubmit={cancel}>
          <label>{t('sales.cancelReason')}<input required maxLength={500} value={reason} onChange={event => setReason(event.target.value)} /></label>
          {Number(sale.paid) > 0 && <><p>{t('sales.refundAmount')}: {sale.paid} ₪</p>
            <ReferenceSelector kind="payment-methods" id="sale-refund-method" value={refundMethod} disabled={cancelling} onChange={setRefundMethod} />
            <label className="checkbox"><input type="checkbox" checked={refundConfirmed} onChange={event => setRefundConfirmed(event.target.checked)} />{t('sales.refundConfirm')}</label></>}
          {cancelError && <p role="alert" className="error">{t('errors.' + cancelError.code, { defaultValue: t('errors.SERVER_ERROR') })}</p>}
          <button className="danger-button" disabled={cancelling || !reason.trim() || (Number(sale.paid) > 0 && !refundConfirmed)}>{t('sales.cancelInvoice')}</button>
        </form></details>}
      <h2>{t('customerPayments.history')}</h2>
      <ul>{sale.payments.map(payment => <li key={payment.id}><Link to={'/customer-payments/' + payment.id}>{payment.payment_date} · {payment.amount} ₪ · {payment.payment_method_name}{!payment.active ? ' · ' + t('cancellation.cancelled') : ''}</Link></li>)}</ul>
      <p>{sale.notes}</p></>}
  </main>
}
