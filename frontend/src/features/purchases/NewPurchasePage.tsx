import { useEffect, useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { useQueryClient } from '@tanstack/react-query'
import { useAuth } from '../../auth/AuthProvider'
import { api, ApiError } from '../../api/client'
import { localDrafts } from '../../db/masterDataDrafts'
import { ReferenceForm } from '../masterdata/ReferenceForm'
import { ReferenceSelector } from './ReferenceSelector'
import { PurchaseLineEditor } from './PurchaseLineEditor'
import { newItem, newPurchase, savePurchaseDraft, toRequest } from './drafts'
import { preview } from './calculations'
import { useFormUpdateGuard } from '../../app/pwa/formUpdateGuard'
import type { PurchaseDraft, PurchaseReceipt } from './types'

export function NewPurchasePage() {
  const { user } = useAuth()
  const { t } = useTranslation()
  if (!user?.permissions.includes('PURCHASES_CREATE')) return <main><p role="alert">{t('errors.FORBIDDEN')}</p></main>
  return <PurchaseEditor key={user.id} userId={user.id} />
}
function PurchaseEditor({ userId }: { userId: string }) {
  const { t } = useTranslation()
  const { user } = useAuth()
  const client = useQueryClient()
  const [draft, setDraft] = useState<PurchaseDraft | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<ApiError | null>(null)
  const [storageFailed, setStorageFailed] = useState(false)
  const [supplierForm, setSupplierForm] = useState(false)
  useFormUpdateGuard(!!draft && !draft.receipt)
  useEffect(() => {
    let current = true
    async function load() {
      try {
        const stored = await localDrafts.purchaseDrafts.get(userId)
        const value = stored?.draft ?? newPurchase()
        if (!stored) await savePurchaseDraft(userId, value)
        if (current) setDraft(value)
      } catch { if (current) setStorageFailed(true) }
    }
    void load()
    return () => { current = false }
  }, [userId])

  function update(next: PurchaseDraft) {
    setDraft(next)
    void savePurchaseDraft(userId, next).then(() => setStorageFailed(false)).catch(() => setStorageFailed(true))
  }
  async function submit(event: FormEvent) {
    event.preventDefault()
    if (!draft || busy) return
    setBusy(true); setError(null)
    let submitted = draft.submitted
    try {
      submitted ??= toRequest(draft)
      const pending = { ...draft, submitted }
      // Durable write MUST complete before any accounting request leaves this device.
      await savePurchaseDraft(userId, pending)
      setDraft(pending); setStorageFailed(false)
      const receipt = await api<PurchaseReceipt>('/purchases', { method: 'POST', body: JSON.stringify(submitted) })
      const complete = { ...pending, receipt }
      setDraft(complete)
      await savePurchaseDraft(userId, complete).catch(() => setStorageFailed(true))
      void client.invalidateQueries({ queryKey: ['purchases'] })
      void client.invalidateQueries({ queryKey: ['supplier-balances'] })
      void client.invalidateQueries({ queryKey: ['inventory'] })
    } catch (failure) {
      if (failure instanceof ApiError) {
        setError(failure)
        if (failure.status === 400 || failure.status === 403) {
          const editable = { ...draft, submitted: undefined }
          await savePurchaseDraft(userId, editable).catch(() => setStorageFailed(true))
          setDraft(editable)
        }
      } else if (failure instanceof Error && failure.message === 'PURCHASE_REFERENCE_REQUIRED') {
        setError(new ApiError('PURCHASE_REFERENCE_REQUIRED'))
      } else { setStorageFailed(true) }
    } finally { setBusy(false) }
  }
  async function startNew() {
    try { const next = newPurchase(); await savePurchaseDraft(userId, next); setDraft(next); setError(null); setStorageFailed(false) }
    catch { setStorageFailed(true) }
  }
  const locked = busy || !!draft?.submitted
  const totals = draft ? preview(draft.items, draft.paidNow) : null
  return <main className="purchase-page">
    <Link to="/">{t('home')}</Link><h1>{t('purchase.new')}</h1>
    {storageFailed && <p role="alert">{t('purchase.storageError')}</p>}
    {!draft && !storageFailed && <p role="status">{t('purchase.loadingDraft')}</p>}
    {draft?.receipt ? <section className="purchase-receipt" role="status">
      <h2>{t('purchase.saved', { number: draft.receipt.invoiceNumber })}</h2>
      <p>{t('purchase.total')}: {draft.receipt.total} ₪</p>
      <p>{t('purchase.paid')}: {draft.receipt.paid} ₪ · {t('purchase.outstanding')}: {draft.receipt.outstanding} ₪</p>
      {user?.permissions.includes('PURCHASES_VIEW') && <Link to={'/purchases/' + draft.receipt.id}>{t('purchase.view')}</Link>}
      <button onClick={() => void startNew()}>{t('purchase.another')}</button>
    </section> : draft && <>
      <p>{t('purchase.draftNotice')}</p>
      {draft.submitted && <p role="status" className="duplicate">{t('purchase.pendingRetry')}</p>}
      {supplierForm && <ReferenceForm catalog="suppliers" onClose={() => setSupplierForm(false)} onSelect={() => undefined}
        onSaved={supplier => { update({ ...draft, supplier }); setSupplierForm(false); void client.invalidateQueries({ queryKey: ['purchase-reference'] }) }} />}
      <form onSubmit={submit}>
        <fieldset disabled={locked} className="purchase-header-fields">
          <ReferenceSelector kind="suppliers" id="purchase-supplier" value={draft.supplier} disabled={locked} combined onChange={supplier => update({ ...draft, supplier })} />
          {user?.permissions.includes('SETTINGS_MANAGE') && <button type="button" onClick={() => setSupplierForm(!supplierForm)}>{t('purchase.newSupplier')}</button>}
          <label>{t('purchase.date')}<input required type="date" value={draft.invoiceDate} aria-invalid={!!error?.fieldErrors.invoiceDate} onChange={event => update({ ...draft, invoiceDate: event.target.value })} />
            {error?.fieldErrors.invoiceDate && <span className="field-error">{t('errors.INVALID_FIELD')}</span>}</label>
          <label>{t('purchase.reference')}<input maxLength={200} value={draft.supplierReference} onChange={event => update({ ...draft, supplierReference: event.target.value })} /></label>
        </fieldset>
        {draft.items.map((item, index) => <PurchaseLineEditor key={item.localId} item={item} index={index} disabled={locked} canRemove={draft.items.length > 1}
          errors={error?.fieldErrors ?? {}} onChange={next => update({ ...draft, items: draft.items.map(existing => existing.localId === item.localId ? next : existing) })}
          onRemove={() => update({ ...draft, items: draft.items.filter(existing => existing.localId !== item.localId) })} />)}
        <button type="button" disabled={locked || draft.items.length >= 100} onClick={() => update({ ...draft, items: [...draft.items, newItem()] })}>{t('purchase.addLine')}</button>
        <fieldset disabled={locked} className="purchase-header-fields">
          <label>{t('purchase.paidNow')} (₪)<input required inputMode="decimal" pattern="[0-9]+([.][0-9]{1,2})?" maxLength={19} value={draft.paidNow}
            onChange={event => update({ ...draft, paidNow: event.target.value })} aria-invalid={!!error?.fieldErrors.paidNow} />
            {error?.fieldErrors.paidNow && <span className="field-error">{t('errors.INVALID_FIELD')}</span>}</label>
          <ReferenceSelector kind="payment-methods" id="purchase-payment" value={draft.paymentMethod} disabled={locked} onChange={paymentMethod => update({ ...draft, paymentMethod })} />
          <label>{t('master.notes')}<textarea maxLength={2000} value={draft.notes} onChange={event => update({ ...draft, notes: event.target.value })} /></label>
        </fieldset>
        {error && <p role="alert" className="error">{t('errors.' + error.code, { defaultValue: t('errors.SERVER_ERROR') })}</p>}
        {totals?.overpaid && <p role="alert" className="error">{t('errors.PAYMENT_EXCEEDS_TOTAL')}</p>}
        <div className="purchase-save-bar">
          <div><strong>{t('purchase.total')}: {totals?.total ?? '—'} ₪</strong><span>{t('purchase.outstanding')}: {totals?.outstanding ?? '—'} ₪</span></div>
          <button className="primary-button" disabled={busy || !!totals?.overpaid || (!draft.submitted && supplierForm)}>{t(busy ? 'master.saving' : draft.submitted ? 'purchase.retry' : 'purchase.save')}</button>
        </div>
      </form>
    </>}
  </main>
}
