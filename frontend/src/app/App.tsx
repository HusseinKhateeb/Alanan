import { OperationEntryPage } from '../features/operations/OperationEntryPage'
import { OperationHistoryPage } from '../features/operations/OperationHistoryPage'
import { NewCustomerPaymentPage } from '../features/customerpayments/NewCustomerPaymentPage'
import { OfflineSales } from '../features/offline/OfflineSales'
import { PaymentHistoryPage as CustomerPaymentHistoryPage, PaymentDetailPage as CustomerPaymentDetailPage } from '../features/customerpayments/PaymentHistoryPage'
import { NewSupplierPaymentPage } from '../features/supplierpayments/NewSupplierPaymentPage'
import { NewSalePage } from '../features/sales/NewSalePage'
import { SaleListPage } from '../features/sales/SaleListPage'
import { SaleDetailPage } from '../features/sales/SaleDetailPage'
import { CustomerBalancesPage } from '../features/sales/CustomerBalancesPage'
import { PaymentHistoryPage, PaymentDetailPage } from '../features/supplierpayments/PaymentHistoryPage'
import { useQuery } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { Link, Route, Routes } from 'react-router-dom'
import { checkBackend } from '../api/health'
import { UpdateNotice } from './pwa/UpdateNotice'
import { AuthProvider, useAuth } from '../auth/AuthProvider'
import { LoginPage } from '../auth/LoginPage'
import { AccountControls } from '../auth/AccountControls'
import { UserDirectoryPage } from '../features/users/UserDirectoryPage'
import { MasterDataPage } from '../features/masterdata/MasterDataPage'
import { catalogs, canRead } from '../features/masterdata/catalogs'
import { NewPurchasePage } from '../features/purchases/NewPurchasePage'
import { PurchaseListPage } from '../features/purchases/PurchaseListPage'
import { PurchaseDetailPage } from '../features/purchases/PurchaseDetailPage'
import { SupplierBalancesPage } from '../features/suppliers/SupplierBalancesPage'
import { InventoryPage } from '../features/inventory/InventoryPage'

function Workspace() {
  const { t } = useTranslation()
  const { user } = useAuth()
  const health = useQuery({ queryKey: ['backend-health'], queryFn: checkBackend,
    refetchInterval: 60_000, retry: false })
  return <main id="main">
    <div className="eyebrow">{t('workspace')}</div>
    <h1>{t('welcome')}</h1>
    <p className="intro">{t('intro')}</p>
    <nav className="catalog-nav" aria-label={t('purchase.list')}>
      {user?.permissions.includes('EXPENSES_CREATE') && <Link to="/expenses">{t('operations.expenses')}</Link>}
      {user?.permissions.includes('INVENTORY_ADJUST') && <Link to="/waste">{t('operations.waste')}</Link>}
      {user?.permissions.includes('CUSTOMER_PAYMENTS_CREATE') && <Link to="/customer-payments/new">{t('customerPayments.new')}</Link>}
      {user?.permissions.includes('SALES_VIEW') && <Link to="/customer-payments">{t('customerPayments.history')}</Link>}
      {user?.permissions.includes('SALES_CREATE') && <Link to="/sales/new">{t('sales.new')}</Link>}
      {user?.permissions.includes('SALES_VIEW') && <><Link to="/sales">{t('sales.list')}</Link><Link to="/customer-balances">{t('sales.customerBalances')}</Link></>}
      {user?.permissions.includes('SUPPLIER_PAYMENTS_CREATE') && <Link to="/supplier-payments/new">{t('payments.new')}</Link>}
      {user?.permissions.includes('PURCHASES_VIEW') && <Link to="/supplier-payments">{t('payments.history')}</Link>}
      {user?.permissions.includes('PURCHASES_CREATE') && <Link to="/purchases/new">{t('purchase.new')}</Link>}
      {user?.permissions.includes('PURCHASES_VIEW') && <><Link to="/purchases">{t('purchase.list')}</Link><Link to="/supplier-balances">{t('purchase.supplierBalances')}</Link></>}
      {['PURCHASES_VIEW', 'PURCHASES_CREATE', 'INVENTORY_ADJUST', 'COST_VIEW'].some(p => user?.permissions.includes(p)) && <Link to="/inventory">{t('purchase.inventory')}</Link>}
    </nav>
    <nav className="catalog-nav" aria-label={t('master.title')}>{catalogs.filter(kind => canRead(kind, user!.permissions)).map(kind =>
      <Link key={kind} to={'/master/' + kind}>{t('master.' + kind)}</Link>)}</nav>
    {user?.permissions.includes('USERS_MANAGE') && <Link className="directory-link" to="/users">{t('auth.users')}</Link>}
    <section className="setup" aria-labelledby="foundation-title">
      <div className="setup-icon" aria-hidden="true">A</div>
      <div><h2 id="foundation-title">{t('foundation')}</h2><p>{t('notice')}</p></div>
    </section>
    <h2 className="section-title">{t('next')}</h2>
    <div className="steps">{['auth', 'catalog', 'invoice'].map((step, index) =>
      <article key={step}><span className="step-number" aria-hidden="true">0{index + 1}</span>
        <h3>{t(`${step}Title`)}</h3><p>{t(`${step}Detail`)}</p>
      </article>,
    )}</div>
    <footer><span className={`health ${health.isSuccess ? 'ready' : ''}`} role="status">
      {t(health.isFetching ? 'healthChecking' : health.isSuccess ? 'healthReady' : 'healthUnavailable')}
    </span><button className="text-button" disabled={health.isFetching} onClick={() => void health.refetch()}>{t('retry')}</button></footer>
  </main>
}

export function App() {
  return <AuthProvider><AuthenticatedApp /></AuthProvider>
}

function AuthenticatedApp() {
  const { t } = useTranslation()
  const { user } = useAuth()
  return <>
    <header className="header"><Link className="brand" to="/">
      <img src="/icon.svg" alt="" width="44" height="44" />
      <span><strong>{t('brand')}</strong><small>{t('subtitle')}</small></span>
    </Link><AccountControls /></header>
    {user ? <Routes>
      <Route path="/expenses" element={<OperationHistoryPage kind="expenses" />} /><Route path="/expenses/new" element={<OperationEntryPage kind="expenses" />} />
      <Route path="/waste" element={<OperationHistoryPage kind="waste" />} /><Route path="/waste/new" element={<OperationEntryPage kind="waste" />} />
      <Route path="/customer-payments/new" element={<NewCustomerPaymentPage />} /><Route path="/customer-payments" element={<CustomerPaymentHistoryPage />} /><Route path="/customer-payments/:id" element={<CustomerPaymentDetailPage />} />
      <Route path="/sales/new" element={<NewSalePage />} /><Route path="/sales" element={<SaleListPage />} /><Route path="/sales/:id" element={<SaleDetailPage />} /><Route path="/customer-balances" element={<CustomerBalancesPage />} />
      <Route path="/" element={<Workspace />} /><Route path="/master/:catalog" element={<MasterDataPage />} /><Route path="/users" element={<UserDirectoryPage />} />
      <Route path="/purchases" element={<PurchaseListPage />} /><Route path="/purchases/new" element={<NewPurchasePage />} /><Route path="/purchases/:id" element={<PurchaseDetailPage />} />
      <Route path="/supplier-payments" element={<PaymentHistoryPage />} /><Route path="/supplier-payments/new" element={<NewSupplierPaymentPage />} /><Route path="/supplier-payments/:id" element={<PaymentDetailPage />} />
      <Route path="/supplier-balances" element={<SupplierBalancesPage />} /><Route path="/inventory" element={<InventoryPage />} />
      <Route path="*" element={<main><h1>{t('notFound')}</h1><Link to="/">{t('home')}</Link></main>} />
    </Routes> : <LoginPage />}
    <OfflineSales key={user?.id ?? 'signed-out'} /><UpdateNotice />
  </>
}
