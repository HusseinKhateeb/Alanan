import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { api, ApiError } from '../../api/client'
import { useAuth } from '../../auth/AuthProvider'
import { moneyCents } from '../../utils/money'
import type { Page } from '../purchases/types'
import type { DraftAllocation, OpenInvoice } from './types'
interface Props { customerId: string; allocations: DraftAllocation[]; disabled: boolean; onChange: (allocations: DraftAllocation[]) => void }
export function InvoiceAllocations({ customerId, allocations, disabled, onChange }: Props) {
  const { t } = useTranslation()
  const { user } = useAuth()
  const [page, setPage] = useState(0)
  const query = useQuery({ queryKey: ['customer-payment-open-invoices', user!.id, customerId, page],
    queryFn: () => api<Page<OpenInvoice>>('/customer-payments/open-invoices?customerId=' + customerId + '&page=' + page), retry: false })
  function change(invoice: OpenInvoice, amount: string) {
    const remaining = allocations.filter(item => item.invoiceId !== invoice.id)
    onChange(amount === '' || moneyCents(amount) === 0n ? remaining : [...remaining, { invoiceId: invoice.id, invoiceNumber: invoice.invoice_number, amount }])
  }
  return <section>
    <h2>{t('customerPayments.openInvoices')}</h2><p>{t('customerPayments.manualHelp')}</p>
    {query.isPending && <p role="status">{t('master.loading')}</p>}
    {query.error && <p role="alert">{t('errors.' + (query.error instanceof ApiError ? query.error.code : 'SERVER_ERROR'))}</p>}
    {query.data?.items.length === 0 && <p>{t('customerPayments.noOpenInvoices')}</p>}
    <div className="reference-list">{query.data?.items.map(invoice => {
      const value = allocations.find(item => item.invoiceId === invoice.id)?.amount ?? ''
      const entered = moneyCents(value), outstanding = moneyCents(invoice.outstanding)
      const exceeded = entered !== null && outstanding !== null && entered > outstanding
      return <article key={invoice.id}><div>
        <strong>{invoice.invoice_number}</strong><span>{invoice.invoice_date}</span>
        <span>{t('purchase.outstanding')}: {invoice.outstanding} ₪</span>
        <label>{t('customerPayments.allocateTo', { number: invoice.invoice_number })}
          <input disabled={disabled} inputMode="decimal" pattern="[0-9]+([.][0-9]{1,2})?" maxLength={19}
            value={value} aria-invalid={exceeded} onChange={event => change(invoice, event.target.value)} />
        </label>
        {exceeded && <span className="field-error">{t('errors.ALLOCATION_EXCEEDS_OUTSTANDING')}</span>}
      </div></article>
    })}</div>
    <div className="pagination"><button type="button" disabled={page === 0 || query.isFetching} onClick={() => setPage(page - 1)}>{t('auth.previous')}</button>
      <button type="button" disabled={!query.data?.hasMore || query.isFetching} onClick={() => setPage(page + 1)}>{t('auth.next')}</button>
      <button type="button" disabled={query.isFetching} onClick={() => void query.refetch()}>{t('retry')}</button></div>
    {allocations.length > 0 && <><h3>{t('customerPayments.selectedAllocations')}</h3>
      <ul>{allocations.map(item => <li key={item.invoiceId}>{item.invoiceNumber} · {item.amount} ₪{' '}
        <button type="button" disabled={disabled} onClick={() => onChange(allocations.filter(selected => selected.invoiceId !== item.invoiceId))}>{t('customerPayments.removeAllocation')}</button>
      </li>)}</ul></>}
  </section>
}
