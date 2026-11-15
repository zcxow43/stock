import { useSyncExternalStore } from 'react'

/** Shared set of stock codes the user has copied (successfully) this browser session.
 * Persisted as a JSON array in sessionStorage `copiedStockIds`; falls back to memory when
 * sessionStorage throws. Used by every table that renders a CopyCodeButton
 * (specs/frontend/simulated-trade.md「已複製的列」). */
const KEY = 'copiedStockIds'

let memory: string[] | null = null
let snapshot: ReadonlySet<string> | null = null
const listeners = new Set<() => void>()

function load(): string[] {
  if (memory) return memory
  try {
    const raw = window.sessionStorage.getItem(KEY)
    const parsed: unknown = raw ? JSON.parse(raw) : []
    memory = Array.isArray(parsed) ? parsed.filter((v): v is string => typeof v === 'string') : []
  } catch {
    memory = []
  }
  return memory
}

function getSnapshot(): ReadonlySet<string> {
  if (!snapshot) snapshot = new Set(load())
  return snapshot
}

function subscribe(fn: () => void): () => void {
  listeners.add(fn)
  return () => {
    listeners.delete(fn)
  }
}

export function markCopied(code: string): void {
  const ids = load()
  if (ids.includes(code)) return
  memory = [...ids, code]
  try {
    window.sessionStorage.setItem(KEY, JSON.stringify(memory))
  } catch {
    // sessionStorage blocked: keep the in-memory copy only.
  }
  snapshot = null
  listeners.forEach((fn) => fn())
}

export function useCopiedStockIds(): ReadonlySet<string> {
  return useSyncExternalStore(subscribe, getSnapshot, getSnapshot)
}
