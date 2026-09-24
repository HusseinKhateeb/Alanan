import type { AuthUser } from './AuthProvider'

const key = 'alanan.offlineProfile'
const lifetime = 7 * 24 * 60 * 60 * 1000

export function saveOfflineProfile(user: AuthUser) {
  try { localStorage.setItem(key, JSON.stringify({ user, savedAt: Date.now() })) } catch { /* Offline reopen is unavailable when browser storage is blocked. */ }
}

export function loadOfflineProfile(): AuthUser | null {
  try {
    const raw = localStorage.getItem(key)
    if (!raw) return null
    const saved = JSON.parse(raw) as { user?: AuthUser; savedAt?: number }
    if (!saved.user || typeof saved.user.id !== 'string' || !saved.user.id || typeof saved.user.username !== 'string' ||
        typeof saved.user.displayName !== 'string' || !['ar', 'en'].includes(saved.user.preferredLanguage) ||
        !Array.isArray(saved.user.permissions) || !saved.user.permissions.every(value => typeof value === 'string') ||
        !Array.isArray(saved.user.roles) || !saved.user.roles.every(value => typeof value === 'string') ||
        typeof saved.savedAt !== 'number' ||
        saved.savedAt > Date.now() || Date.now() - saved.savedAt > lifetime) {
      localStorage.removeItem(key)
      return null
    }
    return saved.user
  } catch { return null }
}

export function clearOfflineProfile() {
  try { localStorage.removeItem(key) } catch { /* In-memory sign-out still succeeds. */ }
}
