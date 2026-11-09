import { useEffect, useRef, useState, type MouseEvent } from 'react'
import './CopyCodeButton.css'

type CopyState = 'idle' | 'copied' | 'failed'

const FEEDBACK_MS = 1500

/** Small icon button that writes `code` (and only `code`) to the clipboard.
 * Feedback: check mark in `#3E8FD8` on success, cross in `#F09A94` on failure, each for
 * 1.5 s; a repeated click restarts the timer. Look and copy per
 * specs/frontend/simulated-trade.md「代號旁的複製按鈕」. */
export default function CopyCodeButton({ code }: { code: string }) {
  const [state, setState] = useState<CopyState>('idle')
  const timerRef = useRef<number | null>(null)

  useEffect(() => () => {
    if (timerRef.current !== null) window.clearTimeout(timerRef.current)
  }, [])

  function show(next: CopyState) {
    if (timerRef.current !== null) window.clearTimeout(timerRef.current)
    setState(next)
    timerRef.current = window.setTimeout(() => {
      timerRef.current = null
      setState('idle')
    }, FEEDBACK_MS)
  }

  // stopPropagation: the button sits inside table rows whose own click toggles a checkbox
  // (策略分頁命中彙總表) — copying must not trigger that.
  async function handleClick(e: MouseEvent<HTMLButtonElement>) {
    e.stopPropagation()
    try {
      await navigator.clipboard.writeText(code)
      show('copied')
    } catch {
      show('failed')
    }
  }

  const label = state === 'copied' ? '已複製' : state === 'failed' ? '複製失敗' : `複製代號 ${code}`

  return (
    <button
      type="button"
      className={`copy-code-btn${state === 'idle' ? '' : ` copy-code-btn-${state}`}`}
      title={label}
      aria-label={label}
      onClick={handleClick}
    >
      <svg viewBox="0 0 14 14" width="14" height="14" aria-hidden="true">
        {state === 'copied' ? (
          <path d="M2.5 7.5l3 3 6-7" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" />
        ) : state === 'failed' ? (
          <path d="M3.5 3.5l7 7M10.5 3.5l-7 7" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
        ) : (
          <>
            <rect x="4.5" y="4.5" width="8" height="8" rx="1.5" fill="none" stroke="currentColor" strokeWidth="1.3" />
            <path d="M9.5 2.5V2a1 1 0 0 0-1-1H2.5a1 1 0 0 0-1 1v6.5a1 1 0 0 0 1 1h.5" fill="none" stroke="currentColor" strokeWidth="1.3" />
          </>
        )}
      </svg>
    </button>
  )
}
