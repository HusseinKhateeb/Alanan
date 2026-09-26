import { NavLink, useLocation } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { useAuth } from '../auth/AuthProvider'

export function QuickNavigation() {
  const { t } = useTranslation()
  const { user, offline } = useAuth()
  const location = useLocation()
  if (!user) return null
  const can = (permission: string) => user.permissions.includes(permission)
  const links = [
    { to: '/', label: t('nav.home'), show: true },
    { to: '/sales/new', label: t('nav.newSale'), show: can('SALES_CREATE') && (!offline || location.pathname !== '/') },
    { to: '/sales', label: t('nav.sales'), show: !offline && can('SALES_VIEW') },
    { to: '/orders', label: t('orders.list'), show: !offline && can('SALES_VIEW') },
    { to: '/orders/new', label: t('orders.new'), show: !offline && can('SALES_CREATE') },
    { to: '/purchases/new', label: t('nav.newPurchase'), show: !offline && can('PURCHASES_CREATE') },
    { to: '/purchases', label: t('nav.purchases'), show: !offline && can('PURCHASES_VIEW') },
    { to: '/expenses/new', label: t('nav.newExpense'), show: !offline && can('EXPENSES_CREATE') },
    { to: '/waste/new', label: t('nav.newWaste'), show: !offline && can('INVENTORY_ADJUST') },
    { to: '/inventory', label: t('nav.inventory'), show: !offline && can('INVENTORY_ADJUST') },
  ]
  return <nav className="quick-navigation" aria-label={t('nav.title')}>
    {links.filter(link => link.show).map(link => <NavLink key={link.to} to={link.to} end className="quick-navigation-link">{link.label}</NavLink>)}
  </nav>
}
