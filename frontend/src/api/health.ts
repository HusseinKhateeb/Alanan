export async function checkBackend(): Promise<boolean> {
  const response = await fetch('/actuator/health', {
    cache: 'no-store', signal: AbortSignal.timeout(5000),
  })
  if (!response.ok) throw new Error('BACKEND_UNAVAILABLE')
  const body: unknown = await response.json()
  if (typeof body !== 'object' || body === null || !('status' in body) || body.status !== 'UP') {
    throw new Error('BACKEND_UNAVAILABLE')
  }
  return true
}
