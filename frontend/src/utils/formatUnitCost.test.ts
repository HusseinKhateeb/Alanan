import { expect, test } from 'vitest'
import { formatUnitCost } from './formatUnitCost'

test('shows cents and preserves meaningful six-digit cost precision', () => {
  expect(formatUnitCost('25.000000')).toBe('25.00')
  expect(formatUnitCost('25')).toBe('25.00')
  expect(formatUnitCost('25.125000')).toBe('25.125')
  expect(formatUnitCost('0.000001')).toBe('0.000001')
})
