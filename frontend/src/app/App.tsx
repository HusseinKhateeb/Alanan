import { lazy, Suspense } from 'react'
import { OfflineSales } from '../features/offline/OfflineSales'
import { NewSalePage } from '../features/sales/NewSalePage'
import { useQuery } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { Link, Route, Routes } from 'react-router-dom'
import { checkBackend } from '../api/health'
import { UpdateNotice } from './pwa/UpdateNotice'
import { AuthProvider, useAuth } from '../auth/AuthProvider'
import { LoginPage } from '../auth/LoginPage'
import { AccountControls } from '../auth/AccountControls'
import { catalogs, canRead } from '../features/masterdata/catalogs'
import { DashboardOverview } from '../features/dashboard/DashboardOverview'

const AuditPage = lazy(() => import('../features/audit/AuditPage').then(m => ({ default: m.AuditPage })))
const ReportPage = lazy(() => import('../features/reports/ReportPage').then(m => ({ default: m.ReportPage })))
const OperationEntryPage = lazy(() => import('../features/operations/OperationEntryPage').then(m => ({ default: m.OperationEntryPage })))
const OperationHistoryPage = lazy(() => import('../features/operations/OperationHistoryPage').then(m => ({ default: m.OperationHistoryPage })))
const NewCustomerPaymentPage = lazy(() => import('../features/customerpayments/NewCustomerPaymentPage').then(m => ({ default: m.NewCustomerPaymentPage })))
const CustomerPaymentHistoryPage = lazy(() => import('../features/customerpayments/PaymentHistoryPage').then(m => ({ default: m.PaymentHistoryPage })))
const CustomerPaymentDetailPage = lazy(() => import('../features/customerpayments/PaymentHistoryPage').then(m => ({ default: m.PaymentDetailPage })))
const NewSupplierPaymentPage = lazy(() => import('../features/supplierpayments/NewSupplierPaymentPage').then(m => ({ default: m.NewSupplierPaymentPage })))
const SaleListPage = lazy(() => import('../features/sales/SaleListPage').then(m => ({ default: m.SaleListPage })))
const SaleDetailPage = lazy(() => import('../features/sales/SaleDetailPage').then(m => ({ default: m.SaleDetailPage })))
const CustomerBalancesPage = lazy(() => import('../features/sales/CustomerBalancesPage').then(m => ({ default: m.CustomerBalancesPage })))
const PaymentHistoryPage = lazy(() => import('../features/supplierpayments/PaymentHistoryPage').then(m => ({ default: m.PaymentHistoryPage })))
const PaymentDetailPage = lazy(() => import('../features/supplierpayments/PaymentHistoryPage').then(m => ({ default: m.PaymentDetailPage })))
const UserDirectoryPage = lazy(() => import('../features/users/UserDirectoryPage').then(m => ({ default: m.UserDirectoryPage })))
const MasterDataPage = lazy(() => import('../features/masterdata/MasterDataPage').then(m => ({ default: m.MasterDataPage })))
const NewPurchasePage = lazy(() => import('../features/purchases/NewPurchasePage').then(m => ({ default: m.NewPurchasePage })))
const PurchaseListPage = lazy(() => import('../features/purchases/PurchaseListPage').then(m => ({ default: m.PurchaseListPage })))
const PurchaseDetailPage = lazy(() => import('../features/purchases/PurchaseDetailPage').then(m => ({ default: m.PurchaseDetailPage })))
const SupplierBalancesPage = lazy(() => import('../features/suppliers/SupplierBalancesPage').then(m => ({ default: m.SupplierBalancesPage })))
const InventoryPage = lazy(() => import('../features/inventory/InventoryPage').then(m => ({ default: m.InventoryPage })))

function Workspace() {
  const { t } = useTranslation()
  const { user } = useAuth()
  const health = useQuery({ queryKey: ['backend-health'], queryFn: checkBackend,
    refetchInterval: 60_000, retry: false })
  return <main id="main">
    <div className="workspace-hero"><div><div className="eyebrow">{t('workspace')}</div>
    <h1>{t('welcome')}</h1><p className="intro">{t('intro')}</p></div>
    <span className={`health ${health.isSuccess ? 'ready' : ''}`} role="status">{t(health.isFetching ? 'healthChecking' : health.isSuccess ? 'healthReady' : 'healthUnavailable')}</span></div>
    <section className="workspace-section" aria-labelledby="quick-actions-title"><h2 id="quick-actions-title">{t('quickActions')}</h2>
    <nav className="quick-actions" aria-label={t('quickActions')}>
      {user?.permissions.includes('SALES_CREATE') && <Link className="quick-action quick-action-primary" to="/sales/new"><span aria-hidden="true">＋</span><strong>{t('sales.new')}</strong><small>{t('quickSaleHelp')}</small></Link>}
      {user?.permissions.includes('PURCHASES_CREATE') && <Link className="quick-action" to="/purchases/new"><span aria-hidden="true">＋</span><strong>{t('purchase.new')}</strong></Link>}
      {user?.permissions.includes('EXPENSES_CREATE') && <Link className="quick-action" to="/expenses/new"><span aria-hidden="true">＋</span><strong>{t('operations.new.expenses')}</strong></Link>}
      {user?.permissions.includes('INVENTORY_ADJUST') && <Link className="quick-action" to="/waste/new"><span aria-hidden="true">＋</span><strong>{t('operations.new.waste')}</strong></Link>}
    </nav></section>
    <DashboardOverview />
    <section className="workspace-section" aria-labelledby="records-title"><h2 id="records-title">{t('recordsTitle')}</h2>
    <nav className="catalog-nav" aria-label={t('recordsTitle')}>
      {user?.permissions.includes('SALES_VIEW') && <Link to="/reports/sales">{t('reports.title')}</Link>}
      {!user?.permissions.includes('SALES_VIEW') && user?.permissions.includes('PURCHASES_VIEW') && <Link to="/reports/purchases">{t('reports.title')}</Link>}
      {!user?.permissions.includes('SALES_VIEW') && !user?.permissions.includes('PURCHASES_VIEW') && user?.permissions.includes('EXPENSES_CREATE') && <Link to="/reports/expenses">{t('reports.title')}</Link>}
      {user?.permissions.includes('EXPENSES_CREATE') && <Link to="/expenses">{t('operations.expenses')}</Link>}
      {user?.permissions.includes('INVENTORY_ADJUST') && <Link to="/waste">{t('operations.waste')}</Link>}
      {user?.permissions.includes('CUSTOMER_PAYMENTS_CREATE') && <Link to="/customer-payments/new">{t('customerPayments.new')}</Link>}
      {user?.permissions.includes('SALES_VIEW') && <Link to="/customer-payments">{t('customerPayments.history')}</Link>}
      {user?.permissions.includes('SALES_VIEW') && <><Link to="/sales">{t('sales.list')}</Link><Link to="/customer-balances">{t('sales.customerBalances')}</Link></>}
      {user?.permissions.includes('SUPPLIER_PAYMENTS_CREATE') && <Link to="/supplier-payments/new">{t('payments.new')}</Link>}
      {user?.permissions.includes('PURCHASES_VIEW') && <Link to="/supplier-payments">{t('payments.history')}</Link>}
      {user?.permissions.includes('PURCHASES_VIEW') && <><Link to="/purchases">{t('purchase.list')}</Link><Link to="/supplier-balances">{t('purchase.supplierBalances')}</Link></>}
      {['PURCHASES_VIEW', 'PURCHASES_CREATE', 'INVENTORY_ADJUST', 'COST_VIEW'].some(p => user?.permissions.includes(p)) && <Link to="/inventory">{t('purchase.inventory')}</Link>}
    </nav></section>
    <details className="workspace-more"><summary>{t('moreSettings')}</summary>
      <nav className="catalog-nav" aria-label={t('master.title')}>{catalogs.filter(kind => canRead(kind, user!.permissions)).map(kind =>
        <Link key={kind} to={'/master/' + kind}>{t('master.' + kind)}</Link>)}
        {user?.permissions.includes('USERS_MANAGE') && <Link to="/users">{t('auth.users')}</Link>}
        {user?.permissions.includes('AUDIT_VIEW') && <Link to="/audit">{t('audit.title')}</Link>}
      </nav></details>
    <footer><button className="text-button" disabled={health.isFetching} onClick={() => void health.refetch()}>{t('retry')}</button></footer>
  </main>
}

export function App() {
  return <AuthProvider><AuthenticatedApp /></AuthProvider>
}

function OfflineWorkspace() {
  const { t } = useTranslation()
  const { user } = useAuth()
  return <main><h1>{t('offline.workspaceTitle')}</h1>
    <p role="status">{t('offline.workspaceHelp')}</p>
    {user?.permissions.includes('SALES_CREATE') && <Link className="primary-button" to="/sales/new">{t('sales.new')}</Link>}
  </main>
}

function AuthenticatedApp() {
  const { t } = useTranslation()
  const { user, offline } = useAuth()
  return <>
    <header className="header"><Link className="brand" to="/">
      <img src="/icon.svg" alt="" width="44" height="44" />
      <span><strong>{t('brand')}</strong><small>{t('subtitle')}</small></span>
    </Link><AccountControls /></header>
    {user && offline ? <Routes><Route path="/sales/new" element={<NewSalePage />} /><Route path="*" element={<OfflineWorkspace />} /></Routes>
    : user ? <Suspense fallback={<main role="status">{t('master.loading')}</main>}><Routes>
      <Route path="/reports/:kind" element={<ReportPage />} />
      <Route path="/audit" element={<AuditPage />} />
      <Route path="/expenses" element={<OperationHistoryPage kind="expenses" />} /><Route path="/expenses/new" element={<OperationEntryPage kind="expenses" />} />
      <Route path="/waste" element={<OperationHistoryPage kind="waste" />} /><Route path="/waste/new" element={<OperationEntryPage kind="waste" />} />
      <Route path="/customer-payments/new" element={<NewCustomerPaymentPage />} /><Route path="/customer-payments" element={<CustomerPaymentHistoryPage />} /><Route path="/customer-payments/:id" element={<CustomerPaymentDetailPage />} />
      <Route path="/sales/new" element={<NewSalePage />} /><Route path="/sales" element={<SaleListPage />} /><Route path="/sales/:id" element={<SaleDetailPage />} /><Route path="/customer-balances" element={<CustomerBalancesPage />} />
      <Route path="/" element={<Workspace />} /><Route path="/master/:catalog" element={<MasterDataPage />} /><Route path="/users" element={<UserDirectoryPage />} />
      <Route path="/purchases" element={<PurchaseListPage />} /><Route path="/purchases/new" element={<NewPurchasePage />} /><Route path="/purchases/:id" element={<PurchaseDetailPage />} />
      <Route path="/supplier-payments" element={<PaymentHistoryPage />} /><Route path="/supplier-payments/new" element={<NewSupplierPaymentPage />} /><Route path="/supplier-payments/:id" element={<PaymentDetailPage />} />
      <Route path="/supplier-balances" element={<SupplierBalancesPage />} /><Route path="/inventory" element={<InventoryPage />} />
      <Route path="*" element={<main><h1>{t('notFound')}</h1><Link to="/">{t('home')}</Link></main>} />
    </Routes></Suspense> : <LoginPage />}
    <OfflineSales key={user?.id ?? 'signed-out'} /><UpdateNotice />
  </>
}
