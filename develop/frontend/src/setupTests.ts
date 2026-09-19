import '@testing-library/jest-dom/vitest'

// jsdom defaults `window.innerWidth` to 1024, which is ≤ 1280 — the breakpoint
// `StrategyTab`'s 視窗寬 ≤ 1280px 疊欄 layout switches on (specs/frontend/strategy.md 「視窗
// 寬 ≤ 1280px：日期與價格各自疊成一欄」). Every existing test in the suite was written
// against the wide (> 1280px), four-separate-column layout, so the whole suite defaults to
// a wide viewport here; the dedicated stacked-layout tests set `window.innerWidth` back down
// to ≤ 1280 themselves and restore it afterward.
if (typeof window !== 'undefined') {
  Object.defineProperty(window, 'innerWidth', { writable: true, configurable: true, value: 1920 })
}
