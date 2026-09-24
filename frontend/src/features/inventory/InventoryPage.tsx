import { useState, type FormEvent } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { useAuth } from '../../auth/AuthProvider'
import { api, ApiError } from '../../api/client'
import { ReferenceSelector } from '../purchases/ReferenceSelector'
import type { ReferenceRecord } from '../masterdata/catalogs'
import type { Page } from '../purchases/types'
import { HardDeleteControl } from '../cancellations/HardDeleteControl'
import { businessToday } from '../../utils/businessDate'
interface Stock { id: string; name: string; code: string; tracking_type: string; current_stock: string; current_wac?: string | null }
interface Movement { id: string; movement_type: string; reference_type: string; quantity_change: string; unit_cost?: string; notes?: string; reference_id: string; invoice_number: string | null }
export function InventoryPage() {
  const { user } = useAuth()
  const { t } = useTranslation()
  const [page, setPage] = useState(0)
  const [selected, setSelected] = useState<Stock | null>(null)
  const allowed = ['PURCHASES_VIEW', 'PURCHASES_CREATE', 'INVENTORY_ADJUST', 'COST_VIEW'].some(p => user?.permissions.includes(p))
  const query = useQuery({ queryKey: ['inventory', user!.id, page], queryFn: () => api<Page<Stock>>('/inventory?page=' + page), enabled: allowed, retry: false })
  if (!allowed) return <main><p role="alert">{t('errors.FORBIDDEN')}</p></main>
  return <main><Link to="/">{t('home')}</Link><h1>{t('purchase.inventory')}</h1><p>{t('purchase.approximateStock')}</p>
    {user?.permissions.includes('INVENTORY_ADJUST') && <OpeningStockForm />}
    {query.isPending && <p role="status">{t('master.loading')}</p>}
    {query.error && <p role="alert">{t('errors.' + (query.error instanceof ApiError ? query.error.code : 'SERVER_ERROR'))}</p>}
    {query.data?.items.length === 0 && <p>{t('master.empty')}</p>}
    <div className="reference-list">{query.data?.items.map(product => <article key={product.id}><div><strong>{product.name}</strong>
      <span>{product.code} · {product.current_stock} {t(product.tracking_type === 'WEIGHT' ? 'master.WEIGHT' : 'master.PIECE')}</span>
      {user?.permissions.includes('COST_VIEW') && <span>{t('purchase.wac')}: {product.current_wac ?? t('purchase.unknownCost')} {product.current_wac ? '₪' : ''}</span>}
      <button onClick={() => setSelected(product)}>{t('purchase.movements')}</button>
    </div></article>)}</div>
    <div className="pagination"><button disabled={page === 0 || query.isFetching} onClick={() => setPage(page - 1)}>{t('auth.previous')}</button>
      <button disabled={!query.data?.hasMore || query.isFetching} onClick={() => setPage(page + 1)}>{t('auth.next')}</button></div>
    {selected && <Movements key={selected.id} product={selected} />}
  </main>
}
function OpeningStockForm() {
  const { t } = useTranslation()
  const client = useQueryClient()
  const [product, setProduct] = useState<ReferenceRecord | null>(null)
  const [quantity, setQuantity] = useState('')
  const [unitCost, setUnitCost] = useState('')
  const [reason, setReason] = useState('')
  const [recordedDate, setRecordedDate] = useState(businessToday)
  const [submitted, setSubmitted] = useState<object | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<ApiError | null>(null)
  const [saved, setSaved] = useState(false)
  async function save(event: FormEvent) {
    event.preventDefault()
    if (!product || busy) return
    const request = submitted ?? { requestId: crypto.randomUUID(), productId: product.id, quantity, unitCost, recordedDate, reason: reason.trim() }
    setSubmitted(request); setBusy(true); setError(null); setSaved(false)
    try {
      await api('/inventory/adjustments', { method: 'POST', body: JSON.stringify(request) })
      setSubmitted(null); setProduct(null); setQuantity(''); setUnitCost(''); setReason(''); setSaved(true)
      for (const key of ['inventory', 'dashboard', 'reports']) void client.invalidateQueries({ queryKey: [key] })
    } catch (failure) {
      const issue = failure instanceof ApiError ? failure : new ApiError('SERVER_ERROR')
      setError(issue)
      if ([400, 403].includes(issue.status)) setSubmitted(null)
    } finally { setBusy(false) }
  }
  return <details className="workspace-section"><summary>{t('inventory.addOldGoods')}</summary>
    <p>{t('inventory.openingHelp')}</p>
    <form onSubmit={save}>
      <fieldset disabled={busy || !!submitted}>
        <ReferenceSelector kind="products" id="opening-stock-product" value={product} disabled={busy || !!submitted} onChange={setProduct} />
        <label>{t('purchase.quantity')}<input required inputMode="decimal" pattern="[0-9]+([.][0-9]{1,3})?" value={quantity} onChange={event => setQuantity(event.target.value)} /></label>
        <label>{t('purchase.unitCost')}<input required inputMode="decimal" pattern="[0-9]+([.][0-9]{1,6})?" value={unitCost} onChange={event => setUnitCost(event.target.value)} /></label>
        <label>{t('purchase.date')}<input required type="date" value={recordedDate} onChange={event => setRecordedDate(event.target.value)} /></label>
        <label>{t('inventory.reason')}<input required maxLength={500} value={reason} onChange={event => setReason(event.target.value)} /></label>
      </fieldset>
      {error && <p role="alert" className="error">{t('errors.' + error.code, { defaultValue: t('errors.SERVER_ERROR') })}</p>}
      {saved && <p role="status">{t('inventory.saved')}</p>}
      <button className="primary-button" disabled={busy || !product || !reason.trim()}>{t(submitted ? 'inventory.retry' : 'inventory.save')}</button>
    </form>
  </details>
}
function Movements({ product }: { product: Stock }) {
  const { user } = useAuth()
  const { t } = useTranslation()
  const [page, setPage] = useState(0)
  const query = useQuery({ queryKey: ['inventory', 'movements', user!.id, product.id, page], queryFn: () => api<Page<Movement>>('/inventory/movements?productId=' + product.id + '&page=' + page), retry: false })
  return <section><h2>{product.name} · {t('purchase.movements')}</h2>
    {query.isPending && <p>{t('master.loading')}</p>}
    {query.error && <p role="alert">{t('errors.SERVER_ERROR')}</p>}
    {query.data?.items.length === 0 && <p>{t('master.empty')}</p>}
    {query.data?.items.map(movement => <div key={movement.id}>
      {movement.invoice_number && user?.permissions.includes('PURCHASES_VIEW') ? <Link to={'/purchases/' + movement.reference_id}>{movement.invoice_number}</Link>
        : movement.invoice_number ?? t('inventory.' + movement.movement_type, { defaultValue: movement.movement_type })}
      {' · '}{movement.quantity_change} {t(product.tracking_type === 'WEIGHT' ? 'master.WEIGHT' : 'master.PIECE')}
      {movement.unit_cost && ' · ' + movement.unit_cost + ' ₪'}
      {movement.notes && ' · ' + movement.notes}
      {movement.reference_type === 'OPENING_STOCK' && <HardDeleteControl kind="opening-stock" id={movement.reference_id} />}
    </div>)}
    <div className="pagination"><button disabled={page === 0 || query.isFetching} onClick={() => setPage(page - 1)}>{t('auth.previous')}</button>
      <button disabled={!query.data?.hasMore || query.isFetching} onClick={() => setPage(page + 1)}>{t('auth.next')}</button></div>
  </section>
}
