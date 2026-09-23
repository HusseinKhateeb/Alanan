export function moneyFromCents(value: bigint) {
  const negative = value < 0n
  const absolute = negative ? -value : value
  return (negative ? '-' : '') + (absolute / 100n).toString() + '.' + (absolute % 100n).toString().padStart(2, '0')
}
export function moneyCents(value: string): bigint | null {
  if (!/^[0-9]{1,16}(\.[0-9]{1,2})?$/.test(value)) return null
  const [whole, fraction = ''] = value.split('.')
  return BigInt(whole) * 100n + BigInt(fraction.padEnd(2, '0'))
}
