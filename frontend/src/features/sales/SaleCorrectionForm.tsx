import { useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { api, ApiError } from '../../api/client'
import { ReferenceSelector } from '../purchases/ReferenceSelector'
import type { ReferenceRecord } from '../masterdata/catalogs'
import { SaleLineEditor } from './SaleLineEditor'
import { newItem } from './drafts'
import { preview } from './calculations'
import { moneyCents, moneyFromCents } from '../../utils/money'
import type { SaleDetail, SaleLine } from './types'

export function SaleCorrectionForm({ sale, onSaved }: { sale: SaleDetail; onSaved: () => void }) {
  const { t } = useTranslation()
  const [open, setOpen] = useState(false)
  const [customer, setCustomer] = useState<ReferenceRecord | null>(sale.customer_is_walk_in ? null : { id: sale.customer_id, name: sale.customer_name, active: true, version: 0 })
  const [date, setDate] = useState(sale.invoice_date)
  const [notes, setNotes] = useState(sale.notes ?? '')
  const [paidNow, setPaidNow] = useState(sale.paid_at_sale)
  const [method, setMethod] = useState<ReferenceRecord | null>(sale.payment_method_id == null ? null : {
    id: String(sale.payment_method_id), name: sale.payment_method_name ?? '', active: true, version: 0,
  })
  const [reason, setReason] = useState('')
  const [items, setItems] = useState<SaleLine[]>(sale.items.map(item => ({
    localId: crypto.randomUUID(), kind: item.container_type_id ? 'CONTAINER' : 'PRODUCT',
    reference: { id: item.product_id ?? item.container_type_id ?? '', name: item.item_name, active: true, version: 0,
      tracking_type: item.item_type === 'WEIGHT_PRODUCT' ? 'WEIGHT' : 'PIECE', default_selling_price: item.unit_price, default_price: item.unit_price },
    mode: item.selling_mode, quantity: item.item_type === 'WEIGHT_PRODUCT' ? item.quantity : item.quantity.replace(/\.0+$/, ''),
    requestedAmount: item.requested_amount ?? '', unitPrice: item.unit_price,
  })))
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<ApiError | null>(null)
  const totals = preview(items, paidNow)
  const laterPaid = (moneyCents(sale.paid) ?? 0n) - (moneyCents(sale.paid_at_sale) ?? 0n)
  const correctedOutstanding = totals ? moneyCents(totals.total)! - (moneyCents(paidNow) ?? 0n) - laterPaid : null
  const fullInitialPayment = totals ? moneyCents(totals.total)! - laterPaid : null
  const invalid = !totals || correctedOutstanding === null || correctedOutstanding < 0n || (!customer && correctedOutstanding !== 0n)

  function changeItem(index: number, item: SaleLine) { setItems(current => current.map((value, i) => i === index ? item : value)) }
  async function save(event: FormEvent) {
    event.preventDefault()
    if (busy || invalid || !reason.trim() || items.some(item => !item.reference)) return
    setBusy(true); setError(null)
    try {
      await api('/sales/' + sale.id, { method: 'PUT', body: JSON.stringify({
        version: sale.version, customerId: customer?.id ?? null, invoiceDate: date, notes: notes || null,
        paidNow, paymentMethodId: Number(paidNow) > 0 ? method?.id ?? null : null, reason: reason.trim(),
        items: items.map(item => ({ productId: item.kind === 'PRODUCT' ? item.reference!.id : null,
          containerTypeId: item.kind === 'CONTAINER' ? item.reference!.id : null, mode: item.mode,
          quantity: item.mode === 'BY_AMOUNT' ? null : item.quantity,
          requestedAmount: item.mode === 'BY_AMOUNT' ? item.requestedAmount : null, unitPrice: item.unitPrice })),
      }) })
      setOpen(false); onSaved()
    } catch (failure) { setError(failure instanceof ApiError ? failure : new ApiError('SERVER_ERROR')) }
    finally { setBusy(false) }
  }
  return <section className="workspace-section purchase-page sale-correction">
    <button type="button" onClick={() => setOpen(value => !value)}>{t('sales.correctInvoice')}</button>
    {open && <form onSubmit={event => void save(event)}>
      <h2>{t('sales.correctInvoice')} · {sale.invoice_number}</h2>
      <p>{t('sales.correctionHelp')}</p>
      <fieldset disabled={busy} className="purchase-header-fields">
        <ReferenceSelector kind="customers" combined id="correction-customer" value={customer} activeOnly={false} disabled={busy} onChange={setCustomer} />
        <label>{t('purchase.date')}<input required type="date" value={date} onChange={event => setDate(event.target.value)} /></label>
      </fieldset>
      {items.map((item, index) => <SaleLineEditor key={item.localId} item={item} index={index} disabled={busy} canRemove={items.length > 1} autoFocusFirst={false}
        onChange={next => changeItem(index, next)} onRemove={() => setItems(current => current.filter(value => value.localId !== item.localId))}
        onNext={() => index === items.length - 1 ? setItems(current => [...current, newItem()]) : undefined} />)}
      <button type="button" disabled={busy || items.length >= 100} onClick={() => setItems(current => [...current, newItem()])}>{t('purchase.addLine')}</button>
      <fieldset disabled={busy} className="purchase-header-fields">
        <label>{t('purchase.paidNow')} (₪)<input required inputMode="decimal" pattern="[0-9]+([.][0-9]{1,2})?" maxLength={19} value={paidNow} onChange={event => setPaidNow(event.target.value)} /></label>
        <button type="button" disabled={fullInitialPayment === null || fullInitialPayment < 0n} onClick={() => fullInitialPayment !== null && setPaidNow(moneyFromCents(fullInitialPayment))}>{t('sales.payFull')}</button>
        <ReferenceSelector kind="payment-methods" id="correction-method" value={method} disabled={busy} onChange={setMethod} />
        <label>{t('master.notes')}<textarea maxLength={2000} value={notes} onChange={event => setNotes(event.target.value)} /></label>
      </fieldset>
      {laterPaid > 0n && <p>{t('sales.laterPayments')}: {moneyFromCents(laterPaid)} ₪</p>}
      <label>{t('sales.correctionReason')}<input required maxLength={500} value={reason} onChange={event => setReason(event.target.value)} /></label>
      {error && <p role="alert" className="error">{t('errors.' + error.code, { defaultValue: t('errors.SERVER_ERROR') })}</p>}
      <div className="purchase-save-bar">
        <div><strong>{t('purchase.total')}: {totals?.total ?? '—'} ₪</strong>
          <span>{t('purchase.outstanding')}: {correctedOutstanding === null ? '—' : moneyFromCents(correctedOutstanding)} ₪</span></div>
        <button className="primary-button" disabled={busy || invalid || !reason.trim() || items.some(item => !item.reference)}>{t(busy ? 'master.saving' : 'sales.saveCorrection')}</button>
      </div>
    </form>}
  </section>
}
