import { useEffect, useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { useQueryClient } from '@tanstack/react-query'
import { api, ApiError } from '../../api/client'
import { useAuth } from '../../auth/AuthProvider'
import { useFormUpdateGuard } from '../../app/pwa/formUpdateGuard'
import { localDrafts } from '../../db/masterDataDrafts'
import { ReferenceSelector } from '../purchases/ReferenceSelector'
import { newOperation, operationRequest, saveOperationDraft } from './drafts'
import type { OperationDraft, OperationKind, OperationRecord } from './types'
export function OperationEntryPage({ kind }: { kind: OperationKind }) {
  const { user } = useAuth()
  const { t } = useTranslation()
  if (!user?.permissions.includes(kind === 'expenses' ? 'EXPENSES_CREATE' : 'INVENTORY_ADJUST')) return <main><p role="alert">{t('errors.FORBIDDEN')}</p></main>
  return <Editor key={user.id + kind} userId={user.id} kind={kind} />
}
function Editor({ userId, kind }: { userId: string; kind: OperationKind }) {
  const { t } = useTranslation()
  const client = useQueryClient()
  const [draft, setDraft] = useState<OperationDraft | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<ApiError | null>(null)
  const [storageFailed, setStorageFailed] = useState(false)
  useFormUpdateGuard(!!draft && !draft.receipt)
  useEffect(() => {
    let current = true
    void (async () => {
      try {
        const stored = await localDrafts.operationDrafts.get(userId + ':' + kind)
        const value = stored?.draft ?? newOperation()
        if (!stored) await saveOperationDraft(userId, kind, value)
        if (current) setDraft(value)
      } catch { if (current) setStorageFailed(true) }
    })()
    return () => { current = false }
  }, [userId, kind])
  function update(value: OperationDraft) {
    setDraft(value)
    void saveOperationDraft(userId, kind, value).then(() => setStorageFailed(false)).catch(() => setStorageFailed(true))
  }
  async function submit(event: FormEvent) {
    event.preventDefault()
    if (!draft || busy) return
    setBusy(true); setError(null)
    try {
      const submitted = draft.submitted ?? operationRequest(kind, draft)
      const pending = { ...draft, submitted }
      await saveOperationDraft(userId, kind, pending)
      setDraft(pending); setStorageFailed(false)
      const receipt = await api<OperationRecord>('/' + kind, { method: 'POST', body: JSON.stringify(submitted) })
      const complete = { ...pending, receipt }
      setDraft(complete)
      await saveOperationDraft(userId, kind, complete).catch(() => setStorageFailed(true))
      void client.invalidateQueries({ queryKey: [kind] })
      if (kind === 'waste') void client.invalidateQueries({ queryKey: ['inventory'] })
    } catch (failure) {
      if (failure instanceof ApiError) {
        setError(failure)
        if ([400, 403].includes(failure.status)) {
          const editable = { ...draft, submitted: undefined }
          await saveOperationDraft(userId, kind, editable).catch(() => setStorageFailed(true)); setDraft(editable)
        }
      } else if (failure instanceof Error && failure.message === 'OPERATION_REFERENCE_REQUIRED') setError(new ApiError(failure.message))
      else setStorageFailed(true)
    } finally { setBusy(false) }
  }
  async function startNew() {
    try { const value = newOperation(); await saveOperationDraft(userId, kind, value); setDraft(value); setError(null); setStorageFailed(false) }
    catch { setStorageFailed(true) }
  }
  const locked = busy || !!draft?.submitted
  const piece = kind === 'waste' && draft?.reference?.tracking_type === 'PIECE'
  return <main className="purchase-page"><Link to={'/' + kind}>{t('operations.' + kind)}</Link><h1>{t('operations.new.' + kind)}</h1>
    {storageFailed && <p role="alert">{t('purchase.storageError')}</p>}
    {!draft && !storageFailed && <p role="status">{t('operations.loading')}</p>}
    {draft?.receipt ? <section role="status"><h2>{t('operations.saved')}</h2><RecordSummary record={draft.receipt} kind={kind} />
      <button onClick={() => void startNew()}>{t('operations.new.' + kind)}</button></section>
      : draft && <><p>{t('operations.draftNotice')}</p>{draft.submitted && <p role="status">{t('operations.pending')}</p>}
      <form onSubmit={submit}><fieldset disabled={locked} className="purchase-header-fields">
        <ReferenceSelector kind={kind === 'expenses' ? 'expense-categories' : 'products'} id={kind + '-reference'} value={draft.reference} disabled={locked} onChange={reference => update({ ...draft, reference })} />
        <label>{t('operations.date')}<input required type="date" value={draft.date} onChange={event => update({ ...draft, date: event.target.value })} /></label>
        <label>{t(kind === 'expenses' ? 'operations.amount' : piece ? 'sales.pieces' : 'sales.kg')}<input required inputMode="decimal" pattern={kind === 'expenses' ? '[0-9]+([.][0-9]{1,2})?' : piece ? '[0-9]+' : '[0-9]+([.][0-9]{1,3})?'} maxLength={kind === 'expenses' ? 19 : 16} value={draft.value} onChange={event => update({ ...draft, value: event.target.value })} /></label>
        {kind === 'expenses' ? <ReferenceSelector kind="payment-methods" id="expense-method" value={draft.paymentMethod} disabled={locked} onChange={paymentMethod => update({ ...draft, paymentMethod })} />
          : <><label>{t('operations.reason')}<input required maxLength={500} value={draft.reason} onChange={event => update({ ...draft, reason: event.target.value })} /></label><p>{t('operations.stockHelp')}</p></>}
        <label>{t('master.notes')}<textarea maxLength={2000} value={draft.notes} onChange={event => update({ ...draft, notes: event.target.value })} /></label>
      </fieldset>
      {error && <p role="alert">{t('errors.' + error.code, { defaultValue: t('errors.SERVER_ERROR') })}</p>}
      <div className="entry-save-bar"><button className="primary-button" disabled={busy || (!draft.submitted && (!draft.reference || !draft.value || (kind === 'waste' && !draft.reason.trim())))}>{t(busy ? 'master.saving' : draft.submitted ? 'operations.retry' : 'operations.save')}</button></div>
      </form></>}
  </main>
}
export function RecordSummary({ record, kind }: { record: OperationRecord; kind: OperationKind }) {
  const { t } = useTranslation()
  return <><strong>{record.category_name ?? record.product_name}</strong><p>{record.expense_date ?? record.waste_date}</p>
    <p>{kind === 'expenses' ? record.amount + ' ₪ · ' + record.payment_method_name : record.quantity + ' ' + t(record.tracking_type === 'PIECE' ? 'sales.pieces' : 'sales.kg')}</p>
    {record.reason && <p>{record.reason}</p>}{record.notes && <p>{record.notes}</p>}
    {record.negative_stock && <p role="alert">{t('operations.negativeStock')}</p>}
    {'cost_total' in record && <p>{t('operations.cost')}: {record.cost_total === null ? t('purchase.unknownCost') : record.cost_total + ' ₪'}</p>}
  </>
}
