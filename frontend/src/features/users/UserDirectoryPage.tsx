import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router-dom'
import { api, ApiError } from '../../api/client'
import { useAuth } from '../../auth/AuthProvider'
import { UserAccessEditor } from './UserAccessEditor'

interface UserSummary { id: string; username: string; displayName: string; active: boolean; roles: string[] }
interface Options { roles: { name: string; permissions: string[] }[]; permissions: { code: string; description: string }[] }
interface NewUser { username: string; displayName: string; password: string; preferredLanguage: string; active: boolean; roles: string[]; permissions: string[] }

export function UserDirectoryPage() {
  const { t } = useTranslation()
  const { user } = useAuth()
  const queryClient = useQueryClient()
  const [page, setPage] = useState(0)
  const [form, setForm] = useState<NewUser>({ username: '', displayName: '', password: '', preferredLanguage: 'ar', active: true, roles: [], permissions: [] })
  const [confirm, setConfirm] = useState('')
  const [formError, setFormError] = useState('')
  const [success, setSuccess] = useState('')
  const [editingId, setEditingId] = useState<string | null>(null)
  const allowed = user?.permissions.includes('USERS_MANAGE') ?? false
  const canCreate = allowed && (user?.roles?.includes('ADMIN') ?? false)
  const users = useQuery({ queryKey: ['users', user?.id, page], queryFn: () => api<UserSummary[]>(`/users?page=${page}`), enabled: allowed, retry: false })
  const options = useQuery({ queryKey: ['user-options', user?.id], queryFn: () => api<Options>('/users/options'), enabled: canCreate, retry: false })
  const create = useMutation({ mutationFn: (input: NewUser) => api<UserSummary>('/users', { method: 'POST', body: JSON.stringify(input) }), onSuccess: async (created) => {
    setSuccess(t('auth.created', { name: created.displayName }))
    setForm({ username: '', displayName: '', password: '', preferredLanguage: 'ar', active: true, roles: [], permissions: [] })
    setConfirm('')
    setPage(0)
    await queryClient.invalidateQueries({ queryKey: ['users', user?.id] })
  } })

  function setRoles(roles: string[]) {
    const inherited = options.data?.roles.filter(role => roles.includes(role.name)).flatMap(role => role.permissions) ?? []
    setForm(current => ({ ...current, roles, permissions: [...new Set(inherited)] }))
    setSuccess('')
  }
  function submit(event: FormEvent) {
    event.preventDefault()
    setFormError('')
    setSuccess('')
    if (form.password !== confirm) { setFormError(t('auth.passwordMismatch')); return }
    const bytes = new TextEncoder().encode(form.password).length
    if (form.password.length < 6 || bytes > 72) { setFormError(t('auth.passwordPolicy')); return }
    if (form.roles.length === 0) { setFormError(t('auth.chooseRole')); return }
    create.mutate({ ...form, username: form.username.trim(), displayName: form.displayName.trim() })
  }
  if (!allowed) return <main><p role="alert">{t('errors.FORBIDDEN')}</p><Link to="/">{t('home')}</Link></main>
  return <main>
    <Link to="/">{t('home')}</Link><h1>{t('auth.users')}</h1>
    {canCreate && <section className="reference-editor user-editor" aria-labelledby="new-user-title">
      <h2 id="new-user-title">{t('auth.newUser')}</h2>
      <p>{t('auth.newUserHelp')}</p>
      {options.isPending && <p role="status">{t('auth.loadingOptions')}</p>}
      {options.error && <p role="alert">{t(`errors.${options.error instanceof ApiError ? options.error.code : 'SERVER_ERROR'}`)}</p>}
      {options.data && <form onSubmit={submit}>
        <label className="form-field">{t('auth.username')}<input required autoComplete="off" minLength={3} maxLength={100} pattern="[A-Za-z0-9._-]{3,100}" value={form.username} onChange={event => setForm({ ...form, username: event.target.value })} /></label>
        <label className="form-field">{t('auth.displayName')}<input required maxLength={200} value={form.displayName} onChange={event => setForm({ ...form, displayName: event.target.value })} /></label>
        <label className="form-field">{t('auth.newPassword')}<input required type="password" autoComplete="new-password" value={form.password} onChange={event => setForm({ ...form, password: event.target.value })} /></label>
        <small>{t('auth.passwordPolicy')}</small>
        <label className="form-field">{t('auth.confirmPassword')}<input required type="password" autoComplete="new-password" value={confirm} onChange={event => setConfirm(event.target.value)} /></label>
        <label className="form-field">{t('auth.preferredLanguage')}<select value={form.preferredLanguage} onChange={event => setForm({ ...form, preferredLanguage: event.target.value })}><option value="ar">العربية</option><option value="en">English</option></select></label>
        <label className="checkbox"><input type="checkbox" checked={form.active} onChange={event => setForm({ ...form, active: event.target.checked })} />{t('auth.activeAccount')}</label>
        <fieldset className="user-choice-group"><legend>{t('auth.roles')}</legend>{options.data.roles.map(role => <label className="checkbox" key={role.name}><input type="checkbox" checked={form.roles.includes(role.name)} onChange={event => setRoles(event.target.checked ? [...form.roles, role.name] : form.roles.filter(value => value !== role.name))} />{t(`auth.role.${role.name}`, { defaultValue: role.name })}</label>)}</fieldset>
        <fieldset className="user-choice-group"><legend>{t('auth.permissions')}</legend><p>{t('auth.permissionsHelp')}</p><div className="user-permissions">{options.data.permissions.map(permission => <label className="checkbox" key={permission.code}><input type="checkbox" checked={form.permissions.includes(permission.code)} onChange={event => setForm(current => ({ ...current, permissions: event.target.checked ? [...current.permissions, permission.code] : current.permissions.filter(code => code !== permission.code) }))} />{t(`auth.permission.${permission.code}`, { defaultValue: permission.description || permission.code })}</label>)}</div></fieldset>
        {(formError || create.error) && <p role="alert" className="error">{formError || t(`errors.${create.error instanceof ApiError ? create.error.code : 'SERVER_ERROR'}`, { defaultValue: create.error instanceof ApiError ? create.error.code : 'Server error' })}</p>}
        {success && <p role="status">{success}</p>}
        <button className="primary-button" type="submit" disabled={create.isPending}>{create.isPending ? t('auth.creating') : t('auth.createUser')}</button>
      </form>}
    </section>}
    <h2>{t('auth.existingUsers')}</h2>
    {canCreate && editingId && options.data && <UserAccessEditor id={editingId} options={options.data}
      onClose={() => setEditingId(null)} onRemoved={() => { setEditingId(null); setSuccess(t('auth.userRemoved')) }} />}
    {users.isPending && <p role="status">{t('auth.loading')}</p>}
    {users.error && <p role="alert">{t(`errors.${users.error instanceof ApiError ? users.error.code : 'SERVER_ERROR'}`)}</p>}
    <div className="user-list">{users.data?.map(account => <article key={account.id}>
      <strong>{account.displayName}</strong><span>{account.username}</span>
      <span>{account.roles?.map(role => t(`auth.role.${role}`, { defaultValue: role })).join(', ')}</span>
      <span>{t(account.active ? 'auth.active' : 'auth.inactive')}</span>
      {canCreate && account.id !== user?.id && <button type="button" onClick={() => { setEditingId(account.id); setSuccess('') }}>{t('auth.editUser')}</button>}
    </article>)}</div>
    <div className="pagination"><button disabled={page === 0 || users.isFetching} onClick={() => setPage(page - 1)}>{t('auth.previous')}</button>
      <button disabled={!users.data || users.data.length < 50 || users.isFetching} onClick={() => setPage(page + 1)}>{t('auth.next')}</button></div>
  </main>
}
