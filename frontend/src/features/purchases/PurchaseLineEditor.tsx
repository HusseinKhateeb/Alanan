import { useTranslation } from 'react-i18next'
import { ReferenceSelector } from './ReferenceSelector'
import { lineCents, moneyFromCents } from './calculations'
import type { PurchaseItem } from './types'
import { formatUnitCost } from '../../utils/formatUnitCost'
interface Props { item: PurchaseItem; index: number; disabled: boolean; canRemove: boolean; onChange: (item: PurchaseItem) => void; onRemove: () => void; onNext: () => void; errors: Record<string, string> }
export function PurchaseLineEditor({ item, index, disabled, canRemove, onChange, onRemove, onNext, errors }: Props) {
  const { t } = useTranslation()
  const total = lineCents(item.quantity, item.unitCost)
  const quantityError = errors[`items[${index}].quantity`], costError = errors[`items[${index}].unitCost`]
  return <fieldset className="purchase-line" disabled={disabled}>
    <legend>{t('purchase.line', { number: index + 1 })}</legend>
    <ReferenceSelector kind="products" combined id={'product-' + item.localId} value={item.product} disabled={disabled} onChange={product => {
      onChange({ ...item, product, unitCost: product?.default_purchase_cost ? formatUnitCost(product.default_purchase_cost) : '' })
      if (product) requestAnimationFrame(() => document.getElementById('purchase-quantity-' + item.localId)?.focus())
    }} />
    <div className="purchase-line-numbers">
      <label>{t('purchase.quantity')} ({t(item.product?.tracking_type === 'PIECE' ? 'master.PIECE' : 'master.WEIGHT')})
        <input id={'purchase-quantity-' + item.localId} required inputMode="decimal" value={item.quantity} maxLength={16}
          pattern={item.product?.tracking_type === 'PIECE' ? '[0-9]+' : '[0-9]+([.][0-9]{1,3})?'}
          aria-invalid={!!quantityError} onChange={event => onChange({ ...item, quantity: event.target.value })} />
        {quantityError && <span className="field-error">{t('errors.INVALID_FIELD')}</span>}
      </label>
      <label>{t('purchase.unitCost')} (₪)
        <input required inputMode="decimal" value={item.unitCost} maxLength={19} pattern="[0-9]+([.][0-9]{1,6})?"
          aria-invalid={!!costError} onChange={event => onChange({ ...item, unitCost: event.target.value })}
          onKeyDown={event => { if (event.key === 'Enter' && item.product && total !== null) { event.preventDefault(); onNext() } }} />
        {costError && <span className="field-error">{t('errors.INVALID_FIELD')}</span>}
      </label>
      <output>{t('purchase.lineTotal')}: {total === null ? '—' : moneyFromCents(total)} ₪</output>
    </div>
    <button type="button" disabled={!canRemove || disabled} onClick={onRemove}>{t('purchase.removeLine')}</button>
  </fieldset>
}
