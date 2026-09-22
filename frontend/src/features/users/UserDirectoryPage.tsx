import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router-dom'
import { api, ApiError } from '../../api/client'
import { useAuth } from '../../auth/AuthProvider'

interface UserSummary { id: string; username: string; displayName: string; active: boolean }
export function UserDirectoryPage() {
  const { t } = useTranslation()
  const { user } = useAuth()
  const [page, setPage] = useState(0)
  const allowed = user?.permissions.includes('USERS_MANAGE') ?? false
  const users = useQuery({ queryKey: ['users', user?.id, page], queryFn: () => api<UserSummary[]>(`/users?page=${page}`), enabled: allowed, retry: false })
  if (!allowed) return <main><p role="alert">{t('errors.FORBIDDEN')}</p><Link to="/">{t('home')}</Link></main>
  return <main>
    <Link to="/">{t('home')}</Link><h1>{t('auth.users')}</h1>
    {users.isPending && <p role="status">{t('auth.loading')}</p>}
    {users.error && <p role="alert">{t(`errors.${users.error instanceof ApiError ? users.error.code : 'SERVER_ERROR'}`)}</p>}
    <div className="user-list">{users.data?.map(account => <article key={account.id}>
      <strong>{account.displayName}</strong><span>{account.username}</span>
      <span>{t(account.active ? 'auth.active' : 'auth.inactive')}</span>
    </article>)}</div>
    <div className="pagination"><button disabled={page === 0 || users.isFetching} onClick={() => setPage(page - 1)}>{t('auth.previous')}</button>
      <button disabled={!users.data || users.data.length < 50 || users.isFetching} onClick={() => setPage(page + 1)}>{t('auth.next')}</button></div>
  </main>
}
