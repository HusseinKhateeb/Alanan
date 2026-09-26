import { useState, type FormEvent } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { api, ApiError } from '../../api/client'
import { useAuth } from '../../auth/AuthProvider'
import { businessToday } from '../../utils/businessDate'
import type { OrderDetail } from './types'

export function OrderDetailPage() {
  const { id } = useParams()
  const { user, offline } = useAuth()
  const { t } = useTranslation()
  const navigate = useNavigate()
  const client = useQueryClient()
  const [deliveryDate, setDeliveryDate] = useState(businessToday)
  const [reason, setReason] = useState('')
  const [confirmed, setConfirmed] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<ApiError | null>(null)
  const allowed = !!user && !offline && (user.permissions.includes('SALES_VIEW') || user.permissions.includes('SALES_CREATE'))
  const result = useQuery({ queryKey: ['order', id], queryFn: () => api<OrderDetail>('/orders/' + id), enabled: allowed && !!id, retry: false })
  if (!allowed) return <main><p role="alert">{t(offline ? 'orders.onlineRequired' : 'errors.FORBIDDEN')}</p></main>
  if (result.isPending) return <main role="status">{t('master.loading')}</main>
  if (result.error || !result.data) return <main role="alert">{t('orders.loadFailed')}</main>
  const order = result.data
  async function deliver(event: FormEvent) {
    event.preventDefault()
    if (busy || !id) return
    setBusy(true); setError(null)
    try {
      await api('/orders/' + id + '/deliver', { method: 'POST', body: JSON.stringify({ version: order.version, deliveryDate }) })
      await client.invalidateQueries({ queryKey: ['order', id] })
      for (const key of ['orders', 'sales', 'inventory', 'customer-balances']) void client.invalidateQueries({ queryKey: [key] })
    } catch (failure) { setError(failure instanceof ApiError ? failure : new ApiError('SERVER_ERROR')) }
    finally { setBusy(false) }
  }
  async function remove(event: FormEvent) {
    event.preventDefault()
    if (busy || !id || !reason.trim() || !confirmed) return
    setBusy(true); setError(null)
    try {
      await api<void>('/orders/' + id, { method: 'DELETE', body: JSON.stringify({ version: order.version, reason: reason.trim() }) })
      await client.invalidateQueries({ queryKey: ['orders'] })
      navigate('/orders', { replace: true })
    } catch (failure) { setError(failure instanceof ApiError ? failure : new ApiError('SERVER_ERROR')) }
    finally { setBusy(false) }
  }
  return <main className="purchase-page"><Link to="/orders">{t('orders.list')}</Link><h1>{order.order_number}</h1>
    <section className="purchase-receipt"><p><strong>{t('orders.' + order.status)}</strong></p>
      <p>{order.customer_name} · {order.order_date}</p>
      <p>{t('purchase.total')}: {order.total} ₪ · {t('orders.plannedPaid')}: {order.planned_paid} ₪</p>
      {order.status === 'OPEN' && <p>{t('orders.notPosted')}</p>}
      {order.notes && <p>{order.notes}</p>}
      <div className="record-actions">{order.status === 'OPEN' && user?.permissions.includes('SALES_CREATE') && <Link to={'/orders/' + id + '/edit'}>{t('orders.edit')}</Link>}
        {order.sale_id && <Link to={'/sales/' + order.sale_id}>{t('orders.viewInvoice')}</Link>}</div>
    </section>
    <section className="workspace-section"><h2>{t('orders.items')}</h2>
      <div className="reference-list">{order.request.items.map((item, index) => <article key={index}><div><strong>{order.references[index]?.name}</strong>
        <span>{item.mode === 'BY_AMOUNT' ? item.requestedAmount : item.quantity} · {item.unitPrice} ₪</span></div></article>)}</div>
    </section>
    {error && <p role="alert" className="error">{t('errors.' + error.code, { defaultValue: t('errors.SERVER_ERROR') })}</p>}
    {order.status === 'OPEN' && user?.permissions.includes('SALES_CREATE') && <>
      <section className="workspace-section"><h2>{t('orders.deliver')}</h2><p>{t('orders.deliverHelp')}</p>
        <form onSubmit={event => void deliver(event)} className="purchase-header-fields"><label>{t('orders.deliveryDate')}<input required type="date" value={deliveryDate} onChange={event => setDeliveryDate(event.target.value)} /></label>
          <button className="primary-button" disabled={busy}>{t('orders.createInvoice')}</button></form></section>
      <details className="cancel-panel"><summary>{t('orders.delete')}</summary><p>{t('orders.deleteHelp')}</p>
        <form onSubmit={event => void remove(event)}><label>{t('hardDelete.reason')}<input required maxLength={500} value={reason} onChange={event => setReason(event.target.value)} /></label>
          <label className="checkbox"><input type="checkbox" checked={confirmed} onChange={event => setConfirmed(event.target.checked)} />{t('hardDelete.confirm')}</label>
          <button className="danger-button" disabled={busy || !reason.trim() || !confirmed}>{t('orders.delete')}</button></form></details>
    </>}
  </main>
}
