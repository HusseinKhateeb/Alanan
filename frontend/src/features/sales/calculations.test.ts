import { expect, test } from 'vitest'
import { newItem } from './drafts'
import { linePreview, preview } from './calculations'

test('requested money remains exact while kg rounds half up', () => {
  expect(linePreview({ ...newItem(), mode: 'BY_AMOUNT', requestedAmount: '20', unitPrice: '45' })).toEqual({ quantity: '0.444', total: '20.00', cents: 2000n })
  expect(linePreview({ ...newItem(), mode: 'BY_AMOUNT', requestedAmount: '1', unitPrice: '16' })?.quantity).toBe('0.063')
})
test('mixed lines sum exactly with a partial payment', () => {
  const lines = [
    { ...newItem(), quantity: '1.250', unitPrice: '45' },
    { ...newItem(), mode: 'BY_PIECE' as const, quantity: '2', unitPrice: '80' },
    { ...newItem(), kind: 'CONTAINER' as const, mode: 'BY_PIECE' as const, quantity: '20', unitPrice: '1.50' },
  ]
  expect(preview(lines, '100')).toEqual({ total: '246.25', outstanding: '146.25', overpaid: false })
})
test('rejects fractional pieces, rounded zero quantities and excessive totals', () => {
  expect(linePreview({ ...newItem(), mode: 'BY_PIECE', quantity: '1.5', unitPrice: '80' })).toBeNull()
  expect(linePreview({ ...newItem(), mode: 'BY_AMOUNT', requestedAmount: '0.01', unitPrice: '999' })).toBeNull()
  expect(linePreview({ ...newItem(), quantity: '999999999999', unitPrice: '999999999999' })).toBeNull()
})
