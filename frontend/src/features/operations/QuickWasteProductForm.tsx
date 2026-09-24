import { useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { api, ApiError } from '../../api/client'
import type { ReferenceRecord } from '../masterdata/catalogs'

export function QuickWasteProductForm({ onSelect, onClose }: { onSelect: (product: ReferenceRecord) => void; onClose: () => void }) {
  const { t } = useTranslation()
  const [name, setName] = useState('')
  const [code, setCode] = useState('')
  const [category, setCategory] = useState('SPICE')
  const [trackingType, setTrackingType] = useState('WEIGHT')
  const [price, setPrice] = useState('')
  const [defaultCost, setDefaultCost] = useState('')
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<ApiError | null>(null)
  async function save(event: FormEvent) {
    event.preventDefault()
    if (pending || !name.trim() || !code.trim() || !price) return
    setPending(true); setError(null)
    try {
      const product = await api<ReferenceRecord>('/products/quick-waste', {
        method: 'POST', body: JSON.stringify({ name: name.trim(), code: code.trim(), category, trackingType, price, defaultCost: defaultCost || null }),
      })
      onSelect(product)
    } catch (failure) { setError(failure instanceof ApiError ? failure : new ApiError('SERVER_ERROR')) }
    finally { setPending(false) }
  }
  function changeCategory(value: string) {
    setCategory(value)
    if (value === 'SPICE') setTrackingType('WEIGHT')
    if (value === 'HONEY') setTrackingType('PIECE')
  }
  return <section className="quick-customer" aria-labelledby="quick-waste-product-title">
    <h2 id="quick-waste-product-title">{t('operations.newWasteProduct')}</h2>
    <form onSubmit={event => void save(event)}>
      <label htmlFor="quick-waste-name">{t('master.name')}<input id="quick-waste-name" autoFocus required maxLength={200}
        value={name} disabled={pending} onChange={event => setName(event.target.value)} /></label>
      <label htmlFor="quick-waste-code">{t('master.code')}<input id="quick-waste-code" required maxLength={60}
        value={code} disabled={pending} onChange={event => setCode(event.target.value)} /></label>
      <label htmlFor="quick-waste-category">{t('master.category')}<select id="quick-waste-category" value={category} disabled={pending}
        onChange={event => changeCategory(event.target.value)}>
        {['SPICE', 'HONEY', 'OTHER'].map(option => <option key={option} value={option}>{t('master.' + option)}</option>)}
      </select></label>
      <label htmlFor="quick-waste-tracking">{t('master.trackingType')}<select id="quick-waste-tracking" value={trackingType}
        disabled={pending || category !== 'OTHER'} onChange={event => setTrackingType(event.target.value)}>
        {['WEIGHT', 'PIECE'].map(option => <option key={option} value={option}>{t('master.' + option)}</option>)}
      </select></label>
      <label htmlFor="quick-waste-price">{t('master.price')}<input id="quick-waste-price" required inputMode="decimal"
        pattern="[0-9]+([.][0-9]{1,2})?" maxLength={19} value={price} disabled={pending}
        onChange={event => setPrice(event.target.value)} /></label>
      <label htmlFor="quick-waste-cost">{t('master.defaultCost')}<input id="quick-waste-cost" inputMode="decimal"
        pattern="[0-9]+([.][0-9]{1,6})?" maxLength={19} value={defaultCost} disabled={pending}
        onChange={event => setDefaultCost(event.target.value)} /></label>
      {error && <p role="alert" className="error">{t('errors.' + error.code, { defaultValue: t('errors.SERVER_ERROR') })}</p>}
      <div className="form-actions"><button className="primary-button" disabled={pending || !name.trim() || !code.trim() || !price}>
        {t(pending ? 'master.saving' : 'operations.saveWasteProduct')}</button>
        <button type="button" disabled={pending} onClick={onClose}>{t('sales.cancelNewCustomer')}</button></div>
    </form>
  </section>
}
