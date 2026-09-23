import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { Link, useParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { api, ApiError } from '../../api/client'
import { useAuth } from '../../auth/AuthProvider'
import type { PurchaseDetail } from './types'
import { ReferenceSelector } from './ReferenceSelector'
import type { ReferenceRecord } from '../masterdata/catalogs'

export function PurchaseDetailPage() {
  const { id } = useParams()
  const { user } = useAuth()
  const { t } = useTranslation()
  const client = useQueryClient()
  const [reason, setReason] = useState('')
  const [refundConfirmed, setRefundConfirmed] = useState(false)
  const [refundMethod, setRefundMethod] = useState<ReferenceRecord | null>(null)
  const [cancelling, setCancelling] = useState(false)
  const [cancelError, setCancelError] = useState<ApiError | null>(null)
  const allowed = !!user?.permissions.includes('PURCHASES_VIEW')
  const result = useQuery({ queryKey: ['purchases', user?.id, 'detail', id], queryFn: () => api<PurchaseDetail>('/purchases/' + id), enabled: allowed, retry: false })
  const invoice = result.data

  async function cancel(event: FormEvent) {
    event.preventDefault()
    if (!invoice || cancelling) return
    setCancelling(true)
    setCancelError(null)
    try {
      await api('/purchases/' + invoice.id + '/cancel', { method: 'POST', body: JSON.stringify({ reason: reason.trim(), refundReceivedConfirmed: refundConfirmed, refundMethodId: refundMethod?.id ?? null }) })
      for (const key of ['purchases', 'dashboard', 'inventory', 'supplier-balances']) void client.invalidateQueries({ queryKey: [key] })
    } catch (failure) {
      setCancelError(failure instanceof ApiError ? failure : new ApiError('SERVER_ERROR'))
    } finally { setCancelling(false) }
  }

  if (!allowed) return <main><p role="alert">{t('errors.FORBIDDEN')}</p></main>
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
      {invoice.cancellation && <section className="cancellation-record"><h2>{t('purchase.cancelled')}</h2><p>{invoice.cancellation.reason}</p>
        <p>{t('purchase.refundRecorded')}: {invoice.cancellation.refund_received} ₪{invoice.cancellation.refund_method_name ? ' · ' + invoice.cancellation.refund_method_name : ''}</p></section>}
      {invoice.status !== 'CANCELLED' && user?.permissions.includes('PURCHASES_CREATE') && user.permissions.includes('AUDIT_VIEW') && <details className="cancel-panel"><summary>{t('purchase.cancelInvoice')}</summary>
        <p>{t('purchase.cancelHelp')}</p><form onSubmit={cancel}>
          <label>{t('purchase.cancelReason')}<input required maxLength={500} value={reason} onChange={event => setReason(event.target.value)} /></label>
          {Number(invoice.paid) > 0 && <><p>{t('purchase.refundAmount')}: {invoice.paid} ₪</p>
            <ReferenceSelector kind="payment-methods" id="purchase-refund-method" value={refundMethod} disabled={cancelling} onChange={setRefundMethod} />
            <label className="checkbox"><input type="checkbox" checked={refundConfirmed} onChange={event => setRefundConfirmed(event.target.checked)} />{t('purchase.refundConfirm')}</label></>}
          {cancelError && <p role="alert" className="error">{t('errors.' + cancelError.code, { defaultValue: t('errors.SERVER_ERROR') })}</p>}
          <button className="danger-button" disabled={cancelling || !reason.trim() || (Number(invoice.paid) > 0 && !refundConfirmed)}>{t('purchase.cancelInvoice')}</button>
        </form></details>}
      <h2>{t('purchase.payments')}</h2>
      {invoice.payments.length === 0 && <p>{t('master.empty')}</p>}
      {invoice.payments.map(payment => <p key={payment.id}>{payment.payment_date} · {payment.payment_method_name} · {payment.amount} ₪{!payment.active ? ' · ' + t('cancellation.cancelled') : ''}</p>)}
      {invoice.notes && <p>{invoice.notes}</p>}
    </>}
  </main>
}
