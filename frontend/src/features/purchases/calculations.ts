// Integer-scaled preview math; the backend remains authoritative.
import { moneyFromCents, moneyCents } from '../../utils/money'
export { moneyFromCents } from '../../utils/money'
function scaled(value: string, scale: number): bigint | null {
  if (!new RegExp('^[0-9]{1,16}(\\.[0-9]{1,' + scale + '})?$').test(value)) return null
  const [whole, fraction = ''] = value.split('.')
  return BigInt(whole) * 10n ** BigInt(scale) + BigInt(fraction.padEnd(scale, '0'))
}
export function lineCents(quantity: string, cost: string): bigint | null {
  const q = scaled(quantity, 3), c = scaled(cost, 6)
  if (q === null || c === null) return null
  return (q * c + 5000000n) / 10000000n
}
export function preview(items: { quantity: string; unitCost: string }[], paid: string) {
  const lines = items.map(item => lineCents(item.quantity, item.unitCost))
  if (lines.some(value => value === null)) return null
  const total = lines.reduce<bigint>((sum, value) => sum + value!, 0n)
  const payment = moneyCents(paid)
  return { total: moneyFromCents(total), outstanding: payment === null ? null : moneyFromCents(total - payment), overpaid: payment !== null && payment > total }
}
