import { createContext, useContext, useEffect, useState, type ReactNode } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { api, ApiError, setAccessToken } from '../api/client'

export interface AuthUser {
  id: string
  username: string
  displayName: string
  preferredLanguage: 'ar' | 'en'
  permissions: string[]
}
interface Session { accessToken: string; expiresAt: string; user: AuthUser }
interface AuthContextValue {
  user: AuthUser | null
  expired: boolean
  login: (username: string, password: string) => Promise<void>
  logout: () => Promise<void>
  changeLanguage: (language: 'ar' | 'en') => Promise<void>
}
const AuthContext = createContext<AuthContextValue | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
  const [session, setSession] = useState<Session | null>(null)
  const [expired, setExpired] = useState(false)
  const queryClient = useQueryClient()
  const { i18n } = useTranslation()
  useEffect(() => {
    const expire = () => { setAccessToken(null); setSession(null); setExpired(true); queryClient.clear() }
    window.addEventListener('alanan:unauthenticated', expire)
    const timeout = session ? window.setTimeout(expire, Math.max(0, Date.parse(session.expiresAt) - Date.now())) : undefined
    return () => { window.removeEventListener('alanan:unauthenticated', expire); window.clearTimeout(timeout) }
  }, [session, queryClient])

  async function login(username: string, password: string) {
    const next = await api<Session>('/auth/login', { method: 'POST', body: JSON.stringify({ username, password }) })
    if (!next.accessToken || !next.user || !Number.isFinite(Date.parse(next.expiresAt))) throw new ApiError('SERVER_ERROR')
    queryClient.clear()
    setAccessToken(next.accessToken, next.user.id)
    setSession(next)
    setExpired(false)
    await i18n.changeLanguage(next.user.preferredLanguage)
  }
  async function logout() {
    // Only report logout after the server has revoked the tokens.
    await api<void>('/auth/logout', { method: 'POST' })
    setAccessToken(null)
    setSession(null)
    setExpired(false)
    queryClient.clear()
  }
  async function changeLanguage(language: 'ar' | 'en') {
    if (session) {
      await api<void>('/auth/language', { method: 'PUT', body: JSON.stringify({ language }) })
      setSession(current => current ? { ...current, user: { ...current.user, preferredLanguage: language } } : null)
    }
    await i18n.changeLanguage(language)
  }
  return <AuthContext.Provider value={{ user: session?.user ?? null, expired, login, logout, changeLanguage }}>
    {children}
  </AuthContext.Provider>
}

export function useAuth() {
  const value = useContext(AuthContext)
  if (!value) throw new Error('AuthProvider is required')
  return value
}
