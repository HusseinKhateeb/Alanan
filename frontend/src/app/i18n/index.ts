import i18n from 'i18next'
import { initReactI18next } from 'react-i18next'
import en from '../../locales/en/common.json'
import ar from '../../locales/ar/common.json'

function initialLanguage() {
  try { return localStorage.getItem('alanan.language') === 'en' ? 'en' : 'ar' }
  catch { return 'ar' }
}

function applyLanguage(language: string) {
  document.documentElement.lang = language
  document.documentElement.dir = language === 'ar' ? 'rtl' : 'ltr'
  try { localStorage.setItem('alanan.language', language) } catch { /* Preference is optional. */ }
}

i18n.on('languageChanged', applyLanguage)
void i18n.use(initReactI18next).init({
  resources: { en: { translation: en }, ar: { translation: ar } },
  lng: initialLanguage(), fallbackLng: 'en', supportedLngs: ['ar', 'en'],
  interpolation: { escapeValue: false },
})
export default i18n
