import { useRegisterSW } from 'virtual:pwa-register/react'
import { useTranslation } from 'react-i18next'

export function UpdateNotice() {
  const { t } = useTranslation()
  const { needRefresh: [needRefresh], updateServiceWorker } = useRegisterSW()
  if (!needRefresh) return null
  // Before forms are introduced, gate this action on durable draft persistence.
  return <aside className="update" role="status">
    <span>{t('update')}</span>
    <button onClick={() => void updateServiceWorker(true)}>{t('updateAction')}</button>
  </aside>
}
