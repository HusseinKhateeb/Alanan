import { useEffect, useSyncExternalStore } from 'react'
let count = 0
const listeners = new Set<() => void>()
function notify() { listeners.forEach(listener => listener()) }
export function useFormUpdateGuard(active: boolean) {
  useEffect(() => {
    if (!active) return
    count++; notify()
    return () => { count--; notify() }
  }, [active])
}
export function useHasOpenForm() {
  return useSyncExternalStore(listener => { listeners.add(listener); return () => { listeners.delete(listener) } }, () => count > 0)
}
