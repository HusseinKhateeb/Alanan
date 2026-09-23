import { useEffect, useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { useQueryClient } from '@tanstack/react-query'
import { useAuth } from '../../auth/AuthProvider'
import { api, ApiError } from '../../api/client'
import { localDrafts } from '../../db/masterDataDrafts'
import { useFormUpdateGuard } from '../../app/pwa/formUpdateGuard'
import { ReferenceSelector } from '../purchases/ReferenceSelector'
import { InvoiceAllocations } from './InvoiceAllocations'
import { newPayment, savePaymentDraft, toPaymentRequest } from './drafts'
import { moneyCents, moneyFromCents } from '../../utils/money'
import type { PaymentDraft, PaymentReceipt } from './types'

export function NewCustomerPaymentPage() {
  const { user } = useAuth()
  const { t } = useTranslation()
  if (!user?.permissions.includes('CUSTOMER_PAYMENTS_CREATE')) return <main><p role="alert">{t('errors.FORBIDDEN')}</p></main>
  return <Editor key={user.id} userId={user.id} />
}
function Editor({ userId }: { userId: string }) {
  const { t } = useTranslation()
  const { user } = useAuth()
  const client = useQueryClient()
  const [draft, setDraft] = useState<PaymentDraft | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<ApiError | null>(null)
  const [storageFailed, setStorageFailed] = useState(false)
  useFormUpdateGuard(!!draft && !draft.receipt)
  useEffect(() => {
    let current = true
    void (async () => {
      try {
        const stored = await localDrafts.customerPaymentDrafts.get(userId)
        const value = stored?.draft ?? newPayment()
        if (!stored) await savePaymentDraft(userId, value)
        if (current) setDraft(value)
      } catch { if (current) setStorageFailed(true) }
    })()
    return () => { current = false }
  }, [userId])
  function update(next: PaymentDraft) {
    setDraft(next)
    void savePaymentDraft(userId, next).then(() => setStorageFailed(false)).catch(() => setStorageFailed(true))
  }
  async function submit(event: FormEvent) {
    event.preventDefault()
    if (!draft || busy) return
    setBusy(true); setError(null)
    try {
      const submitted = draft.submitted ?? toPaymentRequest(draft)
      const pending = { ...draft, submitted }
      await savePaymentDraft(userId, pending)
      setDraft(pending); setStorageFailed(false)
      const receipt = await api<PaymentReceipt>('/customer-payments', { method: 'POST', body: JSON.stringify(submitted) })
      const complete = { ...pending, receipt }
      setDraft(complete)
      await savePaymentDraft(userId, complete).catch(() => setStorageFailed(true))
      for (const key of ['customer-payments', 'customer-payment-open-invoices', 'sales', 'customer-balances']) void client.invalidateQueries({ queryKey: [key] })
    } catch (failure) {
      if (failure instanceof ApiError) {
        setError(failure)
        if (failure.status === 400 || failure.status === 403) {
          const editable = { ...draft, submitted: undefined }
          await savePaymentDraft(userId, editable).catch(() => setStorageFailed(true))
          setDraft(editable)
          void client.invalidateQueries({ queryKey: ['customer-payment-open-invoices'] })
        }
      } else if (failure instanceof Error && failure.message === 'PAYMENT_CUSTOMER_REQUIRED') setError(new ApiError(failure.message))
      else setStorageFailed(true)
    } finally { setBusy(false) }
  }
  async function startNew() {
    try { const next = newPayment(); await savePaymentDraft(userId, next); setDraft(next); setError(null); setStorageFailed(false) }
    catch { setStorageFailed(true) }
  }
  const locked = busy || !!draft?.submitted
  const amount = draft ? moneyCents(draft.amount) : null
  const allocated = draft?.allocations.reduce((sum, item) => sum + (moneyCents(item.amount) ?? 0n), 0n) ?? 0n
  const invalid = !draft?.customer || !!draft.customer.is_walk_in || amount === null || amount <= 0n || (draft.mode === 'MANUAL' &&
    (allocated !== amount || !draft.allocations.length || draft.allocations.length > 100 || draft.allocations.some(item => (moneyCents(item.amount) ?? 0n) <= 0n)))
  return <main className="purchase-page"><Link to="/">{t('home')}</Link><h1>{t('customerPayments.new')}</h1>
    {storageFailed && <p role="alert">{t('purchase.storageError')}</p>}
    {!draft && !storageFailed && <p role="status">{t('customerPayments.loadingDraft')}</p>}
    {draft?.receipt ? <section className="purchase-receipt" role="status"><h2>{t('customerPayments.saved')}</h2>
      <p>{draft.receipt.customerName} · {draft.receipt.amount} ₪ · {draft.receipt.paymentMethodName}</p>
      <ul>{draft.receipt.allocations.map(item => <li key={item.invoiceId}>{item.invoiceNumber}: {item.amount} ₪</li>)}</ul>
      {user?.permissions.includes('SALES_VIEW') && <Link to={'/customer-payments/' + draft.receipt.id}>{t('customerPayments.view')}</Link>}
      <button onClick={() => void startNew()}>{t('customerPayments.another')}</button>
    </section> : draft && <><p>{t('customerPayments.draftNotice')}</p>
      {draft.submitted && <p role="status">{t('customerPayments.pendingRetry')}</p>}
      <form onSubmit={submit}><fieldset disabled={locked} className="purchase-header-fields">
        <ReferenceSelector kind="customers" excludeWalkIn id="payment-customer" value={draft.customer} activeOnly={false} disabled={locked} onChange={customer => update({ ...draft, customer, allocations: [] })} />
        <label>{t('customerPayments.date')}<input required type="date" value={draft.paymentDate} onChange={event => update({ ...draft, paymentDate: event.target.value })} /></label>
        <label>{t('customerPayments.amount')} (₪)<input required inputMode="decimal" pattern="[0-9]+([.][0-9]{1,2})?" maxLength={19} value={draft.amount} onChange={event => update({ ...draft, amount: event.target.value })} /></label>
        <ReferenceSelector kind="payment-methods" id="payment-method" value={draft.paymentMethod} disabled={locked} onChange={paymentMethod => update({ ...draft, paymentMethod })} />
        <label>{t('customerPayments.mode')}<select value={draft.mode} onChange={event => update({ ...draft, mode: event.target.value as PaymentDraft['mode'] })}>
          <option value="AUTO">{t('customerPayments.AUTO')}</option><option value="MANUAL">{t('customerPayments.MANUAL')}</option>
        </select></label>
        <label>{t('master.notes')}<textarea maxLength={2000} value={draft.notes} onChange={event => update({ ...draft, notes: event.target.value })} /></label>
      </fieldset>
      {draft.mode === 'AUTO' ? <p>{t('customerPayments.autoHelp')}</p> : <>
        {draft.customer && <InvoiceAllocations key={draft.customer.id} customerId={draft.customer.id} allocations={draft.allocations} disabled={locked} onChange={allocations => update({ ...draft, allocations })} />}
        <p>{t('customerPayments.allocated')}: {moneyFromCents(allocated)} ₪ / {draft.amount || '0'} ₪</p>
        {allocated !== amount && <p>{t('errors.ALLOCATION_TOTAL_MISMATCH')}</p>}
      </>}
      {error && <p role="alert">{t('errors.' + error.code, { defaultValue: t('errors.SERVER_ERROR') })}</p>}
      <div className="entry-save-bar"><button className="primary-button" disabled={busy || (!draft.submitted && invalid)}>{t(busy ? 'master.saving' : draft.submitted ? 'customerPayments.retry' : 'customerPayments.save')}</button></div>
      </form></>}
  </main>
}
