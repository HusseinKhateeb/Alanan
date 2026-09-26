import { useTranslation } from 'react-i18next'
import { ReferenceSelector } from '../purchases/ReferenceSelector'
import { linePreview } from './calculations'
import type { SaleLine, SaleMode } from './types'
interface Props { item: SaleLine; index: number; disabled: boolean; canRemove: boolean; autoFocusFirst?: boolean; onChange: (item: SaleLine) => void; onRemove: () => void; onNext: () => void }
export function SaleLineEditor({ item, index, disabled, canRemove, autoFocusFirst = true, onChange, onRemove, onNext }: Props) {
  const { t } = useTranslation()
  const result = linePreview(item)
  const weight = item.kind === 'PRODUCT' && item.reference?.tracking_type === 'WEIGHT'
  return <fieldset disabled={disabled} className="sale-line purchase-header-fields"><legend>{t('purchase.line', { number: index + 1 })}</legend>
    <div className="choice-field"><span>{t('sales.kind')}</span><div className="segmented-control" role="group" aria-label={t('sales.kind')}>
      {(['PRODUCT', 'CONTAINER'] as const).map(kind => <button type="button" key={kind} aria-pressed={item.kind === kind}
        onClick={() => onChange({ ...item, kind, reference: null, mode: kind === 'CONTAINER' ? 'BY_PIECE' : 'BY_WEIGHT', quantity: '1', requestedAmount: '', unitPrice: '' })}>{t('sales.' + kind)}</button>)}
    </div></div>
    <ReferenceSelector key={item.kind} kind={item.kind === 'PRODUCT' ? 'products' : 'container-types'} id={'sale-line-' + item.localId} value={item.reference} disabled={disabled} quickPick autoFocus={autoFocusFirst && index === 0}
      onChange={reference => { onChange({ ...item, reference, mode: reference?.tracking_type === 'WEIGHT' ? 'BY_WEIGHT' : 'BY_PIECE', unitPrice: reference?.default_selling_price ?? reference?.default_price ?? '', requestedAmount: '' });
        if (reference) requestAnimationFrame(() => document.getElementById('sale-line-' + item.localId + '-value')?.focus()) }} />
    {item.kind === 'CONTAINER' && <p>{t('sales.containerHelp')}</p>}
    {weight && <div className="choice-field"><span>{t('sales.mode')}</span><div className="segmented-control" role="group" aria-label={t('sales.mode')}>
      {(['BY_WEIGHT', 'BY_AMOUNT'] as SaleMode[]).map(mode => <button type="button" key={mode} aria-pressed={item.mode === mode}
        onClick={() => onChange({ ...item, mode })}>{t('sales.' + mode)}</button>)}
    </div></div>}
    <label>{t(weight ? 'sales.priceKg' : 'sales.pricePiece')}<input required inputMode="decimal" pattern="[0-9]+([.][0-9]{1,2})?" maxLength={15} value={item.unitPrice} onChange={event => onChange({ ...item, unitPrice: event.target.value })} /></label>
    {item.mode === 'BY_AMOUNT' ? <label>{t('sales.requestedAmount')}<input id={'sale-line-' + item.localId + '-value'} required inputMode="decimal" pattern="[0-9]+([.][0-9]{1,2})?" maxLength={19} value={item.requestedAmount} onChange={event => onChange({ ...item, requestedAmount: event.target.value })}
        onKeyDown={event => { if (event.key === 'Enter' && result) { event.preventDefault(); onNext() } }} /></label>
      : <label>{t(weight ? 'sales.kg' : 'sales.pieces')}<input id={'sale-line-' + item.localId + '-value'} required inputMode="decimal" pattern={weight ? '[0-9]+([.][0-9]{1,3})?' : '[0-9]+'} maxLength={16} value={item.quantity} onChange={event => onChange({ ...item, quantity: event.target.value })}
        onKeyDown={event => { if (event.key === 'Enter' && result) { event.preventDefault(); onNext() } }} /></label>}
    {item.mode === 'BY_AMOUNT' && <p>{t('sales.kg')}: {result?.quantity ?? '—'}</p>}
    <strong>{t('purchase.lineTotal')}: {result?.total ?? '—'} ₪</strong>
    <button type="button" disabled={disabled || !canRemove} onClick={onRemove}>{t('purchase.removeLine')}</button>
  </fieldset>
}
