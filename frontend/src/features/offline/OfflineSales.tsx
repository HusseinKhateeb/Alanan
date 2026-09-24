import { useEffect, useState } from 'react'
import { liveQuery } from 'dexie'
import { Link, useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { useQueryClient } from '@tanstack/react-query'
import { useAuth } from '../../auth/AuthProvider'
import { ApiError } from '../../api/client'
import { localDrafts } from '../../db/masterDataDrafts'
import { refreshReferences } from './references'
import { canEditRejectedSale, editRejectedSale, retrySale, syncSales } from './outbox'
import type { QueuedSale } from './types'

export function OfflineSales() {
  const { user, offline } = useAuth()
  const { t } = useTranslation()
  const navigate = useNavigate()
  const client = useQueryClient()
  const [rows, setRows] = useState<QueuedSale[]>([])
  const [error, setError] = useState('')
  const [cachedAt, setCachedAt] = useState<number | null>(null)
  const [refreshing, setRefreshing] = useState(false)
  const allowed = !!user?.permissions.includes('SALES_CREATE')
  useEffect(() => {
    if (!user || !allowed) return
    const subscription = liveQuery(async () => ({ rows: await localDrafts.outbox.where('userId').equals(user.id).toArray(), refs: await localDrafts.references.where('userId').equals(user.id).toArray() }))
      .subscribe({ next: value => {
        setRows(value.rows)
        setCachedAt(value.refs.length === 4 ? Math.min(...value.refs.map(ref => ref.updatedAt)) : null)
        for (const key of ['sales', 'customer-balances', 'inventory']) void client.invalidateQueries({ queryKey: [key] })
      }, error: () => setError('OFFLINE_STORAGE_ERROR') })
    let current = true, running = false, caching = false
    async function run() {
      if (running || !current) return
      running = true
      try { await syncSales(user!.id, () => current) } catch { if (current) setError('OFFLINE_STORAGE_ERROR') }
      finally { running = false }
    }
    async function cache() {
      if (caching || offline || navigator.onLine === false) return
      caching = true
      try { await refreshReferences(user!.id); if (current) setError('') }
      catch (failure) { if (current) setError(failure instanceof ApiError ? failure.code : 'OFFLINE_STORAGE_ERROR') }
      finally { caching = false }
    }
    const online = () => { void run(); void cache() }
    const interval = window.setInterval(() => void run(), 10_000)
    const visible = () => { if (document.visibilityState === 'visible') void cache() }
    window.addEventListener('online', online)
    document.addEventListener('visibilitychange', visible)
    online()
    return () => { current = false; subscription.unsubscribe(); window.clearInterval(interval); window.removeEventListener('online', online); document.removeEventListener('visibilitychange', visible) }
  }, [user, allowed, offline, client])
  if (!user || !allowed) return null
  async function refresh() {
    if (offline) return
    setRefreshing(true)
    try { await refreshReferences(user!.id); setError('') } catch (failure) { setError(failure instanceof ApiError ? failure.code : 'OFFLINE_STORAGE_ERROR') }
    finally { setRefreshing(false) }
  }
  async function act(row: QueuedSale, edit = false) {
    try {
      if (edit) { await editRejectedSale(user!.id, row.key); navigate('/sales/new?edit=' + row.request.requestId) }
      else { await retrySale(user!.id, row.key); await syncSales(user!.id) }
      setError('')
    } catch (failure) { setError(failure instanceof ApiError ? failure.code : 'OFFLINE_STORAGE_ERROR') }
  }
  const mine = rows.filter(row => row.userId === user.id).sort((a, b) => b.createdAt - a.createdAt)
  return <aside className="setup"><details><summary>{t('offline.title')} ({mine.filter(row => row.status !== 'SYNCED').length})</summary>
    <p>{cachedAt ? t('offline.cacheReady', { date: new Date(cachedAt).toLocaleString() }) : t('offline.cacheMissing')}</p>
    <p>{t('offline.sessionHelp')}</p>
    <button disabled={refreshing || offline} onClick={() => void refresh()}>{t('offline.refresh')}</button>
    {error && <p role="alert">{t('errors.' + error, { defaultValue: t('errors.SERVER_ERROR') })}</p>}
    {mine.length === 0 && <p>{t('offline.empty')}</p>}
    <ul>{mine.map(row => <li key={row.key}>
      <strong>{row.draft.customer?.name ?? t('master.walkIn')}</strong> · {row.request.invoiceDate} · {t('offline.' + row.status)}
      {row.error && row.status !== 'SYNCED' && <p>{t('errors.' + row.error, { defaultValue: t('errors.SERVER_ERROR') })}</p>}
      {row.receipt && <><p>{row.receipt.invoiceNumber} · {row.receipt.total} ₪</p>{row.receipt.negativeStock && <p>{t('sales.negativeStock')}</p>}<Link to={'/sales/' + row.receipt.id}>{t('purchase.view')}</Link></>}
      {['PENDING', 'NEEDS_ATTENTION'].includes(row.status) && <button onClick={() => void act(row)}>{t('retry')}</button>}
      {canEditRejectedSale(row) && <button onClick={() => void act(row, true)}>{t('offline.edit')}</button>}
    </li>)}</ul>
  </details></aside>
}
