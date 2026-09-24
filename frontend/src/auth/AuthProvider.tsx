import { createContext, useContext, useEffect, useRef, useState, type ReactNode } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { api, ApiError, refreshSession, setAccessToken } from '../api/client'
import { clearOfflineProfile, loadOfflineProfile, saveOfflineProfile } from './offlineProfile'

export interface AuthUser {
  id: string
  username: string
  displayName: string
  preferredLanguage: 'ar' | 'en'
  permissions: string[]
  roles: string[]
}
interface Session { accessToken: string; expiresAt: string; user: AuthUser }
interface AuthContextValue {
  user: AuthUser | null
  offline: boolean
  expired: boolean
  login: (username: string, password: string) => Promise<void>
  logout: () => Promise<void>
  changePassword: (currentPassword: string, newPassword: string) => Promise<void>
  changeLanguage: (language: 'ar' | 'en') => Promise<void>
}
const AuthContext = createContext<AuthContextValue | null>(null)
function connectionFailed(failure: unknown) {
  return failure instanceof ApiError && (failure.code === 'NETWORK_ERROR' || failure.status >= 500)
}

async function restoreSession(): Promise<Session> {
  for (const delay of [250, 750, 1500, 0]) {
    try { return await refreshSession<Session>() }
    catch (failure) {
      if (!(failure instanceof ApiError) || (failure.code !== 'NETWORK_ERROR' && failure.status < 500) || delay === 0) throw failure
      await new Promise(resolve => window.setTimeout(resolve, delay))
    }
  }
  throw new ApiError('NETWORK_ERROR')
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [session, setSession] = useState<Session | null>(null)
  const [offlineUser, setOfflineUser] = useState<AuthUser | null>(null)
  const [backendUnavailable, setBackendUnavailable] = useState(() => navigator.onLine === false)
  const [expired, setExpired] = useState(false)
  const [restoring, setRestoring] = useState(true)
  const generation = useRef(0)
  const queryClient = useQueryClient()
  const { i18n } = useTranslation()
  useEffect(() => {
    const unavailable = () => setBackendUnavailable(true)
    const available = () => setBackendUnavailable(false)
    window.addEventListener('alanan:backend-unavailable', unavailable)
    window.addEventListener('alanan:backend-available', available)
    window.addEventListener('offline', unavailable)
    return () => {
      window.removeEventListener('alanan:backend-unavailable', unavailable)
      window.removeEventListener('alanan:backend-available', available)
      window.removeEventListener('offline', unavailable)
    }
  }, [])
  useEffect(() => {
    const current = ++generation.current
    let cancelled = false
    const initial = navigator.onLine === false ? Promise.reject(new ApiError('NETWORK_ERROR')) : restoreSession()
    initial.then(async next => {
      if (cancelled || current !== generation.current) return
      if (!next.accessToken || !next.user || !Number.isFinite(Date.parse(next.expiresAt))) return
      setAccessToken(next.accessToken, next.user.id)
      setSession(next)
      setBackendUnavailable(false)
      saveOfflineProfile(next.user)
      await i18n.changeLanguage(next.user.preferredLanguage)
    }).catch(async failure => {
      if (cancelled || current !== generation.current) return
      if (connectionFailed(failure)) {
        const cached = loadOfflineProfile()
        if (cached) { setOfflineUser(cached); await i18n.changeLanguage(cached.preferredLanguage) }
      } else if (failure instanceof ApiError && failure.status === 401) clearOfflineProfile()
    }).finally(() => { if (!cancelled && current === generation.current) setRestoring(false) })
    return () => { cancelled = true }
  }, [i18n])
  useEffect(() => {
    if (!offlineUser && !(backendUnavailable && session)) return
    let active = true, running = false
    async function reconnect() {
      if (!active || running || navigator.onLine === false) return
      running = true
      try {
        const next = await refreshSession<Session>()
        if (!active || !next.accessToken || !next.user || !Number.isFinite(Date.parse(next.expiresAt))) return
        setAccessToken(next.accessToken, next.user.id)
        saveOfflineProfile(next.user)
        setSession(next)
        setOfflineUser(null)
        setBackendUnavailable(false)
        setExpired(false)
        queryClient.clear()
        await i18n.changeLanguage(next.user.preferredLanguage)
      } catch (failure) {
        if (active && failure instanceof ApiError && failure.status === 401) {
          clearOfflineProfile(); setOfflineUser(null); setExpired(true); queryClient.clear()
        }
      } finally { running = false }
    }
    const online = () => { void reconnect() }
    window.addEventListener('online', online)
    const interval = window.setInterval(online, 10_000)
    online()
    return () => { active = false; window.removeEventListener('online', online); window.clearInterval(interval) }
  }, [offlineUser, backendUnavailable, session, i18n, queryClient])
  useEffect(() => {
    const expire = () => { setAccessToken(null); setSession(null); setOfflineUser(null); clearOfflineProfile(); setExpired(true); queryClient.clear() }
    window.addEventListener('alanan:unauthenticated', expire)
    const timeout = session ? window.setTimeout(() => {
      const current = generation.current
      restoreSession().then(next => {
        if (current !== generation.current) return
        if (!next.accessToken || !next.user || !Number.isFinite(Date.parse(next.expiresAt))) throw new ApiError('SERVER_ERROR')
        setAccessToken(next.accessToken, next.user.id)
        saveOfflineProfile(next.user)
        setSession(next)
      }).catch(failure => {
        if (current !== generation.current) return
        if (connectionFailed(failure)) {
          setAccessToken(null); setSession(null); setOfflineUser(session.user); queryClient.clear()
        } else expire()
      })
    }, Math.max(0, Date.parse(session.expiresAt) - Date.now() - 60_000)) : undefined
    return () => { window.removeEventListener('alanan:unauthenticated', expire); window.clearTimeout(timeout) }
  }, [session, queryClient])

  async function login(username: string, password: string) {
    const next = await api<Session>('/auth/login', { method: 'POST', body: JSON.stringify({ username, password }) })
    if (!next.accessToken || !next.user || !Number.isFinite(Date.parse(next.expiresAt))) throw new ApiError('SERVER_ERROR')
    generation.current++
    setRestoring(false)
    queryClient.clear()
    setAccessToken(next.accessToken, next.user.id)
    saveOfflineProfile(next.user)
    setSession(next)
    setOfflineUser(null)
    setBackendUnavailable(false)
    setExpired(false)
    await i18n.changeLanguage(next.user.preferredLanguage)
  }
  async function logout() {
    // Only report logout after the server has revoked the tokens.
    await api<void>('/auth/logout-current', { method: 'POST' })
    generation.current++
    setAccessToken(null)
    setSession(null)
    setOfflineUser(null)
    clearOfflineProfile()
    setExpired(false)
    queryClient.clear()
  }
  async function changePassword(currentPassword: string, newPassword: string) {
    await api<void>('/auth/password', { method: 'PUT', body: JSON.stringify({ currentPassword, newPassword }) })
    generation.current++
    setAccessToken(null)
    setSession(null)
    setOfflineUser(null)
    clearOfflineProfile()
    setExpired(false)
    queryClient.clear()
  }
  async function changeLanguage(language: 'ar' | 'en') {
    if (session) {
      await api<void>('/auth/language', { method: 'PUT', body: JSON.stringify({ language }) })
      setSession(current => current ? { ...current, user: { ...current.user, preferredLanguage: language } } : null)
    }
    if (offlineUser) {
      const next = { ...offlineUser, preferredLanguage: language }
      setOfflineUser(next)
      saveOfflineProfile(next)
    }
    await i18n.changeLanguage(language)
  }
  return <AuthContext.Provider value={{ user: session?.user ?? offlineUser, offline: !!(session || offlineUser) && (backendUnavailable || !session), expired, login, logout, changePassword, changeLanguage }}>
    {restoring ? <main role="status">Loading session…</main> : children}
  </AuthContext.Provider>
}

export function useAuth() {
  const value = useContext(AuthContext)
  if (!value) throw new Error('AuthProvider is required')
  return value
}
