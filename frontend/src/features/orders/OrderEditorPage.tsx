import { useMemo, useState, type FormEvent } from 'react'
import { useNavigate, useParams, Link } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { api, ApiError } from '../../api/client'
import { useAuth } from '../../auth/AuthProvider'
import { useFormUpdateGuard } from '../../app/pwa/formUpdateGuard'
import { ReferenceSelector } from '../purchases/ReferenceSelector'
import { QuickCustomerForm } from '../sales/QuickCustomerForm'
import { SaleLineEditor } from '../sales/SaleLineEditor'
import { newItem, newSale, toRequest } from '../sales/drafts'
import { preview } from '../sales/calculations'
import type { SaleDraft } from '../sales/types'
import type { OrderDetail, OrderSummary } from './types'

function fromOrder(order: OrderDetail): SaleDraft {
  return {
    requestId: order.request.requestId,
    customer: order.request.customerId ? { id: order.request.customerId, name: order.customer_name, active: true, version: 0 } : null,
    invoiceDate: order.request.invoiceDate, notes: order.request.notes ?? '', paidNow: String(order.request.paidNow),
    paymentMethod: order.request.paymentMethodId ? { id: String(order.request.paymentMethodId), name: '', active: true, version: 0 } : null,
    items: order.request.items.map((item, index) => ({
      localId: crypto.randomUUID(), kind: item.containerTypeId ? 'CONTAINER' : 'PRODUCT', reference: order.references[index],
      mode: item.mode, quantity: item.quantity == null ? '1' : String(item.quantity),
      requestedAmount: item.requestedAmount == null ? '' : String(item.requestedAmount), unitPrice: String(item.unitPrice),
    })),
  }
}

export function OrderEditorPage() {
  const { id } = useParams()
  const { user, offline } = useAuth()
  const { t } = useTranslation()
  const navigate = useNavigate()
  const client = useQueryClient()
  const [storedDraft, setDraft] = useState<SaleDraft | null>(id ? null : newSale)
  const [customerForm, setCustomerForm] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<ApiError | null>(null)
  const result = useQuery({ queryKey: ['order', id], queryFn: () => api<OrderDetail>('/orders/' + id), enabled: !!id && !!user && !offline, retry: false })
  const initialDraft = useMemo(() => result.data ? fromOrder(result.data) : null, [result.data])
  const draft = storedDraft ?? initialDraft
  useFormUpdateGuard(!!draft && !busy)
  if (!user?.permissions.includes('SALES_CREATE')) return <main><p role="alert">{t('errors.FORBIDDEN')}</p></main>
  if (offline) return <main><p role="alert">{t('orders.onlineRequired')}</p></main>
  if (id && result.isPending) return <main role="status">{t('master.loading')}</main>
  if (id && result.error) return <main role="alert">{t('orders.loadFailed')}</main>
  if (id && result.data?.status !== 'OPEN') return <main><p role="alert">{t('orders.closed')}</p><Link to={'/orders/' + id}>{t('purchase.view')}</Link></main>
  if (!draft) return <main role="status">{t('master.loading')}</main>
  const totals = preview(draft.items, draft.paidNow)
  function update(next: SaleDraft) {
    const payable = next.autoPayFull && next.items !== draft?.items ? preview(next.items, '0') : null
    setDraft(payable ? { ...next, paidNow: payable.total } : next)
  }
  function addLine() {
    if (!draft || draft.items.length >= 100) return
    const item = newItem()
    update({ ...draft, items: [...draft.items, item] })
    requestAnimationFrame(() => document.getElementById('sale-line-' + item.localId + '-search')?.focus())
  }
  async function save(event: FormEvent) {
    event.preventDefault()
    if (!draft || busy || !totals || totals.overpaid || draft.items.some(item => !item.reference)) return
    setBusy(true); setError(null)
    try {
      const sale = toRequest(draft)
      const order = id
        ? await api<OrderSummary>('/orders/' + id, { method: 'PUT', body: JSON.stringify({ version: result.data!.version, sale }) })
        : await api<OrderSummary>('/orders', { method: 'POST', body: JSON.stringify(sale) })
      await client.invalidateQueries({ queryKey: ['orders'] })
      navigate('/orders/' + order.id, { replace: true })
    } catch (failure) { setError(failure instanceof ApiError ? failure : new ApiError('SERVER_ERROR')) }
    finally { setBusy(false) }
  }
  return <main className="purchase-page">
    <Link to={id ? '/orders/' + id : '/orders'}>{t('orders.list')}</Link><h1>{t(id ? 'orders.edit' : 'orders.new')}</h1>
    <p>{t('orders.notPosted')}</p>
    {customerForm && <QuickCustomerForm onClose={() => setCustomerForm(false)} onSelect={customer => {
      update({ ...draft, customer }); setCustomerForm(false)
      void client.invalidateQueries({ queryKey: ['purchase-reference', user.id, 'customers'] })
    }} />}
    <form onSubmit={event => void save(event)}>
      <fieldset disabled={busy} className="purchase-header-fields">
        <ReferenceSelector kind="customers" combined id="order-customer" value={draft.customer} disabled={busy} onChange={customer => update({ ...draft, customer })} />
        <button type="button" disabled={busy} onClick={() => setCustomerForm(open => !open)}>{t('sales.newCustomer')}</button>
        <label>{t('orders.deliveryDate')}<input required type="date" value={draft.invoiceDate} onChange={event => update({ ...draft, invoiceDate: event.target.value })} /></label>
      </fieldset>
      {draft.items.map((item, index) => <SaleLineEditor key={item.localId} item={item} index={index} disabled={busy} canRemove={draft.items.length > 1} autoFocusFirst={!id} quickKgPrices
        onChange={next => update({ ...draft, items: draft.items.map(existing => existing.localId === item.localId ? next : existing) })}
        onRemove={() => update({ ...draft, items: draft.items.filter(existing => existing.localId !== item.localId) })}
        onNext={() => index === draft.items.length - 1 ? addLine() : document.getElementById('sale-line-' + draft.items[index + 1].localId + '-search')?.focus()} />)}
      <button type="button" disabled={busy || draft.items.length >= 100} onClick={addLine}>{t('purchase.addLine')}</button>
      <fieldset disabled={busy} className="purchase-header-fields">
        <label>{t('orders.plannedPaid')} (₪)<input required inputMode="decimal" pattern="[0-9]+([.][0-9]{1,2})?" value={draft.paidNow}
          onChange={event => update({ ...draft, paidNow: event.target.value, autoPayFull: false })} /></label>
        <button type="button" disabled={busy || !totals} onClick={() => totals && update({ ...draft, paidNow: totals.total, autoPayFull: true })}>{t('sales.payFull')}</button>
        <ReferenceSelector kind="payment-methods" id="order-payment" value={draft.paymentMethod} disabled={busy} onChange={paymentMethod => update({ ...draft, paymentMethod })} />
        <label>{t('master.notes')}<textarea maxLength={2000} value={draft.notes} onChange={event => update({ ...draft, notes: event.target.value })} /></label>
      </fieldset>
      {error && <p role="alert" className="error">{t('errors.' + error.code, { defaultValue: t('errors.SERVER_ERROR') })}</p>}
      <div className="purchase-save-bar"><div><strong>{t('purchase.total')}: {totals?.total ?? '—'} ₪</strong><span>{t('purchase.outstanding')}: {totals?.outstanding ?? '—'} ₪</span></div>
        <button className="primary-button" disabled={busy || customerForm || !totals || totals.overpaid || draft.items.some(item => !item.reference) || ((!draft.customer || draft.customer.is_walk_in) && totals.outstanding !== '0.00')}>
          {t(busy ? 'master.saving' : id ? 'orders.saveEdit' : 'orders.save')}</button></div>
    </form>
  </main>
}
