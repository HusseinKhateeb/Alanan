import { useState, type FormEvent } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { api, ApiError } from '../../api/client'
import { useAuth } from '../../auth/AuthProvider'

type Kind = 'sales' | 'purchases' | 'customer-payments' | 'supplier-payments' | 'expenses' | 'waste' | 'opening-stock'
export function HardDeleteControl({ kind, id, returnTo }: { kind: Kind; id: string; returnTo?: string }) {
  const { user, offline } = useAuth()
  const { t } = useTranslation()
  const client = useQueryClient()
  const navigate = useNavigate()
  const [reason, setReason] = useState('')
  const [confirmed, setConfirmed] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<ApiError | null>(null)
  if (!user?.roles.includes('ADMIN') || offline) return null
  async function remove(event: FormEvent) {
    event.preventDefault()
    if (!reason.trim() || !confirmed || busy) return
    setBusy(true); setError(null)
    try {
      await api<void>(`/admin/deletions/${kind}/${id}`, { method: 'DELETE', body: JSON.stringify({ reason: reason.trim() }) })
      await client.invalidateQueries()
      if (returnTo) navigate(returnTo, { replace: true })
      else setConfirmed(false)
    } catch (failure) { setError(failure instanceof ApiError ? failure : new ApiError('SERVER_ERROR')) }
    finally { setBusy(false) }
  }
  return <details className="cancel-panel"><summary>{t('hardDelete.title')}</summary>
    <p>{t('hardDelete.help')}</p>
    <form onSubmit={event => void remove(event)}>
      <label>{t('hardDelete.reason')}<input required maxLength={500} value={reason} onChange={event => setReason(event.target.value)} /></label>
      <label className="checkbox"><input type="checkbox" checked={confirmed} onChange={event => setConfirmed(event.target.checked)} />{t('hardDelete.confirm')}</label>
      {error && <p role="alert" className="error">{t('errors.' + error.code, { defaultValue: t('errors.SERVER_ERROR') })}</p>}
      <button className="danger-button" disabled={busy || !reason.trim() || !confirmed}>{t('hardDelete.remove')}</button>
    </form>
  </details>
}
