import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useAuth } from './AuthProvider'
import { ApiError } from '../api/client'

export function AccountControls() {
  const { t, i18n } = useTranslation()
  const { user, offline, logout, changeLanguage } = useAuth()
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  async function run(action: () => Promise<void>) {
    setBusy(true); setError('')
    try { await action() } catch (failure) { setError(failure instanceof ApiError ? failure.code : 'SERVER_ERROR') }
    finally { setBusy(false) }
  }
  return <div className="account-controls">
    <div className="languages" aria-label={t('language')}>
      <button disabled={busy} lang="ar" aria-pressed={i18n.language === 'ar'} onClick={() => void run(() => changeLanguage('ar'))}>العربية</button>
      <button disabled={busy} lang="en" aria-pressed={i18n.language === 'en'} onClick={() => void run(() => changeLanguage('en'))}>English</button>
    </div>
    {user && <><span className="account-name">{user.displayName}</span>
      <button disabled={busy || offline} onClick={() => void run(logout)}>{t('auth.signOut')}</button></>}
    {error && <span role="alert" className="error">{t(`errors.${error}`, { defaultValue: t('errors.SERVER_ERROR') })}</span>}
  </div>
}
