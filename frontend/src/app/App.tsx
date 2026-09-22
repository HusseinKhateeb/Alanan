import { useQuery } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { Link, Route, Routes } from 'react-router-dom'
import { checkBackend } from '../api/health'
import { UpdateNotice } from './pwa/UpdateNotice'
import { AuthProvider, useAuth } from '../auth/AuthProvider'
import { LoginPage } from '../auth/LoginPage'
import { AccountControls } from '../auth/AccountControls'
import { UserDirectoryPage } from '../features/users/UserDirectoryPage'

function Workspace() {
  const { t } = useTranslation()
  const { user } = useAuth()
  const health = useQuery({ queryKey: ['backend-health'], queryFn: checkBackend,
    refetchInterval: 60_000, retry: false })
  return <main id="main">
    <div className="eyebrow">{t('workspace')}</div>
    <h1>{t('welcome')}</h1>
    <p className="intro">{t('intro')}</p>
    {user?.permissions.includes('USERS_MANAGE') && <Link className="directory-link" to="/users">{t('auth.users')}</Link>}
    <section className="setup" aria-labelledby="foundation-title">
      <div className="setup-icon" aria-hidden="true">A</div>
      <div><h2 id="foundation-title">{t('foundation')}</h2><p>{t('notice')}</p></div>
    </section>
    <h2 className="section-title">{t('next')}</h2>
    <div className="steps">{['auth', 'catalog', 'invoice'].map((step, index) =>
      <article key={step}><span className="step-number" aria-hidden="true">0{index + 1}</span>
        <h3>{t(`${step}Title`)}</h3><p>{t(`${step}Detail`)}</p>
      </article>,
    )}</div>
    <footer><span className={`health ${health.isSuccess ? 'ready' : ''}`} role="status">
      {t(health.isFetching ? 'healthChecking' : health.isSuccess ? 'healthReady' : 'healthUnavailable')}
    </span><button className="text-button" disabled={health.isFetching} onClick={() => void health.refetch()}>{t('retry')}</button></footer>
  </main>
}

export function App() {
  return <AuthProvider><AuthenticatedApp /></AuthProvider>
}

function AuthenticatedApp() {
  const { t } = useTranslation()
  const { user } = useAuth()
  return <>
    <header className="header"><Link className="brand" to="/">
      <img src="/icon.svg" alt="" width="44" height="44" />
      <span><strong>{t('brand')}</strong><small>{t('subtitle')}</small></span>
    </Link><AccountControls /></header>
    {user ? <Routes><Route path="/" element={<Workspace />} /><Route path="/users" element={<UserDirectoryPage />} /><Route path="*" element={<main><h1>{t('notFound')}</h1><Link to="/">{t('home')}</Link></main>} /></Routes> : <LoginPage />}
    <UpdateNotice />
  </>
}
