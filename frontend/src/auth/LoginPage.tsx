import { useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { ApiError } from '../api/client'
import { useAuth } from './AuthProvider'

export function LoginPage() {
  const { t } = useTranslation()
  const { login, expired } = useAuth()
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<ApiError | null>(null)
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (pending) return
    setPending(true); setError(null)
    try { await login(username.trim(), password) }
    catch (failure) { setError(failure instanceof ApiError ? failure : new ApiError('SERVER_ERROR')) }
    finally { setPending(false) }
  }
  return <main className="login-page">
    <section className="login-card" aria-labelledby="login-title">
      <div className="eyebrow">{t('brand')}</div>
      <h1 id="login-title">{t('auth.loginTitle')}</h1>
      <p>{t('auth.loginIntro')}</p>
      {expired && <p role="status">{t('auth.expired')}</p>}
      <form onSubmit={submit}>
        <label htmlFor="username">{t('auth.username')}</label>
        <input id="username" name="username" autoComplete="username" required maxLength={100}
          value={username} onChange={event => setUsername(event.target.value)}
          aria-invalid={!!error?.fieldErrors.username} aria-describedby={error?.fieldErrors.username ? 'username-error' : undefined} />
        {error?.fieldErrors.username && <span id="username-error" className="field-error">{t('errors.INVALID_FIELD')}</span>}
        <label htmlFor="password">{t('auth.password')}</label>
        <input id="password" name="password" type="password" autoComplete="current-password" required maxLength={72}
          value={password} onChange={event => setPassword(event.target.value)}
          aria-invalid={!!error?.fieldErrors.password} aria-describedby={error?.fieldErrors.password ? 'password-error' : undefined} />
        {error?.fieldErrors.password && <span id="password-error" className="field-error">{t('errors.INVALID_FIELD')}</span>}
        {error && <p role="alert" className="error">{t(`errors.${error.code}`, { defaultValue: t('errors.SERVER_ERROR') })}</p>}
        <button className="primary-button" type="submit" disabled={pending}>{t(pending ? 'auth.signingIn' : 'auth.signIn')}</button>
      </form>
      <p className="login-help">{t('auth.setupHelp')}</p>
    </section>
  </main>
}
