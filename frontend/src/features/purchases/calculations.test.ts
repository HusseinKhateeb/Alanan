import { expect, test } from 'vitest'
import { lineCents, preview } from './calculations'
test('rounds each line half up without floating point drift', () => {
  expect(lineCents('0.125', '1')).toBe(13n)
  expect(preview([{ quantity: '20', unitCost: '18' }, { quantity: '10', unitCost: '35' }, { quantity: '20', unitCost: '40' }], '1000')).toEqual({ total: '1510.00', outstanding: '510.00', overpaid: false })
  expect(preview([{ quantity: '1', unitCost: '0.1' }, { quantity: '1', unitCost: '0.2' }], '0')).toEqual({ total: '0.30', outstanding: '0.30', overpaid: false })
})
test('rejects invalid precision and reports overpayments', () => {
  expect(lineCents('1.0001', '2')).toBeNull()
  expect(lineCents('1', '-2')).toBeNull()
  expect(preview([{ quantity: '1', unitCost: '2' }], '3')?.overpaid).toBe(true)
})
