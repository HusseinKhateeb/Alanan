export const catalogs = ['products', 'customers', 'suppliers', 'container-types', 'payment-methods', 'expense-categories'] as const
export type Catalog = typeof catalogs[number]
export interface ReferenceRecord {
  id: string; name: string; active: boolean; version: number
  code?: string; category?: string; tracking_type?: string
  default_selling_price?: string; default_price?: string
  phone?: string; address?: string; notes?: string; is_default?: boolean; is_walk_in?: boolean
}
export interface ReferencePage { items: ReferenceRecord[]; hasMore: boolean }
export interface ReferenceInput {
  name: string; code: string; category: string; trackingType: string; price: string
  phone: string; address: string; notes: string; active: boolean; isDefault: boolean; version: number
}
export function initialInput(record?: ReferenceRecord): ReferenceInput {
  return { name: record?.name ?? '', code: record?.code ?? '', category: record?.category ?? 'SPICE',
    trackingType: record?.tracking_type ?? 'WEIGHT', price: record?.default_selling_price ?? record?.default_price ?? '',
    phone: record?.phone ?? '', address: record?.address ?? '', notes: record?.notes ?? '',
    active: record?.active ?? true, isDefault: record?.is_default ?? false, version: record?.version ?? 0 }
}
export function canRead(catalog: Catalog, permissions: string[]) {
  if (permissions.includes('SETTINGS_MANAGE')) return true
  if (catalog === 'products') return ['SALES_CREATE', 'PURCHASES_CREATE', 'INVENTORY_ADJUST'].some(p => permissions.includes(p))
  if (catalog === 'payment-methods') return ['CUSTOMER_PAYMENTS_CREATE', 'SALES_CREATE', 'PURCHASES_CREATE', 'EXPENSES_CREATE', 'SUPPLIER_PAYMENTS_CREATE'].some(p => permissions.includes(p))
  if (catalog === 'customers') return ['SALES_CREATE', 'CUSTOMER_PAYMENTS_CREATE'].some(p => permissions.includes(p))
  if (catalog === 'suppliers') return ['PURCHASES_VIEW', 'PURCHASES_CREATE', 'SUPPLIER_PAYMENTS_CREATE'].some(p => permissions.includes(p))
  if (catalog === 'expense-categories') return permissions.includes('EXPENSES_CREATE')
  return permissions.includes('SALES_CREATE')
}
export function payload(catalog: Catalog, input: ReferenceInput) {
  const common = { name: input.name, active: input.active, version: input.version }
  if (catalog === 'products') return { ...common, code: input.code, category: input.category, trackingType: input.trackingType, price: input.price, notes: input.notes }
  if (catalog === 'customers' || catalog === 'suppliers') return { ...common, phone: input.phone, address: input.address, notes: input.notes }
  if (catalog === 'container-types') return { ...common, price: input.price, notes: input.notes }
  if (catalog === 'payment-methods') return { ...common, isDefault: input.isDefault }
  return common
}
