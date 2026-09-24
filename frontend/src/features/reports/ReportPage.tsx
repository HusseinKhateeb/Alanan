import { useState, type FormEvent } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Link, useParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { api, ApiError, downloadCsv } from '../../api/client'
import { useAuth } from '../../auth/AuthProvider'

type Kind = 'sales' | 'purchases' | 'expenses' | 'suppliers' | 'profit'
interface Report { from: string; to: string; totals: Record<string, string | number>; rows: { items: Record<string, string>[]; hasMore: boolean } }
const kinds: Kind[] = ['sales', 'purchases', 'expenses', 'suppliers', 'profit']
const columns: Record<Kind, string[]> = {
  sales: ['invoice_date', 'invoice_number', 'customer_name', 'status', 'total', 'paid', 'outstanding'],
  purchases: ['invoice_date', 'invoice_number', 'supplier_name', 'status', 'total', 'paid', 'outstanding'],
  expenses: ['expense_date', 'category_name', 'payment_method_name', 'amount', 'notes'],
  suppliers: ['name', 'purchased', 'paid', 'current_outstanding'],
  profit: ['invoice_date', 'invoice_number', 'customer_name', 'known_revenue', 'known_cost', 'unknown_cost_revenue', 'known_gross_profit'],
}
const totals: Record<Kind, string[]> = {
  sales: ['invoice_count', 'total', 'paid', 'outstanding'],
  purchases: ['invoice_count', 'total', 'paid', 'outstanding'],
  expenses: ['expense_count', 'total'],
  suppliers: ['supplier_count', 'purchased', 'paid', 'current_outstanding'],
  profit: ['known_revenue', 'known_cost', 'known_gross_profit', 'unknown_cost_revenue', 'expenses', 'known_margin_less_expenses'],
}
const money = new Set(['total', 'paid', 'outstanding', 'amount', 'purchased', 'current_outstanding', 'known_revenue', 'known_cost', 'known_gross_profit', 'unknown_cost_revenue', 'expenses', 'known_margin_less_expenses'])

function today() {
  const parts = new Intl.DateTimeFormat('en-US', { timeZone: 'Asia/Hebron', year: 'numeric', month: '2-digit', day: '2-digit' }).formatToParts(new Date())
  const value = (type: string) => parts.find(part => part.type === type)?.value ?? ''
  return `${value('year')}-${value('month')}-${value('day')}`
}
function permitted(kind: Kind, permissions: string[]) {
  if (kind === 'sales') return permissions.includes('SALES_VIEW')
  if (kind === 'purchases' || kind === 'suppliers') return permissions.includes('PURCHASES_VIEW')
  if (kind === 'expenses') return permissions.includes('EXPENSES_CREATE')
  return ['SALES_VIEW', 'COST_VIEW', 'EXPENSES_CREATE'].every(permission => permissions.includes(permission))
}
export function ReportPage() {
  const { t } = useTranslation()
  const { kind } = useParams()
  if (!kinds.includes(kind as Kind)) return <main><p role="alert">{t('notFound')}</p><Link to="/">{t('home')}</Link></main>
  return <ReportView key={kind} kind={kind as Kind} />
}
function ReportView({ kind }: { kind: Kind }) {
  const { t } = useTranslation()
  const { user } = useAuth()
  const current = today()
  const [from, setFrom] = useState(current.slice(0, 7) + '-01')
  const [to, setTo] = useState(current)
  const [period, setPeriod] = useState({ from, to })
  const [page, setPage] = useState(0)
  const [dateError, setDateError] = useState(false)
  const [exporting, setExporting] = useState(false)
  const [exportError, setExportError] = useState<ApiError | null>(null)
  const allowed = permitted(kind, user?.permissions ?? [])
  const report = useQuery({ queryKey: ['report', user?.id, kind, period.from, period.to, page], enabled: allowed, retry: false,
    queryFn: () => api<Report>(`/reports/${kind}?from=${period.from}&to=${period.to}&page=${page}`) })
  function apply(event: FormEvent) {
    event.preventDefault()
    if (!from || !to || from > to) { setDateError(true); return }
    setDateError(false); setPage(0); setPeriod({ from, to })
  }
  async function exportReport() {
    if(exporting) return
    setExporting(true); setExportError(null)
    try { await downloadCsv(`/reports/${kind}/csv?from=${period.from}&to=${period.to}`, `alanan-${kind}-${period.from}-${period.to}.csv`) }
    catch (failure) { setExportError(failure instanceof ApiError ? failure : new ApiError('SERVER_ERROR')) }
    finally { setExporting(false) }
  }
  if (!allowed) return <main><p role="alert">{t('errors.FORBIDDEN')}</p></main>
  const data = report.data
  return <main><Link to="/">{t('home')}</Link><h1>{t('reports.title')}</h1>
    <nav className="catalog-nav" aria-label={t('reports.title')}>{kinds.filter(option => permitted(option, user?.permissions ?? [])).map(option =>
      <Link key={option} to={'/reports/' + option} aria-current={option === kind ? 'page' : undefined}>{t('reports.' + option)}</Link>)}</nav>
    <h2>{t('reports.' + kind)}</h2>
    <form className="report-filters" onSubmit={apply}>
      <label>{t('reports.from')}<input type="date" required value={from} onChange={event => setFrom(event.target.value)} /></label>
      <label>{t('reports.to')}<input type="date" required value={to} onChange={event => setTo(event.target.value)} /></label>
      <button className="primary-button">{t('reports.apply')}</button>
    </form>
    <button type="button" disabled={exporting || report.isPending} onClick={() => void exportReport()}>{t('reports.exportCsv')}</button>
    {exportError && <p role="alert">{t('errors.' + exportError.code, { defaultValue: t('errors.SERVER_ERROR') })}</p>}
    {dateError && <p role="alert" className="error">{t('errors.INVALID_DATE_RANGE')}</p>}
    {report.isPending && <p role="status">{t('master.loading')}</p>}
    {report.error && <p role="alert">{t('errors.' + (report.error instanceof ApiError ? report.error.code : 'SERVER_ERROR'))}</p>}
    {data && <><p className="report-period">{data.from} – {data.to}</p>
      <div className="dashboard-cards">{totals[kind].map(key => <div key={key} className="dashboard-card"><span>{t('reports.' + key)}</span>
        <strong dir="ltr">{data.totals[key]}{money.has(key) ? ' ₪' : ''}</strong></div>)}</div>
      {kind === 'suppliers' && <p className="dashboard-note">{t('reports.supplierNote')}</p>}
      {kind === 'profit' && <p className="dashboard-note">{t('reports.profitNote')}</p>}
      <div className="report-table-wrap"><table className="report-table"><thead><tr>{columns[kind].map(key => <th key={key} scope="col">{t('reports.' + key)}</th>)}</tr></thead>
        <tbody>{data.rows.items.map((row, index) => <tr key={row.id ?? index}>{columns[kind].map(key => <td key={key}>
          {key === 'invoice_number' && row.id ? <Link to={(kind === 'purchases' ? '/purchases/' : '/sales/') + row.id}>{row[key]}</Link>
            : row[key] == null || row[key] === '' ? '—' : money.has(key) ? <span dir="ltr">{row[key]} ₪</span> : key === 'status' ? t('purchase.' + row[key]) : row[key]}
        </td>)}</tr>)}</tbody></table></div>
      {data.rows.items.length === 0 && <p>{t('master.empty')}</p>}
      <div className="pagination"><button disabled={page === 0 || report.isFetching} onClick={() => setPage(page - 1)}>{t('auth.previous')}</button>
        <button disabled={!data.rows.hasMore || report.isFetching} onClick={() => setPage(page + 1)}>{t('auth.next')}</button></div>
    </>}
  </main>
}
