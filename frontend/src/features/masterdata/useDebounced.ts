import { useEffect, useState } from 'react'
export function useDebounced(value: string) {
  const [debounced, setDebounced] = useState(value)
  useEffect(() => { const timer = setTimeout(() => setDebounced(value), 250); return () => clearTimeout(timer) }, [value])
  return debounced
}
