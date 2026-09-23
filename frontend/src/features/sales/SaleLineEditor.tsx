import { useTranslation } from 'react-i18next'
import { ReferenceSelector } from '../purchases/ReferenceSelector'
import { linePreview } from './calculations'
import type { SaleLine, SaleMode } from './types'
interface Props { item: SaleLine; index: number; disabled: boolean; canRemove: boolean; onChange: (item: SaleLine) => void; onRemove: () => void }
export function SaleLineEditor({ item, index, disabled, canRemove, onChange, onRemove }: Props) {
  const { t } = useTranslation()
  const result = linePreview(item)
  const weight = item.kind === 'PRODUCT' && item.reference?.tracking_type === 'WEIGHT'
  return <fieldset disabled={disabled} className="purchase-header-fields"><legend>{t('purchase.line', { number: index + 1 })}</legend>
    <label>{t('sales.kind')}<select value={item.kind} onChange={event => onChange({ ...item, kind: event.target.value as SaleLine['kind'], reference: null, mode: event.target.value === 'CONTAINER' ? 'BY_PIECE' : 'BY_WEIGHT', quantity: '1', requestedAmount: '', unitPrice: '' })}>
      <option value="PRODUCT">{t('sales.PRODUCT')}</option><option value="CONTAINER">{t('sales.CONTAINER')}</option>
    </select></label>
    <ReferenceSelector key={item.kind} kind={item.kind === 'PRODUCT' ? 'products' : 'container-types'} id={'sale-line-' + item.localId} value={item.reference} disabled={disabled}
      onChange={reference => onChange({ ...item, reference, mode: reference?.tracking_type === 'WEIGHT' ? 'BY_WEIGHT' : 'BY_PIECE', unitPrice: reference?.default_selling_price ?? reference?.default_price ?? '', requestedAmount: '' })} />
    {item.kind === 'CONTAINER' && <p>{t('sales.containerHelp')}</p>}
    {weight && <label>{t('sales.mode')}<select value={item.mode} onChange={event => onChange({ ...item, mode: event.target.value as SaleMode })}>
      <option value="BY_WEIGHT">{t('sales.BY_WEIGHT')}</option><option value="BY_AMOUNT">{t('sales.BY_AMOUNT')}</option>
    </select></label>}
    <label>{t(weight ? 'sales.priceKg' : 'sales.pricePiece')}<input required inputMode="decimal" pattern="[0-9]+([.][0-9]{1,2})?" maxLength={15} value={item.unitPrice} onChange={event => onChange({ ...item, unitPrice: event.target.value })} /></label>
    {item.mode === 'BY_AMOUNT' ? <label>{t('sales.requestedAmount')}<input required inputMode="decimal" pattern="[0-9]+([.][0-9]{1,2})?" maxLength={19} value={item.requestedAmount} onChange={event => onChange({ ...item, requestedAmount: event.target.value })} /></label>
      : <label>{t(weight ? 'sales.kg' : 'sales.pieces')}<input required inputMode="decimal" pattern={weight ? '[0-9]+([.][0-9]{1,3})?' : '[0-9]+'} maxLength={16} value={item.quantity} onChange={event => onChange({ ...item, quantity: event.target.value })} /></label>}
    {item.mode === 'BY_AMOUNT' && <p>{t('sales.kg')}: {result?.quantity ?? '—'}</p>}
    <strong>{t('purchase.lineTotal')}: {result?.total ?? '—'} ₪</strong>
    <button type="button" disabled={disabled || !canRemove} onClick={onRemove}>{t('purchase.removeLine')}</button>
  </fieldset>
}
