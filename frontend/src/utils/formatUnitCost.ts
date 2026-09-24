/** Show at least cents without hiding meaningful purchase-cost precision. */
export function formatUnitCost(value: string): string {
  if (!/^\d+(?:\.\d{1,6})?$/.test(value)) return value
  const [whole, fraction = ''] = value.split('.')
  let decimals = fraction.padEnd(2, '0')
  while (decimals.length > 2 && decimals.endsWith('0')) decimals = decimals.slice(0, -1)
  return `${whole}.${decimals}`
}
