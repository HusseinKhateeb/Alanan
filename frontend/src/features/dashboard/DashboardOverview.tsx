import { useQuery } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router-dom'
import { api, ApiError } from '../../api/client'
import { useAuth } from '../../auth/AuthProvider'

interface Overview {
  asOf: string
  salesToday?: string; salesMonth?: string; containerRevenueMonth?: string
  purchasesMonth?: string; supplierDebt?: string; expensesMonth?: string
  knownCogsMonth?: string; knownGrossProfitMonth?: string; estimatedNetProfitMonth?: string
  salesTrend?: { day: string; total: string }[]
}

export function DashboardOverview() {
  const { t, i18n } = useTranslation()
  const { user } = useAuth()
  const dashboard = useQuery({ queryKey: ['dashboard', user?.id], enabled: !!user,
    queryFn: () => api<Overview>('/dashboard'), retry: false, refetchInterval: 60_000 })
  const data = dashboard.data
  const cards: { key: keyof Overview; to?: string }[] = [
    { key: 'salesToday', to: '/sales' }, { key: 'salesMonth', to: '/sales' },
    { key: 'purchasesMonth', to: '/purchases' }, { key: 'expensesMonth', to: '/expenses' },
    { key: 'supplierDebt', to: '/supplier-balances' }, { key: 'knownGrossProfitMonth' },
    { key: 'estimatedNetProfitMonth' }, { key: 'containerRevenueMonth', to: '/sales' },
  ]
  const trend = data?.salesTrend ?? []
  const max = Math.max(1, ...trend.map(point => Number(point.total)))
  return <section className="dashboard" aria-labelledby="dashboard-title">
    <div className="dashboard-heading"><div><div className="eyebrow">{t('dashboard.period')}</div><h2 id="dashboard-title">{t('dashboard.title')}</h2></div>
      <button type="button" disabled={dashboard.isFetching} onClick={() => void dashboard.refetch()}>{t('retry')}</button></div>
    {dashboard.isPending && <p role="status">{t('master.loading')}</p>}
    {dashboard.error && <p role="alert">{t('errors.' + (dashboard.error instanceof ApiError ? dashboard.error.code : 'SERVER_ERROR'))}</p>}
    {data && <>
      <div className="dashboard-cards">{cards.filter(card => typeof data[card.key] === 'string').map(card => {
        const content = <><span>{t('dashboard.' + card.key)}</span><strong dir="ltr">{data[card.key] as string} ₪</strong></>
        return card.to ? <Link key={card.key} className="dashboard-card" to={card.to}>{content}</Link>
          : <div key={card.key} className="dashboard-card">{content}</div>
      })}</div>
      {(data.knownGrossProfitMonth || data.containerRevenueMonth) && <p className="dashboard-note">{t('dashboard.profitNote')}</p>}
      {trend.length > 0 && <figure className="dashboard-chart"><figcaption>{t('dashboard.lastSevenDays')}</figcaption>
        <div className="dashboard-bars" role="list">{trend.map(point => <div key={point.day} role="listitem" className="dashboard-day"
          aria-label={`${point.day}: ${point.total} ₪`}><div className="dashboard-bar-track"><span style={{ height: `${Math.max(3, Number(point.total) / max * 100)}%` }} /></div>
          <small>{new Intl.DateTimeFormat(i18n.language, { weekday: 'short' }).format(new Date(point.day + 'T12:00:00Z'))}</small></div>)}</div>
      </figure>}
    </>}
  </section>
}
