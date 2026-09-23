import { moneyCents, moneyFromCents } from '../../utils/money'
import type { SaleLine } from './types'
export function linePreview(line: SaleLine) {
  const price = moneyCents(line.unitPrice)
  if (price === null || price <= 0n) return null
  let quantity: bigint, total: bigint
  if (line.mode === 'BY_AMOUNT') {
    const amount = moneyCents(line.requestedAmount)
    if (amount === null || amount <= 0n) return null
    quantity = (amount * 1000n * 2n + price) / (2n * price)
    total = amount
  } else {
    if (!/^[0-9]{1,12}(\.[0-9]{1,3})?$/.test(line.quantity)) return null
    const [whole, fraction = ''] = line.quantity.split('.')
    quantity = BigInt(whole) * 1000n + BigInt(fraction.padEnd(3, '0'))
    if (line.mode === 'BY_PIECE' && quantity % 1000n !== 0n) return null
    total = (quantity * price + 500n) / 1000n
  }
  if (quantity <= 0n || total <= 0n || quantity >= 10n ** 18n || total >= 10n ** 18n) return null
  return { quantity: (quantity / 1000n).toString() + '.' + (quantity % 1000n).toString().padStart(3, '0'), total: moneyFromCents(total), cents: total }
}
export function preview(lines: SaleLine[], paidNow: string) {
  const values = lines.map(linePreview)
  if (!values.length || values.some(value => !value)) return null
  const total = values.reduce((sum, value) => sum + value!.cents, 0n)
  const paid = moneyCents(paidNow)
  if (paid === null || total >= 10n ** 18n) return null
  return { total: moneyFromCents(total), outstanding: moneyFromCents(total - paid), overpaid: paid > total }
}
