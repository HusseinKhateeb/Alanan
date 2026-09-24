import { useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { api, ApiError } from '../../api/client'
import type { ReferenceRecord } from '../masterdata/catalogs'

export function QuickExpenseCategoryForm({ onSelect, onClose }: { onSelect: (category: ReferenceRecord) => void; onClose: () => void }) {
  const { t } = useTranslation()
  const [name, setName] = useState('')
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<ApiError | null>(null)
  async function save(event: FormEvent) {
    event.preventDefault()
    if (pending || !name.trim()) return
    setPending(true); setError(null)
    try {
      const category = await api<ReferenceRecord>('/expense-categories/quick', {
        method: 'POST', body: JSON.stringify({ name: name.trim() }),
      })
      onSelect(category)
    } catch (failure) { setError(failure instanceof ApiError ? failure : new ApiError('SERVER_ERROR')) }
    finally { setPending(false) }
  }
  return <section className="quick-customer" aria-labelledby="quick-expense-category-title">
    <h2 id="quick-expense-category-title">{t('operations.newExpenseCategory')}</h2>
    <form onSubmit={event => void save(event)}>
      <label htmlFor="quick-expense-category-name">{t('master.name')}
        <input id="quick-expense-category-name" autoFocus required maxLength={200} value={name} disabled={pending}
          onChange={event => setName(event.target.value)} /></label>
      {error && <p role="alert" className="error">{t('errors.' + error.code, { defaultValue: t('errors.SERVER_ERROR') })}</p>}
      <div className="form-actions"><button className="primary-button" disabled={pending || !name.trim()}>{t(pending ? 'master.saving' : 'operations.saveExpenseCategory')}</button>
        <button type="button" disabled={pending} onClick={onClose}>{t('sales.cancelNewCustomer')}</button></div>
    </form>
  </section>
}
