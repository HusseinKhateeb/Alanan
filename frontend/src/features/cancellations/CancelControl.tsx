import { useState, type FormEvent } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { api, ApiError } from '../../api/client'

interface Props { endpoint: string; kind: 'expense' | 'customerPayment' | 'supplierPayment'; cancelled?: string | null }
export function CancelControl({ endpoint, kind, cancelled }: Props) {
  const { t } = useTranslation()
  const client = useQueryClient()
  const [reason, setReason] = useState('')
  const [confirmed, setConfirmed] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<ApiError | null>(null)
  async function submit(event: FormEvent) {
    event.preventDefault()
    if (busy || !reason.trim() || !confirmed) return
    setBusy(true); setError(null)
    try {
      await api(endpoint + '/cancel', { method: 'POST', body: JSON.stringify({ reason: reason.trim(), refundConfirmed: confirmed }) })
      await client.invalidateQueries()
    } catch (failure) { setError(failure instanceof ApiError ? failure : new ApiError('SERVER_ERROR')) }
    finally { setBusy(false) }
  }
  if (cancelled) return <p className="cancellation-record">{t('cancellation.cancelled')}: {cancelled}</p>
  return <details className="cancel-panel"><summary>{t('cancellation.' + kind)}</summary>
    <form onSubmit={submit}>
      <label>{t('cancellation.reason')}<input required maxLength={500} value={reason} onChange={event => setReason(event.target.value)} /></label>
      <label className="checkbox"><input type="checkbox" checked={confirmed} onChange={event => setConfirmed(event.target.checked)} />
        {t(kind === 'expense' ? 'cancellation.expenseRefund' : kind === 'customerPayment' ? 'cancellation.customerRefund' : 'cancellation.supplierRefund')}</label>
      {error && <p role="alert" className="error">{t('errors.' + error.code, { defaultValue: t('errors.SERVER_ERROR') })}</p>}
      <button className="danger-button" disabled={busy || !reason.trim() || !confirmed}>{t('cancellation.confirm')}</button>
    </form>
  </details>
}
