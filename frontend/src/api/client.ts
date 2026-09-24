let accessToken: string | null = null
let tokenOwner: string | null = null
export function setAccessToken(token: string | null, userId: string | null = null) { accessToken = token; tokenOwner = token ? userId : null }
export function hasSessionFor(userId: string) { return !!accessToken && tokenOwner === userId }
export function apiForUser<T>(userId: string, path: string, options: RequestInit = {}): Promise<T> {
  if (!hasSessionFor(userId)) return Promise.reject(new ApiError('UNAUTHENTICATED', {}, 401))
  return api<T>(path, options)
}

export class ApiError extends Error {
  constructor(public code: string, public fieldErrors: Record<string, string> = {}, public status = 0) { super(code) }
}

export async function api<T>(path: string, options: RequestInit = {}): Promise<T> {
  const response = await request(path, options)
  if (response.status === 204) return undefined as T
  return response.json() as Promise<T>
}

let refreshInFlight: Promise<unknown> | null = null
export function refreshSession<T>(): Promise<T> {
  if (refreshInFlight) return refreshInFlight as Promise<T>
  const pending = api<T>('/auth/refresh', { method: 'POST' })
  refreshInFlight = pending
  const clear = () => { if (refreshInFlight === pending) refreshInFlight = null }
  void pending.then(clear, clear)
  return pending
}

export async function downloadPdf(path: string, filename: string): Promise<void> {
  const response = await request(path)
  const blob = await response.blob()
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = filename
  document.body.appendChild(link)
  link.click()
  link.remove()
  window.setTimeout(() => URL.revokeObjectURL(url), 60_000)
}

export async function downloadCsv(path: string, filename: string): Promise<void> {
  const response = await request(path)
  const blob = await response.blob()
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = filename
  document.body.appendChild(link)
  link.click()
  link.remove()
  window.setTimeout(() => URL.revokeObjectURL(url), 60_000)
}

async function request(path: string, options: RequestInit = {}): Promise<Response> {
  const requestToken = accessToken
  const headers = new Headers(options.headers)
  if (options.body) headers.set('Content-Type', 'application/json')
  if (requestToken && path !== '/auth/refresh' && path !== '/auth/login') headers.set('Authorization', `Bearer ${requestToken}`)
  const sessionEndpoint = path === '/auth/login' || path === '/auth/refresh' || path === '/auth/logout'
  if (path === '/auth/refresh') headers.set('X-Alanan-Session', 'refresh')
  let response: Response
  try {
    response = await fetch(`${import.meta.env.VITE_API_BASE_URL || '/api'}${path}`, {
      ...options, headers, cache: 'no-store', credentials: sessionEndpoint ? 'same-origin' : 'omit', signal: options.signal ?? AbortSignal.timeout(15_000),
    })
  } catch {
    window.dispatchEvent(new Event('alanan:backend-unavailable'))
    throw new ApiError('NETWORK_ERROR')
  }
  if (response.ok && path !== '/auth/refresh') window.dispatchEvent(new Event('alanan:backend-available'))
  else if ([502, 503, 504].includes(response.status)) window.dispatchEvent(new Event('alanan:backend-unavailable'))
  if (!response.ok) {
    const body = await response.json().catch(() => ({})) as { errorCode?: string; fieldErrors?: Record<string, string> }
    if (response.status === 401 && path !== '/auth/login' && path !== '/auth/refresh' && accessToken === requestToken) {
      setAccessToken(null)
      window.dispatchEvent(new Event('alanan:unauthenticated'))
    }
    throw new ApiError(body.errorCode || 'SERVER_ERROR', body.fieldErrors, response.status)
  }
  return response
}
