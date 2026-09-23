import { useRegisterSW } from 'virtual:pwa-register/react'
import { useTranslation } from 'react-i18next'
import { useHasOpenForm } from './formUpdateGuard'

export function UpdateNotice() {
  const { t } = useTranslation()
  const { needRefresh: [needRefresh], updateServiceWorker } = useRegisterSW()
  const formOpen = useHasOpenForm()
  if (!needRefresh) return null
  return <aside className="update" role="status">
    <span>{t('update')}</span>
    {formOpen && <span>{t('purchase.closeBeforeUpdate')}</span>}
    <button disabled={formOpen} onClick={() => void updateServiceWorker(true)}>{t('updateAction')}</button>
  </aside>
}
