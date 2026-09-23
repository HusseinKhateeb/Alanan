import { useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { useQuery } from '@tanstack/react-query'
import { api, ApiError } from '../../api/client'
import { useAuth } from '../../auth/AuthProvider'
import { useDebounced } from '../masterdata/useDebounced'
import type { ReferencePage, ReferenceRecord } from '../masterdata/catalogs'

interface Props { onSelect: (customer: ReferenceRecord) => void; onClose: () => void }
export function QuickCustomerForm({ onSelect, onClose }: Props) {
  const { t } = useTranslation()
  const { user } = useAuth()
  const [name, setName] = useState('')
  const [phone, setPhone] = useState('')
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<ApiError | null>(null)
  const debouncedPhone = useDebounced(phone)
  const matches = useQuery({ queryKey: ['customer-phone', user?.id, debouncedPhone],
    queryFn: () => api<ReferencePage>('/customers?activeOnly=true&phone=' + encodeURIComponent(debouncedPhone)),
    enabled: (debouncedPhone.match(/\p{Decimal_Number}/gu) ?? []).length >= 6, retry: false })
  const existing = matches.data?.items[0]

  async function save(event: FormEvent) {
    event.preventDefault()
    if (pending || existing || !name.trim()) return
    setPending(true); setError(null)
    try {
      const customer = await api<ReferenceRecord>('/customers/quick', {
        method: 'POST', body: JSON.stringify({ name: name.trim(), phone: phone.trim() || null }),
      })
      onSelect(customer)
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
      {error && <p role="alert" className="error">{t('errors.' + error.code, { defaultValue: t('errors.SERVER_ERROR') })}</p>}
      <div className="form-actions"><button className="primary-button" disabled={pending || !!existing || !name.trim()}>{t(pending ? 'master.saving' : 'sales.saveCustomer')}</button>
        <button type="button" disabled={pending} onClick={onClose}>{t('sales.cancelNewCustomer')}</button></div>
    </form>
  </section>
}
