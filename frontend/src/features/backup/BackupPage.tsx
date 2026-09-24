import { useState, type FormEvent } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { api, ApiError, downloadBackup } from '../../api/client'
import { useAuth } from '../../auth/AuthProvider'

interface Archive { id: string; original_name: string; source: 'EXPORT' | 'UPLOAD'; size_bytes: number; sha256: string; created_at: string }
export function BackupPage() {
  const { user, offline } = useAuth()
  const { t } = useTranslation()
  const client = useQueryClient()
  const [file, setFile] = useState<File | null>(null)
  const [expectedHash, setExpectedHash] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<ApiError | null>(null)
  const [message, setMessage] = useState('')
  const allowed = !!user?.roles.includes('ADMIN') && !offline
  const query = useQuery({ queryKey: ['backups', user?.id], enabled: allowed, retry: false,
    queryFn: () => api<Archive[]>('/backups') })
  if (!allowed) return <main><p role="alert">{t(offline ? 'errors.NETWORK_ERROR' : 'errors.FORBIDDEN')}</p></main>
  async function exportNow() {
    if (busy) return
    setBusy(true); setError(null); setMessage('')
    try {
      const backup = await api<Archive>('/backups/exports', { method: 'POST', signal: AbortSignal.timeout(600_000) })
      await client.invalidateQueries({ queryKey: ['backups'] })
      await downloadBackup(`/backups/${backup.id}/download`, `alanan-${backup.id}.dump`)
      await downloadBackup(`/backups/${backup.id}/checksum`, `alanan-${backup.id}.dump.sha256`)
      setMessage(t('backup.exported'))
    } catch (failure) { setError(failure instanceof ApiError ? failure : new ApiError('SERVER_ERROR')) }
    finally { setBusy(false) }
  }
  async function upload(event: FormEvent) {
    event.preventDefault()
    if (!file || busy) return
    setBusy(true); setError(null); setMessage('')
    try {
      const form = new FormData()
      form.append('file', file)
      if (expectedHash.trim()) form.append('expectedSha256', expectedHash.trim())
      await api<Archive>('/backups', { method: 'POST', body: form, signal: AbortSignal.timeout(600_000) })
      await client.invalidateQueries({ queryKey: ['backups'] })
      setFile(null); setExpectedHash(''); setMessage(t('backup.staged'))
      const picker = document.getElementById('backup-file') as HTMLInputElement | null
      if (picker) picker.value = ''
    } catch (failure) { setError(failure instanceof ApiError ? failure : new ApiError('SERVER_ERROR')) }
    finally { setBusy(false) }
  }
  async function remove(id: string) {
    if (busy || !window.confirm(t('backup.removeConfirm'))) return
    setBusy(true); setError(null); setMessage('')
    try {
      await api<void>('/backups/' + id, { method: 'DELETE' })
      await client.invalidateQueries({ queryKey: ['backups'] })
    } catch (failure) { setError(failure instanceof ApiError ? failure : new ApiError('SERVER_ERROR')) }
    finally { setBusy(false) }
  }
  async function download(id: string) {
    setBusy(true); setError(null)
    try { await downloadBackup(`/backups/${id}/download`, `alanan-${id}.dump`) }
    catch (failure) { setError(failure instanceof ApiError ? failure : new ApiError('SERVER_ERROR')) }
    finally { setBusy(false) }
  }
  async function downloadChecksum(id: string) {
    setBusy(true); setError(null)
    try { await downloadBackup(`/backups/${id}/checksum`, `alanan-${id}.dump.sha256`) }
    catch (failure) { setError(failure instanceof ApiError ? failure : new ApiError('SERVER_ERROR')) }
    finally { setBusy(false) }
  }
  return <main><Link to="/">{t('home')}</Link><h1>{t('backup.title')}</h1>
    <p>{t('backup.help')}</p>
    <section className="workspace-section"><h2>{t('backup.create')}</h2>
      <button className="primary-button" disabled={busy} onClick={() => void exportNow()}>{t('backup.createAndDownload')}</button>
    </section>
    <section className="workspace-section"><h2>{t('backup.upload')}</h2><p>{t('backup.uploadHelp')}</p>
      <form onSubmit={event => void upload(event)}>
        <label htmlFor="backup-file">{t('backup.file')}<input id="backup-file" type="file" accept=".dump" required
          onChange={event => setFile(event.target.files?.[0] ?? null)} disabled={busy} /></label>
        <label htmlFor="backup-hash">{t('backup.expectedHash')}<input id="backup-hash" value={expectedHash} maxLength={64}
          pattern="[a-fA-F0-9]{64}" onChange={event => setExpectedHash(event.target.value)} disabled={busy} /></label>
        <button className="primary-button" disabled={busy || !file}>{t('backup.verifyAndStage')}</button>
      </form>
    </section>
    {error && <p role="alert" className="error">{t('errors.' + error.code, { defaultValue: t('errors.SERVER_ERROR') })}</p>}
    {message && <p role="status">{message}</p>}
    <section className="workspace-section"><h2>{t('backup.stagedList')}</h2>
      {query.isPending && <p role="status">{t('master.loading')}</p>}
      {query.error && <p role="alert">{t('errors.SERVER_ERROR')}</p>}
      {query.data?.length === 0 && <p>{t('master.empty')}</p>}
      <div className="reference-list">{query.data?.map(archive => <article key={archive.id}><div>
        <strong>{archive.original_name}</strong><span>{t('backup.' + archive.source)} · {new Date(archive.created_at).toLocaleString()} · {(archive.size_bytes / 1024 / 1024).toFixed(1)} MB</span>
        <small>SHA-256: {archive.sha256}</small>
        <button disabled={busy} onClick={() => void download(archive.id)}>{t('backup.download')}</button>
        <button disabled={busy} onClick={() => void downloadChecksum(archive.id)}>{t('backup.checksum')}</button>
        <button className="danger-button" disabled={busy} onClick={() => void remove(archive.id)}>{t('backup.remove')}</button>
      </div></article>)}</div>
    </section>
  </main>
}
