import { liveQuery } from 'dexie'
import { enqueueSale, retrySale, syncSales } from '../offline/outbox'
import { useEffect, useState, type FormEvent } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { useQueryClient } from '@tanstack/react-query'
import { useAuth } from '../../auth/AuthProvider'
import { ApiError } from '../../api/client'
import { localDrafts } from '../../db/masterDataDrafts'
import { ReferenceSelector } from '../purchases/ReferenceSelector'
import { SaleLineEditor } from './SaleLineEditor'
import { QuickCustomerForm } from './QuickCustomerForm'
import { newItem, newSale, saveSaleDraft, toRequest } from './drafts'
import { preview } from './calculations'
import { useFormUpdateGuard } from '../../app/pwa/formUpdateGuard'
import type { SaleDraft } from './types'

export function NewSalePage() {
  const { user } = useAuth()
  const { t } = useTranslation()
  const [params] = useSearchParams()
  if (!user?.permissions.includes('SALES_CREATE')) return <main><p role="alert">{t('errors.FORBIDDEN')}</p></main>
  return <SaleEditor key={user.id + (params.get('edit') ?? '')} userId={user.id} />
}
function SaleEditor({ userId }: { userId: string }) {
  const { t } = useTranslation()
  const { user } = useAuth()
  const client = useQueryClient()
  const [draft, setDraft] = useState<SaleDraft | null>(null)
  const [queued, setQueued] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<ApiError | null>(null)
  const [storageFailed, setStorageFailed] = useState(false)
  const [customerForm, setCustomerForm] = useState(false)
  useFormUpdateGuard(!!draft && !draft.receipt)
  useEffect(() => {
    let current = true
    async function load() {
      try {
        const stored = await localDrafts.saleDrafts.get(userId)
        const value = stored?.draft ?? newSale()
        if (!stored) await saveSaleDraft(userId, value)
        if (current) { setDraft(value); setQueued(!!(await localDrafts.outbox.get(userId + ':' + value.requestId))) }
      } catch { if (current) setStorageFailed(true) }
    }
    void load()
    return () => { current = false }
  }, [userId])

  useEffect(() => {
    if (!draft?.submitted) return
    const subscription = liveQuery(() => localDrafts.outbox.get(userId + ':' + draft.requestId)).subscribe(row => {
      if (row?.receipt) setDraft(current => current?.requestId === row.request.requestId ? { ...current, receipt: row.receipt } : current)
    })
    return () => subscription.unsubscribe()
  }, [userId, draft?.requestId, draft?.submitted])
  function update(next: SaleDraft) {
    const payable = next.autoPayFull && next.items !== draft?.items ? preview(next.items, '0') : null
    const value = payable ? { ...next, paidNow: payable.total } : next
    setDraft(value)
    void saveSaleDraft(userId, value).then(() => setStorageFailed(false)).catch(() => setStorageFailed(true))
  }
  async function submit(event: FormEvent) {
    event.preventDefault()
    if (!draft || busy) return
    setBusy(true); setError(null)
    try {
      const submitted = draft.submitted ?? toRequest(draft)
      const queuedKey = await enqueueSale(userId, draft)
      setDraft({ ...draft, submitted }); setQueued(true); setStorageFailed(false)
      await retrySale(userId, queuedKey)
      await syncSales(userId)
      const saved = await localDrafts.outbox.get(userId + ':' + draft.requestId)
      if (saved?.receipt) setDraft({ ...draft, submitted, receipt: saved.receipt })
      for (const key of ['sales', 'customer-balances', 'inventory']) void client.invalidateQueries({ queryKey: [key] })
    } catch (failure) {
      if (failure instanceof ApiError) setError(failure)
      else if (failure instanceof Error && failure.message === 'SALE_REFERENCE_REQUIRED') setError(new ApiError(failure.message))
      else setStorageFailed(true)
    } finally { setBusy(false) }
  }
  async function startNew() {
    try { const next = newSale(); await saveSaleDraft(userId, next); setDraft(next); setQueued(false); setError(null); setStorageFailed(false); setCustomerForm(false) }
    catch { setStorageFailed(true) }
  }
  function addLine() {
    if (!draft) return
    const item = newItem()
    update({ ...draft, items: [...draft.items, item] })
    requestAnimationFrame(() => document.getElementById('sale-line-' + item.localId + '-search')?.focus())
  }
  const locked = busy || !!draft?.submitted
  const totals = draft ? preview(draft.items, draft.paidNow) : null
  return <main className="purchase-page">
    <Link to="/">{t('home')}</Link><h1>{t('sales.new')}</h1>
    {storageFailed && <p role="alert">{t('purchase.storageError')}</p>}
    {!draft && !storageFailed && <p role="status">{t('sales.loadingDraft')}</p>}
    {draft?.receipt ? <section className="purchase-receipt" role="status">
      <h2>{t('sales.saved', { number: draft.receipt.invoiceNumber })}</h2>
      <p>{t('purchase.total')}: {draft.receipt.total} ₪</p>
      {draft.receipt.negativeStock && <p role="alert">{t('sales.negativeStock')}</p>}
      <p>{t('purchase.paid')}: {draft.receipt.paid} ₪ · {t('purchase.outstanding')}: {draft.receipt.outstanding} ₪</p>
      {user?.permissions.includes('SALES_VIEW') && <Link to={'/sales/' + draft.receipt.id}>{t('purchase.view')}</Link>}
      <button onClick={() => void startNew()}>{t('sales.another')}</button>
    </section> : draft && <>
      <p>{t('offline.draftNotice')}</p>
      {queued && <section role="status"><p>{t('offline.queued')}</p><button disabled={busy} onClick={() => void startNew()}>{t('sales.another')}</button></section>}
      {draft.submitted && <p role="status" className="duplicate">{t('sales.pendingRetry')}</p>}
      <p>{t('sales.walkInHelp')}</p>
      {customerForm && <QuickCustomerForm onClose={() => setCustomerForm(false)} onSelect={customer => {
        update({ ...draft, customer }); setCustomerForm(false)
        void client.invalidateQueries({ queryKey: ['purchase-reference', userId, 'customers'] })
      }} />}
      <form onSubmit={submit}>
        <fieldset disabled={locked} className="purchase-header-fields">
          <ReferenceSelector kind="customers" combined id="sale-customer" value={draft.customer} disabled={locked} onChange={customer => update({ ...draft, customer })} />
          <button type="button" disabled={locked} onClick={() => setCustomerForm(open => !open)}>{t('sales.newCustomer')}</button>
          <label>{t('purchase.date')}<input required type="date" value={draft.invoiceDate} aria-invalid={!!error?.fieldErrors.invoiceDate} onChange={event => update({ ...draft, invoiceDate: event.target.value })} />
            {error?.fieldErrors.invoiceDate && <span className="field-error">{t('errors.INVALID_FIELD')}</span>}</label>
        </fieldset>
        {draft.items.map((item, index) => <SaleLineEditor key={item.localId} item={item} index={index} disabled={locked} canRemove={draft.items.length > 1}
          onChange={next => update({ ...draft, items: draft.items.map(existing => existing.localId === item.localId ? next : existing) })}
          onRemove={() => update({ ...draft, items: draft.items.filter(existing => existing.localId !== item.localId) })} />)}
        <button type="button" disabled={locked || draft.items.length >= 100} onClick={addLine}>{t('purchase.addLine')}</button>
        <fieldset disabled={locked} className="purchase-header-fields">
          <label>{t('purchase.paidNow')} (₪)<input required inputMode="decimal" pattern="[0-9]+([.][0-9]{1,2})?" maxLength={19} value={draft.paidNow}
            onChange={event => update({ ...draft, paidNow: event.target.value, autoPayFull: false })} aria-invalid={!!error?.fieldErrors.paidNow} />
            {error?.fieldErrors.paidNow && <span className="field-error">{t('errors.INVALID_FIELD')}</span>}</label>
          <button type="button" disabled={locked || !totals} onClick={() => totals && update({ ...draft, paidNow: totals.total, autoPayFull: true })}>{t('sales.payFull')}</button>
          <ReferenceSelector kind="payment-methods" id="purchase-payment" value={draft.paymentMethod} disabled={locked} onChange={paymentMethod => update({ ...draft, paymentMethod })} />
          <label>{t('master.notes')}<textarea maxLength={2000} value={draft.notes} onChange={event => update({ ...draft, notes: event.target.value })} /></label>
        </fieldset>
        {error && <p role="alert" className="error">{t('errors.' + error.code, { defaultValue: t('errors.SERVER_ERROR') })}</p>}
        {totals?.overpaid && <p role="alert" className="error">{t('errors.PAYMENT_EXCEEDS_TOTAL')}</p>}
        <div className="purchase-save-bar">
          <div><strong>{t('purchase.total')}: {totals?.total ?? '—'} ₪</strong><span>{t('purchase.outstanding')}: {totals?.outstanding ?? '—'} ₪</span></div>
          <button className="primary-button" disabled={busy || customerForm || (!draft.submitted && (!totals || totals.overpaid || draft.items.some(item => !item.reference) || ((!draft.customer || draft.customer.is_walk_in) && totals.outstanding !== '0.00')))}>{t(busy ? 'master.saving' : draft.submitted ? 'sales.retry' : 'offline.save')}</button>
        </div>
      </form>
    </>}
  </main>
}
