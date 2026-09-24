import { useState, type FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { api, ApiError } from '../../api/client'

interface UserAccess { id: string; username: string; displayName: string; active: boolean; roles: string[]; permissions: string[] }
interface Options { roles: { name: string; permissions: string[] }[]; permissions: { code: string; description: string }[] }

export function UserAccessEditor({ id, options, onClose, onRemoved }: {
  id: string; options: Options; onClose: () => void; onRemoved: () => void
}) {
  const { t } = useTranslation()
  const queryClient = useQueryClient()
  const account = useQuery({ queryKey: ['user-access', id], queryFn: () => api<UserAccess>(`/users/${id}`), retry: false })
  const [editing, setEditing] = useState<{ id: string; roles: string[]; permissions: string[] } | null>(null)
  const roles = editing?.id === id ? editing.roles : account.data?.roles ?? []
  const permissions = editing?.id === id ? editing.permissions : account.data?.permissions ?? []
  const [error, setError] = useState('')
  const [saved, setSaved] = useState(false)
  const save = useMutation({ mutationFn: () => api<UserAccess>(`/users/${id}/access`, {
    method: 'PUT', body: JSON.stringify({ roles, permissions })
  }), onSuccess: async () => {
    setSaved(true)
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ['users'] }),
      queryClient.invalidateQueries({ queryKey: ['user-access', id] })
    ])
  } })
  const remove = useMutation({ mutationFn: () => api<void>(`/users/${id}`, { method: 'DELETE' }), onSuccess: async () => {
    await queryClient.invalidateQueries({ queryKey: ['users'] })
    onRemoved()
  } })
  function submit(event: FormEvent) {
    event.preventDefault()
    setError(''); setSaved(false)
    if (roles.length === 0) { setError(t('auth.chooseRole')); return }
    save.mutate()
  }
  function changeRoles(next: string[]) {
    setEditing({ id, roles: next, permissions: [...new Set(options.roles.filter(role => next.includes(role.name)).flatMap(role => role.permissions))] })
    setSaved(false)
  }
  function deleteAccount() {
    if (account.data && window.confirm(t('auth.removeUserConfirm', { name: account.data.displayName }))) remove.mutate()
  }
  const failure = save.error || remove.error || account.error
  return <section className="reference-editor user-editor" aria-labelledby="edit-user-title">
    <h2 id="edit-user-title">{t('auth.editUser')}: {account.data?.displayName}</h2>
    <button type="button" onClick={onClose}>{t('auth.closeEditor')}</button>
    {account.isPending && <p role="status">{t('auth.loading')}</p>}
    {failure && <p role="alert" className="error">{t(`errors.${failure instanceof ApiError ? failure.code : 'SERVER_ERROR'}`)}</p>}
    {account.data && <form onSubmit={submit}>
      <p>{account.data.username}</p>
      <fieldset className="user-choice-group"><legend>{t('auth.roles')}</legend>{options.roles.map(role => <label className="checkbox" key={role.name}><input type="checkbox" checked={roles.includes(role.name)} onChange={event => changeRoles(event.target.checked ? [...roles, role.name] : roles.filter(value => value !== role.name))} />{t(`auth.role.${role.name}`, { defaultValue: role.name })}</label>)}</fieldset>
      <fieldset className="user-choice-group"><legend>{t('auth.permissions')}</legend><p>{t('auth.permissionsHelp')}</p><div className="user-permissions">{options.permissions.map(permission => <label className="checkbox" key={permission.code}><input type="checkbox" checked={permissions.includes(permission.code)} onChange={event => { setEditing({ id, roles, permissions: event.target.checked ? [...permissions, permission.code] : permissions.filter(code => code !== permission.code) }); setSaved(false) }} />{t(`auth.permission.${permission.code}`, { defaultValue: permission.description || permission.code })}</label>)}</div></fieldset>
      {error && <p role="alert" className="error">{error}</p>}
      {saved && <p role="status">{t('auth.accessSaved')}</p>}
      <button className="primary-button" type="submit" disabled={save.isPending || remove.isPending}>{t('auth.saveAccess')}</button>
      <button type="button" className="danger-button" disabled={save.isPending || remove.isPending} onClick={deleteAccount}>{t('auth.removeUser')}</button>
      <p>{t('auth.removeUserHelp')}</p>
    </form>}
  </section>
}
