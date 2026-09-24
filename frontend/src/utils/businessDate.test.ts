import { expect, test } from 'vitest'
import { businessToday } from './businessDate'

test('new records use the business date even when UTC is still on the previous day', () => {
  expect(businessToday(new Date('2026-01-01T22:30:00Z'))).toBe('2026-01-02')
})
