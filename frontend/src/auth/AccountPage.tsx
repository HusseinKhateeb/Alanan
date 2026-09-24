import { useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { ApiError } from '../api/client'
import { useAuth } from './AuthProvider'

export function AccountPage() {
  const { t } = useTranslation()
  const { user, offline, logout, changePassword } = useAuth()
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [currentPassword, setCurrentPassword] = useState('')
  const [newPassword, setNewPassword] = useState('')
  const [confirmPassword, setConfirmPassword] = useState('')

  async function run(action: () => Promise<void>) {
    setBusy(true); setError('')
    try { await action() }
    catch (failure) { setError(failure instanceof ApiError ? failure.code : 'SERVER_ERROR') }
    finally { setBusy(false) }
  }
  function submitPassword(event: FormEvent) {
    event.preventDefault()
    if (newPassword !== confirmPassword) { setError('PASSWORD_MISMATCH'); return }
    void run(() => changePassword(currentPassword, newPassword))
  }
  if (!user) return null
  return <main className="account-page">
    <Link className="directory-link" to="/">{t('home')}</Link>
    <h1>{t('auth.accountTitle')}</h1>
    <section className="account-card" aria-labelledby="account-info-title">
      <h2 id="account-info-title">{t('auth.accountInfo')}</h2>
      <dl className="account-details">
        <div><dt>{t('auth.displayName')}</dt><dd>{user.displayName}</dd></div>
        <div><dt>{t('auth.username')}</dt><dd>{user.username}</dd></div>
        <div><dt>{t('auth.roles')}</dt><dd>{user.roles.map(role => t(`auth.role.${role}`, { defaultValue: role })).join(', ')}</dd></div>
        <div><dt>{t('auth.preferredLanguage')}</dt><dd>{t(user.preferredLanguage === 'ar' ? 'auth.languageArabic' : 'auth.languageEnglish')}</dd></div>
      </dl>
    </section>
    <section className="account-card" aria-labelledby="account-password-title">
      <h2 id="account-password-title">{t('auth.changePassword')}</h2>
      <p>{t('auth.passwordPolicy')}</p>
      <form className="account-password-form" onSubmit={submitPassword}>
        <label>{t('auth.currentPassword')}<input type="password" required autoComplete="current-password" value={currentPassword} onChange={event => setCurrentPassword(event.target.value)} /></label>
        <label>{t('auth.newPassword')}<input type="password" required minLength={6} autoComplete="new-password" value={newPassword} onChange={event => setNewPassword(event.target.value)} /></label>
        <label>{t('auth.confirmPassword')}<input type="password" required autoComplete="new-password" value={confirmPassword} onChange={event => setConfirmPassword(event.target.value)} /></label>
        <button className="primary-button" disabled={busy || offline} type="submit">{t('auth.savePassword')}</button>
      </form>
    </section>
    <section className="account-card" aria-labelledby="account-session-title">
      <h2 id="account-session-title">{t('auth.currentSession')}</h2>
      <p>{t('auth.signOutCurrentHelp')}</p>
      <button disabled={busy || offline} onClick={() => void run(logout)}>{t('auth.signOutCurrent')}</button>
    </section>
    {offline && <p role="status">{t('auth.accountOffline')}</p>}
    {error && <p role="alert" className="error">{t(`errors.${error}`, { defaultValue: t('errors.SERVER_ERROR') })}</p>}
  </main>
}
