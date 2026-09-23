let accessToken: string | null = null
export function setAccessToken(token: string | null) { accessToken = token }

export class ApiError extends Error {
  constructor(public code: string, public fieldErrors: Record<string, string> = {}, public status = 0) { super(code) }
}

export async function api<T>(path: string, options: RequestInit = {}): Promise<T> {
  const headers = new Headers(options.headers)
  if (options.body) headers.set('Content-Type', 'application/json')
  if (accessToken) headers.set('Authorization', `Bearer ${accessToken}`)
  let response: Response
  try {
    response = await fetch(`${import.meta.env.VITE_API_BASE_URL || '/api'}${path}`, {
      ...options, headers, cache: 'no-store', credentials: 'omit', signal: options.signal ?? AbortSignal.timeout(15_000),
    })
  } catch { throw new ApiError('NETWORK_ERROR') }
  if (!response.ok) {
    const body = await response.json().catch(() => ({})) as { errorCode?: string; fieldErrors?: Record<string, string> }
    if (response.status === 401 && path !== '/auth/login') {
      setAccessToken(null)
      window.dispatchEvent(new Event('alanan:unauthenticated'))
    }
    throw new ApiError(body.errorCode || 'SERVER_ERROR', body.fieldErrors, response.status)
  }
  if (response.status === 204) return undefined as T
  return response.json() as Promise<T>
}
