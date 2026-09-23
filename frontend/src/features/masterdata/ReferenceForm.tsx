import { useEffect, useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { useQuery } from '@tanstack/react-query'
import { api, ApiError } from '../../api/client'
import { useAuth } from '../../auth/AuthProvider'
import { localDrafts, saveMasterDataDraft } from '../../db/masterDataDrafts'
import { initialInput, payload, type Catalog, type ReferenceInput, type ReferenceRecord, type ReferencePage } from './catalogs'
import { useDebounced } from './useDebounced'
import { useFormUpdateGuard } from '../../app/pwa/formUpdateGuard'

interface Props { catalog: Catalog; record?: ReferenceRecord; onSaved: (record: ReferenceRecord) => void; onClose: () => void; onSelect: (record: ReferenceRecord) => void }
export function ReferenceForm({ catalog, record, onSaved, onClose, onSelect }: Props) {
  useFormUpdateGuard(true)
  const { t } = useTranslation()
  const { user } = useAuth()
  const [input, setInput] = useState(() => initialInput(record))
  const [error, setError] = useState<ApiError | null>(null)
  const [pending, setPending] = useState(false)
  const [loaded, setLoaded] = useState(false)
  const [draftWarning, setDraftWarning] = useState(false)
  const [restored, setRestored] = useState(false)
  const key = `${user!.id}:${catalog}:${record?.id ?? 'new'}`
  useEffect(() => {
    let current = true
    localDrafts.masterDataDrafts.get(key).then(draft => {
      if (current && draft) { setInput(draft.input); setRestored(true) }
    }).catch(() => { if (current) setDraftWarning(true) })
      .finally(() => { if (current) setLoaded(true) })
    return () => { current = false }
  }, [key])
  const phone = useDebounced(input.phone)
  const matches = useQuery({ queryKey: ['customer-phone', user?.id, phone],
    queryFn: () => api<ReferencePage>(`/customers?phone=${encodeURIComponent(phone)}`),
    enabled: catalog === 'customers' && phone.replace(/[^0-9٠-٩۰-۹]/g, '').length >= 6, retry: false })
  const existing = matches.data?.items.find(item => item.id !== record?.id)
  function change<K extends keyof ReferenceInput>(field: K, value: ReferenceInput[K]) {
    const next = { ...input, [field]: value }
    if (field === 'category' && value === 'SPICE') next.trackingType = 'WEIGHT'
    if (field === 'category' && value === 'HONEY') next.trackingType = 'PIECE'
    setInput(next)
    void saveMasterDataDraft(key, next).catch(() => setDraftWarning(true))
  }
  async function save(event: FormEvent) {
    event.preventDefault()
    if (pending || !loaded || existing) return
    setPending(true); setError(null)
    try {
      const result = await api<ReferenceRecord>(`/${catalog}${record ? '/' + record.id : ''}`, {
        method: record ? 'PUT' : 'POST', body: JSON.stringify(payload(catalog, input)),
      })
      await localDrafts.masterDataDrafts.delete(key).catch(() => undefined)
      onSaved(result)
    } catch (failure) { setError(failure instanceof ApiError ? failure : new ApiError('SERVER_ERROR')) }
    finally { setPending(false) }
  }
  function field(name: 'name' | 'code' | 'phone' | 'address' | 'notes' | 'price', required = false) {
    const id = 'reference-' + name
    return <div className="form-field"><label htmlFor={id}>{t('master.' + name)}</label>
      <input id={id} value={input[name]} required={required} disabled={!loaded || pending}
        maxLength={name === 'notes' ? 2000 : name === 'address' ? 500 : name === 'phone' || name === 'code' ? 60 : name === 'price' ? 19 : 200}
        inputMode={name === 'price' ? 'decimal' : name === 'phone' ? 'tel' : 'text'}
        pattern={name === 'price' ? '[0-9]+([.][0-9]{1,2})?' : undefined}
        aria-invalid={!!error?.fieldErrors[name]} aria-describedby={error?.fieldErrors[name] ? id + '-error' : undefined}
        onChange={event => change(name, event.target.value)} />
      {error?.fieldErrors[name] && <span id={id + '-error'} className="field-error">{t('errors.INVALID_FIELD')}</span>}
    </div>
  }
  return <section className="reference-editor" aria-labelledby="editor-title">
    <h2 id="editor-title">{t(record ? 'master.edit' : 'master.add')}</h2>
    {restored && <p role="status">{t('master.draftRestored')}</p>}
    {draftWarning && <p role="alert">{t('master.draftWarning')}</p>}
    <form onSubmit={save}>
      {field('name', true)}
      {catalog === 'products' && <>
        {field('code', true)}
        <label htmlFor="category">{t('master.category')}</label>
        <select id="category" value={input.category} disabled={!loaded || pending} onChange={event => change('category', event.target.value)}>
          {['SPICE', 'HONEY', 'OTHER'].map(value => <option key={value} value={value}>{t('master.' + value)}</option>)}
        </select>
        <label htmlFor="trackingType">{t('master.trackingType')}</label>
        <select id="trackingType" value={input.trackingType} disabled={!loaded || pending || input.category !== 'OTHER'} onChange={event => change('trackingType', event.target.value)}>
          {['WEIGHT', 'PIECE'].map(value => <option key={value} value={value}>{t('master.' + value)}</option>)}
        </select>
      </>}
      {(catalog === 'products' || catalog === 'container-types') && field('price', true)}
      {(catalog === 'customers' || catalog === 'suppliers') && <>{field('phone')}{field('address')}</>}
      {!['payment-methods', 'expense-categories'].includes(catalog) && field('notes')}
      {existing && <aside className="duplicate" role="status">
        <p>{t('master.phoneMatch', { name: existing.name })}</p>
        <button type="button" onClick={() => onSelect(existing)}>{t('master.selectExisting')}</button>
      </aside>}
      <label className="checkbox"><input type="checkbox" checked={input.active} disabled={!loaded || pending} onChange={event => change('active', event.target.checked)} />{t('auth.active')}</label>
      {catalog === 'payment-methods' && <label className="checkbox"><input type="checkbox" checked={input.isDefault} disabled={!loaded || pending} onChange={event => change('isDefault', event.target.checked)} />{t('master.isDefault')}</label>}
      {error && <p role="alert" className="error">{t('errors.' + error.code, { defaultValue: t('errors.SERVER_ERROR') })}</p>}
      <div className="form-actions"><button className="primary-button" disabled={pending || !loaded || !!existing}>{t(pending ? 'master.saving' : 'master.save')}</button>
        <button type="button" disabled={pending} onClick={onClose}>{t('master.close')}</button>
        <button type="button" disabled={pending || !loaded} onClick={() => {
          if (window.confirm(t('master.discardConfirm'))) {
            void localDrafts.masterDataDrafts.delete(key).then(onClose).catch(() => setDraftWarning(true))
          }
        }}>{t('master.discard')}</button></div>
    </form>
  </section>
}
