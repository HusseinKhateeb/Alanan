import { useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { useQuery } from '@tanstack/react-query'
import { api, ApiError } from '../../api/client'
import { useAuth } from '../../auth/AuthProvider'
import { useDebounced } from '../masterdata/useDebounced'
import { localDrafts } from '../../db/masterDataDrafts'
import { normalizedPhone, saveLocalCustomer } from '../offline/localCustomers'
import type { ReferencePage, ReferenceRecord } from '../masterdata/catalogs'

interface Props { onSelect: (customer: ReferenceRecord) => void; onClose: () => void }
export function QuickCustomerForm({ onSelect, onClose }: Props) {
  const { t } = useTranslation()
  const { user, offline } = useAuth()
  const [name, setName] = useState('')
  const [phone, setPhone] = useState('')
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<ApiError | null>(null)
  const [requestId] = useState(() => crypto.randomUUID())
  const debouncedPhone = useDebounced(phone)
  const matches = useQuery({ queryKey: ['customer-phone', user?.id, debouncedPhone, offline],
    queryFn: async () => {
      if (offline || navigator.onLine === false) {
        const cached = await localDrafts.references.get(user!.id + ':customers')
        return { items: (cached?.records ?? []).filter(customer => normalizedPhone(customer.phone) === normalizedPhone(debouncedPhone)), hasMore: false }
      }
      return api<ReferencePage>('/customers?activeOnly=true&phone=' + encodeURIComponent(debouncedPhone))
    },
    enabled: !!user && (debouncedPhone.match(/\p{Decimal_Number}/gu) ?? []).length >= 6, retry: false })
  const existing = matches.data?.items[0]

  async function save(event: FormEvent) {
    event.preventDefault()
    if (pending || existing || !name.trim()) return
    setPending(true); setError(null)
    try {
      const input = { name: name.trim(), phone: phone.trim() || null, requestId }
      if (input.phone && (!/^[\p{Decimal_Number}\s()+.-]+$/u.test(input.phone) || normalizedPhone(input.phone).length < 6 || normalizedPhone(input.phone).length > 20)) throw new ApiError('INVALID_PHONE')
      if (offline || navigator.onLine === false) { onSelect(await saveLocalCustomer(user!.id, requestId, input.name, input.phone ?? '')); return }
      try { onSelect(await api<ReferenceRecord>('/customers/quick', { method: 'POST', body: JSON.stringify(input) })) }
      catch (failure) {
        if (failure instanceof ApiError && (failure.status === 0 || failure.status >= 500)) onSelect(await saveLocalCustomer(user!.id, requestId, input.name, input.phone ?? ''))
        else throw failure
      }
    } catch (failure) { setError(failure instanceof ApiError ? failure : new ApiError('SERVER_ERROR')) }
    finally { setPending(false) }
  }
  return <section className="quick-customer" aria-labelledby="quick-customer-title">
    <h2 id="quick-customer-title">{t('sales.newCustomer')}</h2>
    <form onSubmit={save}>
      <label htmlFor="quick-customer-name">{t('master.name')}<input id="quick-customer-name" autoFocus required maxLength={200}
        value={name} disabled={pending} onChange={event => setName(event.target.value)} /></label>
      <label htmlFor="quick-customer-phone">{t('master.phone')}<input id="quick-customer-phone" type="tel" maxLength={60}
        value={phone} disabled={pending} onChange={event => setPhone(event.target.value)} /></label>
      {existing && <p className="duplicate">{t('master.phoneMatch', { name: existing.name })} <button type="button" disabled={pending} onClick={() => onSelect(existing)}>{t('master.selectExisting')}</button></p>}
      {(offline || navigator.onLine === false) && <p role="status">{t('sales.offlineCustomerHelp')}</p>}
      {error && <p role="alert" className="error">{t('errors.' + error.code, { defaultValue: t('errors.SERVER_ERROR') })}</p>}
      <div className="form-actions"><button className="primary-button" disabled={pending || !!existing || !name.trim()}>{t(pending ? 'master.saving' : 'sales.saveCustomer')}</button>
        <button type="button" disabled={pending} onClick={onClose}>{t('sales.cancelNewCustomer')}</button></div>
    </form>
  </section>
}
