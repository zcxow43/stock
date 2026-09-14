import { useEffect, useRef, useState, type ReactNode } from 'react'
import { ApiError, fetchStocks, importStockUniverse, type StockListItem, type UniverseImportResponse } from '../api/stocks'
import {
  backtestStrategies,
  fetchStrategyCatalog,
  scanStrategies,
  type BacktestRequestItem,
  type BacktestResponse,
  type BacktestResultItem,
  type ParamGroup,
  type PresetCode,
  type ScanRequest,
  type ScanResponse,
  type ScanStrategySelection,
  type StrategyCatalogItem,
  type StrategyCode,
  type StrategyParam,
  type StrategyResult,
} from '../api/strategies'
import { fetchSyncProgress, startBackfill, type ProgressResponse } from '../api/sync'
import { useDebouncedValue } from '../hooks/useDebouncedValue'
import './StockListPage.css'

/** Matches the backend's configured startup catch-up start date
 * (specs/backend/stock-price-ingestion.md `app.backfill.startup-catch-up.start-date`).
 * Only matters for a stock that has never been synced at all — every other stock's
 * `catchUp` request resumes from its own `last_synced_date`, ignoring this value. */
const BACKFILL_START_DATE = '2026-01-01'
const POLL_INTERVAL_MS = 5000
const STOCK_ID_CAP = 200

type ScanStatus = 'idle' | 'scanning' | 'success' | 'error'
type SyncStatus = 'idle' | 'running'
type ShortcutKey = '1m' | '3m' | '6m'
type BacktestStatus = 'idle' | 'running' | 'success' | 'error'
/** 買進價／報酬率欄排序 — the only two sortable columns, and the only two directions in
 * their click cycle (降冪 → 升冪 → 還原預設排序, `null` state meaning the latter). */
type SortColumn = 'buyPrice' | 'returnPercent'
type SortDirection = 'desc' | 'asc'
type SortState = { column: SortColumn; direction: SortDirection }

function pad2(n: number): string {
  return String(n).padStart(2, '0')
}

function toIsoDate(d: Date): string {
  return `${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())}`
}

function monthsAgo(months: number, from: Date): Date {
  // Plain `d.setMonth(d.getMonth() - months)` silently overflows when the source day
  // doesn't exist in the target month (e.g. Aug 30 minus 6 months lands on "Feb 30",
  // which JS Date normalizes into March 2 instead of clamping) — a real, user-visible
  // date-range bug for the 近半年/近三個月 shortcuts around month-end dates. Clamp the
  // day to the last day of the target month instead, matching calendar-correct
  // "N months ago" semantics.
  const targetMonthIndex = from.getMonth() - months
  const lastDayOfTargetMonth = new Date(from.getFullYear(), targetMonthIndex + 1, 0).getDate()
  const day = Math.min(from.getDate(), lastDayOfTargetMonth)
  return new Date(from.getFullYear(), targetMonthIndex, day)
}

/** The one notion of "today" this page uses anywhere a default/quick range or an upper
 * bound needs the current local date — every other call site reuses this instead of its
 * own `new Date()`, so there's never a second, subtly different definition of "now". */
function today(): Date {
  return new Date()
}

function parseIsoDate(value: string): Date {
  const [y, m, d] = value.split('-').map(Number)
  return new Date(y, m - 1, d)
}

function shiftDays(d: Date, days: number): Date {
  const result = new Date(d)
  result.setDate(result.getDate() + days)
  return result
}

type WeekInfo = { isoYear: number; isoWeek: number; monday: Date; sunday: Date }

/** ISO 8601 week info (Monday…Sunday, week 1 is the week containing the year's first
 * Thursday) for the week containing `date`. Computed via UTC-midnight arithmetic purely
 * to sidestep local DST transitions while walking day-by-day — the returned `monday`/
 * `sunday` are plain local-midnight `Date`s carrying the same calendar Y/M/D throughout,
 * so cross-year weeks (e.g. 2027-01-01 → ISO 2026 week 53, spanning 12/28–01/03) fall out
 * of the same formula as any other week with no special-casing. */
function isoWeekInfo(date: Date): WeekInfo {
  const utcMidnight = new Date(Date.UTC(date.getFullYear(), date.getMonth(), date.getDate()))
  const isoWeekday = utcMidnight.getUTCDay() || 7 // Mon=1 … Sun=7 (getUTCDay()'s Sun=0 becomes 7)
  const monday = new Date(utcMidnight)
  monday.setUTCDate(monday.getUTCDate() - (isoWeekday - 1))
  const sunday = new Date(monday)
  sunday.setUTCDate(sunday.getUTCDate() + 6)
  // The Thursday of a week always falls in the ISO week-year that week belongs to, and is
  // exactly `isoWeek` whole weeks after that year's own Jan-1-Thursday-block start.
  const thursday = new Date(monday)
  thursday.setUTCDate(thursday.getUTCDate() + 3)
  const isoYear = thursday.getUTCFullYear()
  const yearStart = Date.UTC(isoYear, 0, 1)
  const isoWeek = Math.floor((thursday.getTime() - yearStart) / 86400000 / 7) + 1
  return {
    isoYear,
    isoWeek,
    monday: new Date(monday.getUTCFullYear(), monday.getUTCMonth(), monday.getUTCDate()),
    sunday: new Date(sunday.getUTCFullYear(), sunday.getUTCMonth(), sunday.getUTCDate()),
  }
}

function formatMonthDay(d: Date): string {
  return `${pad2(d.getMonth() + 1)}/${pad2(d.getDate())}`
}

/** 「{年} 第 {週次} 週（{MM/DD}–{MM/DD}）」— the one exact string every week selector must
 * show. A native `<input type="week">`'s own rendered value is locale-dependent and can't
 * be trusted to match this, so the label is built from `isoWeekInfo` and shown separately. */
function formatWeekLabel(info: WeekInfo): string {
  return `${info.isoYear} 第 ${info.isoWeek} 週（${formatMonthDay(info.monday)}–${formatMonthDay(info.sunday)}）`
}

type WeekRange = { startMonday: string; endMonday: string }

/** Start week = the ISO week containing (今日 minus N calendar months); end week = this
 * week — the shared formula behind both the default range and all three shortcut buttons
 * (default is just this called with `months=1`). */
function quickWeekRange(months: number): WeekRange {
  const now = today()
  return {
    startMonday: toIsoDate(isoWeekInfo(monthsAgo(months, now)).monday),
    endMonday: toIsoDate(isoWeekInfo(now).monday),
  }
}

/** Default range: start week = LAST week, end week = THIS week — deliberately not the
 * result of any of the three quick-range shortcuts (「近一個月」's start week is ~4 weeks
 * back, never exactly last week), so none of the shortcut buttons highlights on page load. */
function defaultWeekRange(): WeekRange {
  const thisWeekMonday = isoWeekInfo(today()).monday
  return {
    startMonday: toIsoDate(shiftDays(thisWeekMonday, -7)),
    endMonday: toIsoDate(thisWeekMonday),
  }
}

/** The 週選擇 control itself — two step buttons around a caption that always reads the
 * exact spec'd label text, never a native input's locale-formatted value. Stepping by a
 * week is the only way to move it, so there is no control anywhere on the page that can
 * land on a single day. */
function WeekSelector({
  ariaLabel,
  monday,
  onPrev,
  onNext,
  nextDisabled,
}: {
  ariaLabel: string
  monday: Date
  onPrev: () => void
  onNext: () => void
  nextDisabled?: boolean
}) {
  const info = isoWeekInfo(monday)
  return (
    <div className="st-week-select">
      <button type="button" className="st-week-step" aria-label={`${ariaLabel}往前一週`} onClick={onPrev}>
        ‹
      </button>
      <span className="st-week-caption">{formatWeekLabel(info)}</span>
      <button
        type="button"
        className="st-week-step"
        aria-label={`${ariaLabel}往後一週`}
        onClick={onNext}
        disabled={nextDisabled}
      >
        ›
      </button>
    </div>
  )
}

function formatPercent2(value: number | null | undefined): string {
  return value == null ? '—' : `${value.toFixed(2)}%`
}

/** 買進價／賣出價／父列成本加權均價 — two decimals (「數值格式」). Callers still branch on
 * `null` explicitly (matching `formatPercent2`'s call sites) so the muted-`—` span can
 * carry its own class; this only formats the non-null case. */
function formatPrice2(value: number): string {
  return value.toFixed(2)
}

/** Params-line only (「數值格式」: 兩位小數時去掉無意義的尾數，例如 10.0 寫成 10). Table
 * cells keep the fixed two-decimal `formatPercent2` — this is purely for the parenthetical
 * in the merged table's「本次採用參數」line. */
function formatTrimmedPercent(value: number): string {
  return `${Number(value.toFixed(2))}`
}

/** 收益／總收益 — thousand separator, no decimals, negative sign kept (「數值格式」). */
function formatAmount(value: number | null | undefined): string {
  return value == null ? '—' : Math.round(value).toLocaleString('en-US')
}

/** 漲跌色一律沿用全站規則: positive `#E04B45`(sl-up)／negative `#16A75C`(sl-down)／`0`
 * `#93A4B8`(sl-neutral, defined alongside sl-up/sl-down for the merged table + totals). */
function signColorClass(value: number | null | undefined): string {
  if (value == null) return 'sl-muted'
  if (value > 0) return 'sl-up'
  if (value < 0) return 'sl-down'
  return 'sl-neutral'
}

function formatSyncTime(value: string | null | undefined): string {
  if (!value) return '尚未同步'
  return value.slice(0, 16).replace('T', ' ')
}

/** The three remaining sensitivity-driven cards (BOX_BREAKOUT/HIGHER_LOWS/RISING_SUPPORT)
 * all share the same 0~20 「漲幅門檻」 range. REBOUND and CUMULATIVE_RISE no longer reach
 * this — both are always params-driven now, and get their range from their own `params`
 * entries instead (see `isParamInputInvalid`). */
const SENSITIVITY_RISE_PERCENT_RANGE = { min: 0, max: 20 }

/** The catalogue (`GET /api/strategies`) carries no dedicated numeric field for a preset's
 * rise/drop threshold — only human-readable `description` text. Every preset description
 * across all five strategies happens to state its overridable threshold as the *last*
 * "N%" figure in the sentence (earlier percentages, if any, describe a different,
 * non-overridable parameter, e.g. 箱型突破's 箱高 ratio) — so parsing that text is the
 * sole legitimate way to read a default that "取自 API，不在前端寫死". No percentage at
 * all (e.g. 箱型突破 LOOSE's「收盤突破上緣即計」) means the threshold is 0. */
function extractDefaultRisePercent(description: string | undefined): number {
  if (!description) return 0
  const matches = [...description.matchAll(/(\d+(?:\.\d+)?)%/g)]
  if (matches.length === 0) return 0
  return Number(matches[matches.length - 1][1])
}

function isRisePercentInputInvalid(value: string, range: { min: number; max: number }): boolean {
  if (value.trim() === '') return true
  const num = Number(value)
  if (!Number.isFinite(num)) return true
  if (num < range.min || num > range.max) return true
  // "最多一位小數"
  const rounded = Math.round(num * 10) / 10
  return Math.abs(rounded - num) > 1e-9
}

/** 「取消買進價高於 N 元」的金額輸入 — 0 以上、最多兩位小數，same decimal-place-check shape
 * as `isRisePercentInputInvalid`/`isParamInputInvalid` above (round to the allowed precision,
 * compare back to the parsed value). Empty, non-finite, negative, or >2-decimal input is
 * invalid — only the checkbox goes disabled+unchecked in that case; the amount itself is
 * never clamped or rewritten out from under the user while they're mid-edit. */
function isPriceThresholdInputInvalid(value: string): boolean {
  if (value.trim() === '') return true
  const num = Number(value)
  if (!Number.isFinite(num)) return true
  if (num < 0) return true
  const rounded = Math.round(num * 100) / 100
  return Math.abs(rounded - num) > 1e-9
}

/** 總計浮動跟隨's on/off decision, isolated from DOM measurement so it's a plain,
 * unit-testable function — jsdom's `getBoundingClientRect` has no real layout engine behind
 * it, so the wiring around this (a scroll/resize listener reading two refs) can only be
 * exercised by mocking that measurement, never by asserting real pixel positions.
 * `anchorTop`: the in-flow 總成本／總報酬率／總收益 triplet's own `getBoundingClientRect().top`
 * — negative once it has scrolled above the viewport. `tableEndTop`: a sentinel placed
 * immediately after the hit table's last row — once IT has also scrolled above the viewport
 * (negative), the whole table is gone and the floating copy has nothing left to describe. */
function computeFloatingTotalsVisible(anchorTop: number, tableEndTop: number): boolean {
  return anchorTop < 0 && tableEndTop >= 0
}

/** Card shape is decided purely by whether `presets` is empty — never by inspecting
 * `code` — per specs/backend/strategy-scan.md「presets 與 params 的關係」. Today only
 * `CUMULATIVE_RISE` is params-driven, but nothing here names it. */
function isParamsDriven(strategy: StrategyCatalogItem | undefined): boolean {
  return (strategy?.presets.length ?? 0) === 0
}

function getStrategyParam(strategy: StrategyCatalogItem | undefined, code: string): StrategyParam | undefined {
  return strategy?.params?.find((p) => p.code === code)
}

function getParamGroup(strategy: StrategyCatalogItem | undefined, groupCode: string): ParamGroup | undefined {
  return strategy?.paramGroups?.find((g) => g.code === groupCode)
}

/** Whether a param-driven card's given group is currently switched on — from the user's
 * own toggle if they've touched it, otherwise the group's own `default`. Params without a
 * `group` are always "on" (they have no switch to turn off). */
function isGroupOn(
  strategy: StrategyCatalogItem | undefined,
  groupEnabled: Partial<Record<StrategyCode, Record<string, boolean>>>,
  param: StrategyParam,
): boolean {
  if (!param.group) return true
  const group = getParamGroup(strategy, param.group)
  const stored = strategy ? groupEnabled[strategy.code]?.[param.group] : undefined
  return stored ?? group?.default ?? true
}

/** A params-driven input's validity, entirely from its own `params` entry — `step === 1`
 * means "整數" (day-count params); any other step (currently always `0.1`) means "at most
 * that many decimal places" (percent/amount params). Nothing here is keyed by `code` or
 * `unit`; it all comes from the numbers the API already sent. */
function isParamInputInvalid(value: string, param: StrategyParam): boolean {
  if (value.trim() === '') return true
  const num = Number(value)
  if (!Number.isFinite(num)) return true
  if (num < param.min || num > param.max) return true
  if (param.step === 1) return !Number.isInteger(num)
  const decimals = String(param.step).split('.')[1]?.length ?? 0
  const factor = 10 ** decimals
  const rounded = Math.round(num * factor) / factor
  return Math.abs(rounded - num) > 1e-9
}

function paramErrorMessage(param: StrategyParam): string {
  return param.step === 1
    ? `${param.name}需介於 ${param.min} ~ ${param.max} 的整數`
    : `${param.name}需介於 ${param.min} ~ ${param.max}`
}

/** Maps a param group's own `code` to the boolean request field that switches it — e.g.
 * `rise` → `requireRise`, matching specs/backend/strategy-scan.md's request field name.
 * Derived from the group's `code` (API data), never from the strategy's `code`. */
function requireFieldName(groupCode: string): string {
  return `require${groupCode.charAt(0).toUpperCase()}${groupCode.slice(1)}`
}

/** The literal "窗口只有當天" hint text is spec-mandated copy that can't be derived from
 * `params` alone (「累積漲幅」/「跌幅」aren't literally either param's `name`) — keyed by
 * the day-count param's own `code`, not by strategy `code`, so it stays data-driven about
 * *which* card it applies to even though the wording itself is fixed. */
const ONE_DAY_WINDOW_HINT: Record<string, string> = {
  days: '天數為 1 時窗口只有當天，累積漲幅恆為 0%，不會有命中',
  dropDays: '下跌天數為 1 時窗口只有當天，跌幅恆為 0%，不會有命中',
}

interface UnionHit {
  strategyCode: StrategyCode
  signalDate: string
}

/** One distinct buy date for a stock — 「以買進日去重，不以策略、也不以訊號日去重」. A
 * position is `(stockId, buyDate)`, not `(stockId, signalDate)`: for RISING_SUPPORT the
 * `buyDate` is D+2 while `signalDate` stays D, so two hits with the same `signalDate` but
 * different `buyDate`s are two positions, while two hits with different `signalDate`s that
 * land on the same `buyDate` are one. `hits` holds every strategy (with its OWN
 * `signalDate`) that landed on this buy date. This is also the unit a backtest position is
 * computed on: one group = one `POST /api/strategies/backtest` item = one row when the
 * parent is expanded. */
interface BuyDateGroup {
  buyDate: string
  hits: UnionHit[]
}

interface UnionRow {
  stockId: string
  stockName: string
  /** Every hit across every distinct buy date — what the collapsed row's「命中策略與
   * 訊號日」cell renders (unaffected by expand/collapse, see「摺疊與展開都不改變...」). */
  hits: UnionHit[]
  /** The latest `signalDate` among this stock's hits — what the merged table sorts by.
   * Sorting stays keyed on `signalDate`, not `buyDate` — 父列排序不變. */
  latestSignalDate: string
  /** This stock's distinct buy dates, newest first. Length 1 → no expand caret, the
   * single group IS the row. Length ≥ 2 → an expand caret reveals one child row per
   * group (specs/frontend/strategy.md「一檔多筆的展開列」). */
  buyDateGroups: BuyDateGroup[]
}

/** Union across every requested strategy's `items` (never `insufficientData`/`pendingConfirm` —
 * those never appear in `items` per specs/backend/strategy-scan.md), deduped by `stockId`,
 * then regrouped by each hit's `buyDate` (not `signalDate` — see `BuyDateGroup`). Hits are
 * appended in `result.results` array order, which the backend already returns in request
 * order — so no dependency on the component's current (possibly since-changed) selection
 * order is needed to keep "策略的排列順序與勾選順序一致". */
function buildUnionRows(result: ScanResponse): UnionRow[] {
  const map = new Map<string, { stockId: string; stockName: string; hits: (UnionHit & { buyDate: string })[] }>()
  for (const strategyResult of result.results) {
    for (const item of strategyResult.items) {
      let row = map.get(item.stockId)
      if (!row) {
        row = { stockId: item.stockId, stockName: item.stockName, hits: [] }
        map.set(item.stockId, row)
      }
      row.hits.push({ strategyCode: strategyResult.strategy, signalDate: item.signalDate, buyDate: item.buyDate })
    }
  }
  const withGroups: UnionRow[] = Array.from(map.values()).map((row) => {
    const byBuyDate = new Map<string, UnionHit[]>()
    for (const hit of row.hits) {
      const plainHit: UnionHit = { strategyCode: hit.strategyCode, signalDate: hit.signalDate }
      const bucket = byBuyDate.get(hit.buyDate)
      if (bucket) bucket.push(plainHit)
      else byBuyDate.set(hit.buyDate, [plainHit])
    }
    const buyDateGroups = Array.from(byBuyDate.entries())
      .map(([buyDate, hits]) => ({ buyDate, hits }))
      .sort((a, b) => (a.buyDate < b.buyDate ? 1 : a.buyDate > b.buyDate ? -1 : 0))
    const latestSignalDate = row.hits.reduce((max, h) => (h.signalDate > max ? h.signalDate : max), row.hits[0].signalDate)
    return {
      stockId: row.stockId,
      stockName: row.stockName,
      hits: row.hits.map((h) => ({ strategyCode: h.strategyCode, signalDate: h.signalDate })),
      latestSignalDate,
      buyDateGroups,
    }
  })
  return withGroups.sort((a, b) => {
    if (a.latestSignalDate !== b.latestSignalDate) return a.latestSignalDate < b.latestSignalDate ? 1 : -1
    return a.stockId < b.stockId ? -1 : a.stockId > b.stockId ? 1 : 0
  })
}

/** Composite key for `checkedItemKeys`/`backtestItemsByKey` — one `(stockId, buyDate)`
 * position, matching the backend's own uniqueness key for a backtest item. */
function itemKey(stockId: string, buyDate: string): string {
  return `${stockId}::${buyDate}`
}

/** 送出全部命中標的的全部相異買進日，勾選狀態與展開狀態都不影響請求內容 — one item per
 * distinct `(stockId, buyDate)`, `buyDate` taken verbatim from the scan response (never
 * recomputed). Shared by the auto-triggered backtest and 「重試回測」 so the two always send
 * identically-shaped payloads for the same `unionRows` (specs/frontend/strategy.md「自動
 * 回測與「重試回測」送出的 items[] 完全相同」). */
function buildBacktestItems(rows: UnionRow[]): BacktestRequestItem[] {
  return rows.flatMap((row) => row.buyDateGroups.map((group) => ({ stockId: row.stockId, buyDate: group.buyDate })))
}

interface BacktestTotals {
  /** Positions (筆, not 檔) counted toward the two totals — checked, and with a non-null
   * `sellDate`. Includes every checked child of an expanded-or-not multi-signal-date stock;
   * unaffected by which rows are currently expanded (specs/frontend/strategy.md「摺疊狀態
   * 不影響總計」). */
  includedCount: number
  totalCost: number
  totalProfit: number
  /** `null` when `includedCount` is 0 — never `0`, per「不得顯示成 0%」. */
  totalReturnPercent: number | null
  /** `sellDate === null` — 「尚無可賣出交易日」, never counted regardless of checkbox state.
   * Counts 筆 (positions), not 檔. */
  uncountedNoSellDate: number
  /** Checked-eligible (`sellDate` non-null) but unchecked by the user. Counts 筆. A position
   * that is both unbacktestable and unchecked is counted only in `uncountedNoSellDate`
   * above, never here. */
  uncountedUnchecked: number
}

/** Recomputes the two merged-table totals client-side from the backtest response's own
 * per-item `buyPrice`/`profit` and `lotSize` (never a hard-coded 1000) — so toggling a
 * checkbox never has to re-call the endpoint. Iterates `result.items` directly (one entry
 * per `(stockId, buyDate)` position already, per the current backend contract) rather
 * than the union rows' grouping, so the totals are exactly「已勾選且可回測的各筆」regardless
 * of expand/collapse state. With every row checked this must equal the response's own
 * `totalCost`/`totalProfit`/`totalReturnPercent` exactly (specs/frontend/strategy.md「全部
 * 勾選時…這兩條路徑必須交會」), since it excludes exactly the same `sellDate === null`
 * items the backend already excludes and applies the same weighted formula. */
function computeBacktestTotals(result: BacktestResponse, checkedItemKeys: Set<string>): BacktestTotals {
  let totalCost = 0
  let totalProfit = 0
  let includedCount = 0
  let uncountedNoSellDate = 0
  let uncountedUnchecked = 0
  for (const item of result.items) {
    if (item.sellDate === null) {
      uncountedNoSellDate += 1
      continue
    }
    if (!checkedItemKeys.has(itemKey(item.stockId, item.buyDate))) {
      uncountedUnchecked += 1
      continue
    }
    includedCount += 1
    totalCost += (item.buyPrice ?? 0) * result.lotSize
    totalProfit += item.profit ?? 0
  }
  const totalReturnPercent = includedCount > 0 && totalCost > 0 ? Math.round((totalProfit / totalCost) * 100 * 100) / 100 : null
  return { includedCount, totalCost, totalProfit, totalReturnPercent, uncountedNoSellDate, uncountedUnchecked }
}

interface ParentAggregate {
  checkedChildren: number
  totalChildren: number
  /** Checked AND backtestable (`sellDate` non-null) children — what「{N} 筆」counts. */
  includedCount: number
  /** Cost-weighted average buy price across `includedCount` children, `null` when 0
   * (specs/frontend/strategy.md「該檔的子筆全被取消勾選時…顯示「—」」). */
  avgBuyPrice: number | null
  /** Sum of `profit` across `includedCount` children — `0` is a real, displayable value
   * (only meaningful when `includedCount > 0`; callers check that before rendering it). */
  totalProfit: number
  /** Cost-weighted (`已勾選收益總和 ÷ 已勾選成本總和 × 100`) — the SAME formula as the two
   * header totals, never an arithmetic mean of the children's own return percentages
   * (specs/frontend/strategy.md「父列的算法必須與標題兩個總計同一套」). `null` when 0. */
  returnPercent: number | null
}

/** A multi-buy-date stock's collapsed-row aggregate, recomputed client-side from its
 * checked children only — never a network call (specs/frontend/strategy.md「父列隨子列的
 * 勾選即時重算，不重打端點」). */
function computeParentAggregate(
  stockId: string,
  groups: BuyDateGroup[],
  backtestItemsByKey: Map<string, BacktestResultItem>,
  checkedItemKeys: Set<string>,
  lotSize: number,
): ParentAggregate {
  let checkedChildren = 0
  let includedCount = 0
  let totalCost = 0
  let totalProfit = 0
  for (const group of groups) {
    const checked = checkedItemKeys.has(itemKey(stockId, group.buyDate))
    if (checked) checkedChildren += 1
    const item = backtestItemsByKey.get(itemKey(stockId, group.buyDate))
    if (!checked || !item || item.sellDate === null) continue
    includedCount += 1
    totalCost += (item.buyPrice ?? 0) * lotSize
    totalProfit += item.profit ?? 0
  }
  const avgBuyPrice = includedCount > 0 ? totalCost / (lotSize * includedCount) : null
  const returnPercent = includedCount > 0 && totalCost > 0 ? Math.round((totalProfit / totalCost) * 100 * 100) / 100 : null
  return { checkedChildren, totalChildren: groups.length, includedCount, avgBuyPrice, totalProfit, returnPercent }
}

/** 「—」視為最小值 — represented as `-Infinity` so both a `null` value and any real number
 * compare correctly through one branch-free formula, and two `null`s naturally tie
 * (`compareBySortDirection` then returns `0`, falling back to whatever order the input
 * array already had — see `handleSortClick`'s stable sort over the default-ordered
 * `unionRows`). */
function sortKeyValue(value: number | null): number {
  return value == null ? Number.NEGATIVE_INFINITY : value
}

function compareBySortDirection(aValue: number | null, bValue: number | null, direction: SortDirection): number {
  const a = sortKeyValue(aValue)
  const b = sortKeyValue(bValue)
  if (a === b) return 0
  return direction === 'desc' ? b - a : a - b
}

/** The value a row currently DISPLAYS for a sortable column — a single-buy-date row's own
 * backtest item, or a multi-buy-date parent's cost-weighted aggregate (「一檔多筆的父列以它
 * 畫面上顯示的合計值排序」— never the raw per-child values, which would let the sort order
 * disagree with the number actually on screen). */
function rowSortValue(
  row: UnionRow,
  column: SortColumn,
  backtestItemsByKey: Map<string, BacktestResultItem>,
  checkedItemKeys: Set<string>,
  lotSize: number,
): number | null {
  if (row.buyDateGroups.length >= 2) {
    const agg = computeParentAggregate(row.stockId, row.buyDateGroups, backtestItemsByKey, checkedItemKeys, lotSize)
    return column === 'buyPrice' ? agg.avgBuyPrice : agg.returnPercent
  }
  const group = row.buyDateGroups[0]
  if (!group) return null
  const item = backtestItemsByKey.get(itemKey(row.stockId, group.buyDate)) ?? null
  return column === 'buyPrice' ? (item?.buyPrice ?? null) : (item?.returnPercent ?? null)
}

/** A checkbox that can additionally render the browser's native indeterminate (半選) glyph
 * — React has no JSX prop for `indeterminate` (it isn't a real DOM attribute), so it has to
 * be poked onto the element imperatively. Used for every merged-table row checkbox (both
 * the tri-state parent and the plain checked/unchecked child/single-group cases) so the
 * imperative-ref plumbing lives in exactly one place. */
function RowCheckbox({
  checked,
  indeterminate = false,
  onChange,
  ariaLabel,
}: {
  checked: boolean
  indeterminate?: boolean
  onChange: (checked: boolean) => void
  ariaLabel: string
}) {
  const ref = (el: HTMLInputElement | null) => {
    if (el) el.indeterminate = indeterminate
  }
  return (
    <input
      ref={ref}
      type="checkbox"
      className="st-row-checkbox"
      checked={checked}
      onChange={(e) => onChange(e.target.checked)}
      // 點選列＝點選該列勾選框：the row body above this checkbox also carries an onClick
      // that performs the exact same toggle. Without stopping propagation here, a click on
      // the checkbox itself would both fire this `onChange` AND bubble up to the row's own
      // `onClick`, toggling twice and visibly cancelling itself out. This keeps "click the
      // checkbox" and "click the row" as two paths to the same single toggle, not two
      // stacked toggles.
      onClick={(e) => e.stopPropagation()}
      aria-label={ariaLabel}
    />
  )
}

/** Collapsible "N 檔…" note used for both `insufficientData` and `pendingConfirm` —
 * both are "checked but excluded" lists that must stay visually distinct from the
 * matched-hits table, never merged into it and never silently dropped. */
function ExpandableNote({ label, ids }: { label: string; ids: string[] }) {
  const [open, setOpen] = useState(false)
  return (
    <div className="st-note">
      <button type="button" className="st-note-toggle" onClick={() => setOpen((o) => !o)}>
        {label}
        <span className="st-note-caret">{open ? ' ▲' : ' ▼'}</span>
      </button>
      {open ? <div className="st-note-ids">{ids.join('、')}</div> : null}
    </div>
  )
}

export interface StrategyTabProps {
  /** Page-level "只看上市普通股" setting, owned by `StockListPage` and shared across all
   * three tabs (specs/frontend/stock-list.md「頁面層級設定」). Defaults to `true` so this
   * component still renders standalone (e.g. in tests that don't pass it). Only applies
   * to "全市場" scans — "指定股票" never sends it (specs/backend/strategy-scan.md). */
  commonStocksOnly?: boolean
}

export default function StrategyTab({ commonStocksOnly = true }: StrategyTabProps) {
  // ---------- strategy catalogue ----------
  const [catalog, setCatalog] = useState<StrategyCatalogItem[]>([])
  const [catalogStatus, setCatalogStatus] = useState<'loading' | 'success' | 'error'>('loading')

  const [selectionOrder, setSelectionOrder] = useState<StrategyCode[]>([])
  const [selectedPresets, setSelectedPresets] = useState<Partial<Record<StrategyCode, PresetCode>>>({})
  // Per-card 漲幅門檻 input for the three sensitivity-driven cards only, kept as the raw
  // string the user typed so a partial entry (e.g. "2.") isn't clobbered mid-keystroke.
  // Populated with the current preset's parsed default the first time a card is checked,
  // and again whenever its preset changes. Params-driven cards (累積上漲／反彈) never use
  // this — their inputs live in `paramInputs` below.
  const [risePercentInputs, setRisePercentInputs] = useState<Partial<Record<StrategyCode, string>>>({})
  // Names the one card the backend's `INVALID_RISE_PERCENT` fallback error belongs to
  // (client-side validation already blocks this in normal use). Only for sensitivity-driven
  // cards — params-driven cards' backend fallbacks land in `paramServerError` instead.
  const [invalidRisePercentStrategy, setInvalidRisePercentStrategy] = useState<StrategyCode | null>(null)
  // Generic per-parameter input, raw string, keyed by strategy code then param `code` —
  // covers every params-driven card's every input (累積上漲's `days`/`risePercent`;
  // 反彈's `dropDays`/`dropPercent`/`riseDays`/`risePercent`). Populated once from each
  // param's own `default` the first time its card is checked, then entirely in the user's
  // hands (no refill mechanism — there's no preset to switch).
  const [paramInputs, setParamInputs] = useState<Partial<Record<StrategyCode, Record<string, string>>>>({})
  // Optional param-group toggle state (currently only 反彈's `rise` group), keyed by
  // strategy code then group `code`. Populated from `paramGroups[].default` the first time
  // a params-driven card with groups is checked; purely data-driven off `paramGroups`.
  const [groupEnabled, setGroupEnabled] = useState<Partial<Record<StrategyCode, Record<string, boolean>>>>({})
  // Names the one card + message the current backend validation fallback belongs to.
  // Covers params-driven fallbacks (INVALID_DAYS/INVALID_DROP_DAYS/INVALID_RISE_DAYS/
  // INVALID_DROP_PERCENT/INVALID_RISE_PERCENT — client-side validation already blocks
  // these in normal use) and the three "not applicable" programmer-error codes
  // (PARAM_NOT_APPLICABLE/PRESET_NOT_APPLICABLE/DAYS_NOT_APPLICABLE), which can name
  // *any* card regardless of shape — rendered in both the params-driven and the
  // sensitivity-driven card branches below.
  const [paramServerError, setParamServerError] = useState<{ code: StrategyCode; message: string } | null>(null)

  // ---------- stock scope ----------
  const [scope, setScope] = useState<'ALL' | 'SELECTED'>('ALL')
  const [selectedStocks, setSelectedStocks] = useState<{ stockId: string; stockName: string }[]>([])
  const [stockSearch, setStockSearch] = useState('')
  const debouncedStockSearch = useDebouncedValue(stockSearch, 300)
  const [stockSuggestions, setStockSuggestions] = useState<StockListItem[]>([])

  // ---------- date range (以週選擇區間 — every control here only ever lands on a whole
  // ISO week, never a single day) ----------
  const [weekRange, setWeekRange] = useState<WeekRange>(defaultWeekRange())
  const [dateRangeServerError, setDateRangeServerError] = useState(false)
  const currentWeekMonday = toIsoDate(isoWeekInfo(today()).monday)
  // Monday strings sort chronologically exactly like the weeks they represent, so this
  // plain string compare is equivalent to comparing the two weeks themselves.
  const dateInvalid = weekRange.startMonday > weekRange.endMonday
  const scanStartDate = weekRange.startMonday
  // 結束週為本週時 endDate 為今日；否則為結束週的週日。No future date can ever come out of
  // this: every other end week is by construction no later than 本週 (stepping past it is
  // clamped in `shiftWeek`, and every quick-range result's end week literally is 本週).
  const scanEndDate =
    weekRange.endMonday === currentWeekMonday
      ? toIsoDate(today())
      : toIsoDate(isoWeekInfo(parseIsoDate(weekRange.endMonday)).sunday)
  // 起、迄週恰等於某個快捷鈕的結果時，該鈕呈選中樣式 — derived every render from the current
  // weeks themselves, not a separately-tracked flag, so stepping back to a week pair that
  // happens to match a shortcut highlights it even without clicking the button, and any
  // edit away from a match un-highlights it automatically.
  const activeShortcut: ShortcutKey | null =
    (['1m', '3m', '6m'] as ShortcutKey[]).find((key) => {
      const result = quickWeekRange(key === '1m' ? 1 : key === '3m' ? 3 : 6)
      return result.startMonday === weekRange.startMonday && result.endMonday === weekRange.endMonday
    }) ?? null

  // ---------- scan ----------
  const [scanStatus, setScanStatus] = useState<ScanStatus>('idle')
  const [scanResult, setScanResult] = useState<ScanResponse | null>(null)
  const [scanErrorMessage, setScanErrorMessage] = useState<string | null>(null)
  const [unknownIds, setUnknownIds] = useState<string[] | null>(null)
  const [lastScanPayload, setLastScanPayload] = useState<ScanRequest | null>(null)

  // ---------- 納入計算的勾選框 (per position — parent+child both key off this, front-end
  // only) ----------
  // Keyed by `itemKey(stockId, buyDate)`, one entry per distinct buy date. Populated
  // with every position checked the moment a scan succeeds; reset to "all checked" again on
  // the next 開始掃描 click, together with the backtest result itself.
  const [checkedItemKeys, setCheckedItemKeys] = useState<Set<string>>(new Set())

  // ---------- 「取消買進價高於 N 元」勾選框 ----------
  // The amount is page-level state, deliberately NOT reset by 開始掃描/重新掃描 (「金額在同一
  // 次進頁內保留…重新整理頁面回到 500」) — only this `useState` initializer, which only runs
  // once on mount, ever sets it back to '500'. Kept as a string (not a number) so a partial
  // keystroke like "1." is never silently clobbered, matching `risePercentInputs`'s shape.
  const [priceThresholdInput, setPriceThresholdInput] = useState('500')
  const [priceThresholdChecked, setPriceThresholdChecked] = useState(false)
  const [hideIncomplete, setHideIncomplete] = useState(false)

  // ---------- 總計浮動跟隨 ----------
  // `totalsAnchorRef`/`tableEndRef`/`unionBlockRef` are attached in `renderMergedTable`
  // below; `floatingTotalsVisible` and `floatingRightOffset` are recomputed by a scroll/
  // resize listener registered only while `backtestTotals` exists (see the effect further
  // down), never by a network response.
  const totalsAnchorRef = useRef<HTMLDivElement | null>(null)
  const tableEndRef = useRef<HTMLDivElement | null>(null)
  const unionBlockRef = useRef<HTMLDivElement | null>(null)
  const [floatingTotalsVisible, setFloatingTotalsVisible] = useState(false)
  const [floatingRightOffset, setFloatingRightOffset] = useState(0)

  // ---------- 展開狀態 (per stock, front-end only, never reset by 回測 — only by 開始掃描
  // implicitly starting a new scanResult) ----------
  // Default collapsed. Only meaningful for a stock with ≥ 2 distinct buy dates; toggling
  // it never re-fetches anything.
  const [expandedStockIds, setExpandedStockIds] = useState<Set<string>>(new Set())

  // ---------- 買進價／報酬率欄排序 (front-end only; only meaningful once backtestResult
  // exists, reset to default — both `null` — on every 開始掃描) ----------
  // `sortState` names the current column + direction (`null` = 還原預設排序). `sortedRowOrder`
  // is a SNAPSHOT of the top-level row order, captured only at the moment a header is
  // clicked (see `handleSortClick`) — later checkbox toggles recompute what a parent row
  // DISPLAYS but must never move any row, so rendering always replays this frozen order
  // instead of re-deriving it from the (possibly since-changed) live aggregates.
  const [sortState, setSortState] = useState<SortState | null>(null)
  const [sortedRowOrder, setSortedRowOrder] = useState<string[] | null>(null)

  // ---------- 回測 (auto-triggered after a successful scan with ≥1 hit — no button) ----------
  const [backtestStatus, setBacktestStatus] = useState<BacktestStatus>('idle')
  const [backtestResult, setBacktestResult] = useState<BacktestResponse | null>(null)
  const [backtestErrorMessage, setBacktestErrorMessage] = useState<string | null>(null)
  // Distinguishes a running 重試回測 from the initial auto-backtest — both share
  // `backtestStatus === 'running'`, but only the retry keeps the failure message on screen
  // and swaps its own button label to 回測中… (the auto-backtest has no button at all, just
  // the 「開始掃描」-adjacent hint). Reset on every terminal outcome (success or failure).
  const [isRetryingBacktest, setIsRetryingBacktest] = useState(false)
  // Generation counter + in-flight AbortController — 「開始掃描」bumps the generation and
  // aborts whatever backtest was in flight the instant it's clicked (before the new scan
  // even starts), and every backtest response checks its own generation before applying
  // itself. Together these guarantee a stale backtest response (one started against an
  // older hit list) can never overwrite a newer one, however the two happen to race
  // (specs/frontend/strategy.md「回測進行中『開始掃描』仍可按…一律丟棄」).
  const backtestGenerationRef = useRef(0)
  const backtestAbortControllerRef = useRef<AbortController | null>(null)

  // ---------- sync ----------
  const [lastSyncedAt, setLastSyncedAt] = useState<string | null>(null)
  const [syncStatus, setSyncStatus] = useState<SyncStatus>('idle')
  const [syncProgress, setSyncProgress] = useState<ProgressResponse | null>(null)
  const [syncSummary, setSyncSummary] = useState<ProgressResponse | null>(null)
  // `targetCount`/`caughtUpCount` from the *this-run's* `202` accept response — kept
  // separate from `syncSummary` (which comes from the progress poll) because the two
  // requests race independently; combining them at render time (rather than baking one
  // into the other at whichever moment happens to resolve first) means whichever arrives
  // second still triggers a correct re-render instead of freezing in a half-known state.
  // Reset to `null` on every new click so a slow-to-arrive previous job's numbers can
  // never be attributed to the next job's completion.
  const [syncMeta, setSyncMeta] = useState<{ targetCount: number; caughtUpCount: number; commonStocksOnly: boolean } | null>(
    null,
  )
  const [syncErrorMessage, setSyncErrorMessage] = useState<string | null>(null)
  const [showFailedList, setShowFailedList] = useState(false)

  // ---------- stock universe (list) size + "更新股票清單" ----------
  const [totalStockCount, setTotalStockCount] = useState<number | null>(null)
  const [universeImportStatus, setUniverseImportStatus] = useState<SyncStatus>('idle')
  const [universeImportSummary, setUniverseImportSummary] = useState<UniverseImportResponse | null>(null)
  const [universeImportErrorMessage, setUniverseImportErrorMessage] = useState<string | null>(null)

  // Aborts whatever backtest request is still in flight if this component unmounts —
  // otherwise its `.then`/`.catch` would try to set state on an unmounted component.
  useEffect(() => () => backtestAbortControllerRef.current?.abort(), [])

  // Catalogue — the sole source of strategy names / preset names / description text.
  useEffect(() => {
    const controller = new AbortController()
    fetchStrategyCatalog(controller.signal)
      .then((resp) => {
        setCatalog(resp.strategies ?? [])
        setCatalogStatus('success')
      })
      .catch((err: unknown) => {
        if (err instanceof DOMException && err.name === 'AbortError') return
        setCatalogStatus('error')
      })
    return () => controller.abort()
  }, [])

  // Last-synced time on entry; also detect a sync already in flight (e.g. the startup
  // catch-up, or another tab having just started one) so we start polling right away.
  useEffect(() => {
    const controller = new AbortController()
    fetchSyncProgress('PRICE_BACKFILL', controller.signal)
      .then((resp) => {
        setLastSyncedAt(resp.lastSyncedAt ?? null)
        setSyncProgress(resp)
        if ((resp.pending ?? 0) > 0 || (resp.running ?? 0) > 0) {
          setSyncStatus('running')
        }
      })
      .catch((err: unknown) => {
        if (err instanceof DOMException && err.name === 'AbortError') return
        // Non-fatal — the page just keeps whatever last-synced display it already has.
      })
    return () => controller.abort()
  }, [])

  // Stock universe size on entry — `GET /api/stocks?page=1&size=1`, `size=1` because only
  // `total` is needed here, not the list content.
  useEffect(() => {
    const controller = new AbortController()
    // Unfiltered — this widget shows the raw active-stock universe size (matches
    // `totalActiveCount` from 更新股票清單), not the page-level 只看上市普通股 setting.
    fetchStocks(
      { keyword: '', market: 'ALL', includeInactive: false, page: 1, size: 1, sort: 'stockId', order: 'asc' },
      false,
      controller.signal,
    )
      .then((resp) => setTotalStockCount(resp.total))
      .catch((err: unknown) => {
        if (err instanceof DOMException && err.name === 'AbortError') return
        // Non-fatal — the page just keeps whatever count is already shown.
      })
    return () => controller.abort()
  }, [])

  // Poll while a sync is running. Stays alive even while this tab is hidden
  // (display:none) because StockListPage keeps both tabs mounted.
  useEffect(() => {
    if (syncStatus !== 'running') return
    let cancelled = false

    const poll = () => {
      fetchSyncProgress('PRICE_BACKFILL')
        .then((resp) => {
          if (cancelled) return
          setSyncProgress(resp)
          if (resp.pending === 0 && resp.running === 0) {
            setSyncStatus('idle')
            setLastSyncedAt(resp.lastSyncedAt ?? null)
            setSyncSummary(resp)
          }
        })
        .catch(() => {
          // Transient network hiccup — keep polling on the next tick rather than giving up.
        })
    }

    poll()
    const timer = setInterval(poll, POLL_INTERVAL_MS)
    return () => {
      cancelled = true
      clearInterval(timer)
    }
  }, [syncStatus])

  // Stock-scope search suggestions.
  useEffect(() => {
    if (scope !== 'SELECTED' || !debouncedStockSearch) {
      setStockSuggestions([])
      return
    }
    const controller = new AbortController()
    // Unfiltered — 指定股票 lets the user name any stock explicitly (ETFs included);
    // the page-level 只看上市普通股 setting never applies to this branch (backend
    // doesn't filter it either, see specs/backend/strategy-scan.md「掃描範圍」).
    fetchStocks(
      {
        keyword: debouncedStockSearch,
        market: 'ALL',
        includeInactive: true,
        page: 1,
        size: 20,
        sort: 'stockId',
        order: 'asc',
      },
      false,
      controller.signal,
    )
      .then((resp) => setStockSuggestions(resp.items))
      .catch((err: unknown) => {
        if (err instanceof DOMException && err.name === 'AbortError') return
        setStockSuggestions([])
      })
    return () => controller.abort()
  }, [scope, debouncedStockSearch])

  const toggleStrategy = (code: StrategyCode) => {
    setSelectionOrder((order) => (order.includes(code) ? order.filter((c) => c !== code) : [...order, code]))
    const strategy = catalog.find((s) => s.code === code)
    if (isParamsDriven(strategy)) {
      // No sensitivity to pick a preset from — every param is populated once from its own
      // `default` and is afterwards entirely in the user's hands (no refill mechanism,
      // unlike changePreset below).
      setParamInputs((inputs) => {
        if (inputs[code] !== undefined) return inputs
        const values: Record<string, string> = {}
        for (const param of strategy?.params ?? []) values[param.code] = String(param.default)
        return { ...inputs, [code]: values }
      })
      setGroupEnabled((state) => {
        if (state[code] !== undefined) return state
        const values: Record<string, boolean> = {}
        for (const group of strategy?.paramGroups ?? []) values[group.code] = group.default
        return { ...state, [code]: values }
      })
      return
    }
    setSelectedPresets((presets) => (presets[code] ? presets : { ...presets, [code]: 'STANDARD' }))
    setRisePercentInputs((inputs) => {
      if (inputs[code] !== undefined) return inputs
      const presetMeta = strategy?.presets.find((p) => p.code === 'STANDARD')
      return { ...inputs, [code]: String(extractDefaultRisePercent(presetMeta?.description)) }
    })
  }

  /** 切換靈敏度會重新填入該靈敏度的漲幅值，覆蓋使用者已輸入的數字 — a preset is a named
   * bundle of defaults, so selecting one must show that bundle's own value, not a stale
   * number left over from whatever the user typed under the previous preset. */
  const changePreset = (code: StrategyCode, preset: PresetCode) => {
    setSelectedPresets((presets) => ({ ...presets, [code]: preset }))
    const presetMeta = catalog.find((s) => s.code === code)?.presets.find((p) => p.code === preset)
    setRisePercentInputs((inputs) => ({ ...inputs, [code]: String(extractDefaultRisePercent(presetMeta?.description)) }))
    setInvalidRisePercentStrategy((current) => (current === code ? null : current))
  }

  const changeRisePercentInput = (code: StrategyCode, value: string) => {
    setRisePercentInputs((inputs) => ({ ...inputs, [code]: value }))
    setInvalidRisePercentStrategy((current) => (current === code ? null : current))
  }

  const changeParamInput = (code: StrategyCode, paramCode: string, value: string) => {
    setParamInputs((inputs) => ({ ...inputs, [code]: { ...(inputs[code] ?? {}), [paramCode]: value } }))
    setParamServerError((current) => (current?.code === code ? null : current))
  }

  /** Toggles one param group's checkbox — resolving its *current* effective state (the
   * user's own choice if any, else the group's own `default`) before flipping it, so the
   * very first click always flips away from what's actually displayed. */
  const toggleGroup = (strategy: StrategyCatalogItem, groupCode: string) => {
    const group = getParamGroup(strategy, groupCode)
    setGroupEnabled((state) => {
      const current = state[strategy.code]?.[groupCode] ?? group?.default ?? true
      return { ...state, [strategy.code]: { ...(state[strategy.code] ?? {}), [groupCode]: !current } }
    })
    setParamServerError((current) => (current?.code === strategy.code ? null : current))
  }

  const applyShortcut = (key: ShortcutKey) => {
    setWeekRange(quickWeekRange(key === '1m' ? 1 : key === '3m' ? 3 : 6))
    setDateRangeServerError(false)
  }

  /** Steps one side of the range by one whole week. `end` is clamped so it can never move
   * past 本週 — the only bound either side has; a start week that ends up after the end
   * week is left as-is and simply blocks the scan (see `dateInvalid`), not silently fixed. */
  const shiftWeek = (side: 'start' | 'end', deltaWeeks: number) => {
    setWeekRange((r) => {
      if (side === 'start') return { ...r, startMonday: toIsoDate(shiftDays(parseIsoDate(r.startMonday), deltaWeeks * 7)) }
      const next = toIsoDate(shiftDays(parseIsoDate(r.endMonday), deltaWeeks * 7))
      const max = toIsoDate(isoWeekInfo(today()).monday)
      return { ...r, endMonday: next > max ? max : next }
    })
    setDateRangeServerError(false)
  }

  const addStock = (stock: StockListItem) => {
    setSelectedStocks((list) => {
      if (list.length >= STOCK_ID_CAP) return list
      if (list.some((s) => s.stockId === stock.stockId)) return list
      return [...list, { stockId: stock.stockId, stockName: stock.stockName }]
    })
    setStockSearch('')
    setStockSuggestions([])
  }

  const removeStock = (stockId: string) => {
    setSelectedStocks((list) => list.filter((s) => s.stockId !== stockId))
    setUnknownIds((ids) => (ids ? ids.filter((id) => id !== stockId) : ids))
  }

  const buildScanPayload = (): ScanRequest => ({
    strategies: selectionOrder.map((code): ScanStrategySelection => {
      const strategy = catalog.find((s) => s.code === code)
      if (isParamsDriven(strategy)) {
        // 這張卡片一律不送 preset — it has no sensitivity to name. Every param's current
        // value goes out by its own `code` as the request field name; a grouped param is
        // omitted entirely when its group is off, and each group's own on/off state goes
        // out under `require<Group>`. None of this branches on `code` — it all comes from
        // this strategy's own `params`/`paramGroups`.
        const values = paramInputs[code] ?? {}
        const groupState = groupEnabled[code] ?? {}
        const selection: ScanStrategySelection = { code }
        // Every field name below comes from the API's own data (a param's `code`, or
        // `require<Group>` derived from a group's `code`) — the object's shape can't be
        // known statically, so this is the one narrow spot that steps outside it.
        const bag = selection as unknown as Record<string, number | boolean>
        for (const group of strategy?.paramGroups ?? []) {
          bag[requireFieldName(group.code)] = groupState[group.code] ?? group.default
        }
        for (const param of strategy?.params ?? []) {
          if (!isGroupOn(strategy, groupEnabled, param)) continue
          bag[param.code] = Number(values[param.code] ?? param.default)
        }
        return selection
      }
      return {
        code,
        preset: selectedPresets[code] ?? 'STANDARD',
        // 值與該靈敏度預設值相同時仍照送 — every selected card's current number goes out,
        // never omitted just because it happens to match the preset's own default.
        risePercent: Number(risePercentInputs[code] ?? '0'),
      }
    }),
    stockIds: scope === 'SELECTED' ? selectedStocks.map((s) => s.stockId) : undefined,
    // 起始週週一／結束週週日（本週時為今日）— never the week selectors' own values directly.
    startDate: scanStartDate,
    endDate: scanEndDate,
    // 使用者明確指名的代號不代為過濾 — only sent for a 全市場 scan.
    commonStocksOnly: scope === 'ALL' ? commonStocksOnly : undefined,
  })

  // Maps a params-driven backend validation error code to the `params` entry it names —
  // data-driven off the strategy's own `params`, not a hard-coded per-code table.
  const PARAM_ERROR_CODE_TO_PARAM_CODE: Record<string, string> = {
    INVALID_DAYS: 'days',
    INVALID_DROP_DAYS: 'dropDays',
    INVALID_RISE_DAYS: 'riseDays',
    INVALID_DROP_PERCENT: 'dropPercent',
  }

  /** Sends `POST /api/strategies/backtest` — called automatically once right after a scan
   * succeeds with ≥1 hit, and again (with the SAME `items`, re-derived from the same
   * `unionRows`) whenever「重試回測」is pressed. `isRetry` only changes what's displayed
   * while it's running (重試 keeps the failure message on screen and swaps its own button
   * label; the auto-backtest has neither) — the request itself is identical either way. */
  const runBacktest = (items: BacktestRequestItem[], isRetry: boolean) => {
    backtestGenerationRef.current += 1
    const generation = backtestGenerationRef.current
    backtestAbortControllerRef.current?.abort()
    const controller = new AbortController()
    backtestAbortControllerRef.current = controller
    setBacktestStatus('running')
    setIsRetryingBacktest(isRetry)
    if (!isRetry) setBacktestErrorMessage(null)
    backtestStrategies({ items }, controller.signal)
      .then((resp) => {
        // 回測進行中「開始掃描」仍可按，其回應若在新掃描之後才到，一律丟棄 — a newer scan
        // (or a newer retry) bumps the generation before this one's response can arrive, so
        // an outdated response can never overwrite a result it no longer corresponds to.
        if (backtestGenerationRef.current !== generation) return
        setBacktestResult(resp)
        setPriceThresholdChecked(false)
        setHideIncomplete(resp.items.some((item) => item.sellDate === null))
        setBacktestStatus('success')
        setBacktestErrorMessage(null)
        setIsRetryingBacktest(false)
      })
      .catch((err: unknown) => {
        if (err instanceof DOMException && err.name === 'AbortError') return
        if (backtestGenerationRef.current !== generation) return
        setBacktestStatus('error')
        setBacktestErrorMessage('回測失敗，請稍後再試')
        setIsRetryingBacktest(false)
      })
  }

  const runScan = (payload: ScanRequest) => {
    setScanStatus('scanning')
    setScanErrorMessage(null)
    setUnknownIds(null)
    setInvalidRisePercentStrategy(null)
    setParamServerError(null)
    setLastScanPayload(payload)
    // 重新掃描一定清空回測結果，在按下的當下就做，不等新結果回來 — an old backtest is for
    // an old hit list; leaving it next to a new one would be two datasets that no longer
    // correspond, with nothing on screen to say so.
    setBacktestStatus('idle')
    setBacktestResult(null)
    setPriceThresholdChecked(false)
    setHideIncomplete(false)
    setBacktestErrorMessage(null)
    setIsRetryingBacktest(false)
    // 重新掃描時排序回到預設，指示一併消失 — sorting only ever makes sense against the hit
    // list it was clicked on; a stale frozen order from the previous list must not survive
    // into the next one.
    setSortState(null)
    setSortedRowOrder(null)
    // 進行中的那次回測隨即作廢 — bump the generation and abort the in-flight request (if
    // any) the instant 開始掃描 is clicked, before the new scan itself even starts, so its
    // eventual response can never land in the new hit list.
    backtestGenerationRef.current += 1
    backtestAbortControllerRef.current?.abort()
    backtestAbortControllerRef.current = null
    scanStrategies(payload)
      .then((resp) => {
        setScanResult(resp)
        setScanStatus('success')
        // 掃描完成當下即出現，預設全部勾選 — a fresh hit list is a different batch of
        // positions, so any prior checkbox state (including which rows were unchecked) must
        // not carry over. One key per distinct signal date, not per stock.
        const rows = buildUnionRows(resp)
        const initiallyChecked = new Set<string>()
        for (const row of rows) {
          for (const group of row.buyDateGroups) initiallyChecked.add(itemKey(row.stockId, group.buyDate))
        }
        setCheckedItemKeys(initiallyChecked)
        // 重新掃描時展開狀態沒有規定要保留或清空——新清單是不同的一批股票，沿用舊的展開
        // 對應不到新的列，乾脆重置回全部摺疊（預設狀態）。
        setExpandedStockIds(new Set())
        // 掃描成功且命中至少一檔時，不需任何使用者動作即自動送出一次回測；掃描失敗或命中
        // 0 檔時不送——沒有命中清單就沒有東西可以回測。
        if (rows.length > 0) runBacktest(buildBacktestItems(rows), false)
      })
      .catch((err: unknown) => {
        if (err instanceof ApiError && err.code === 'UNKNOWN_STOCK_ID') {
          setUnknownIds(err.unknownIds ?? [])
          setScanStatus(scanResult ? 'success' : 'idle')
          return
        }
        if (err instanceof ApiError && err.code === 'INVALID_DATE_RANGE') {
          // Backend fallback only — client-side validation already blocks this in normal
          // use (`dateInvalid`). Same message, same place: below the range.
          setDateRangeServerError(true)
          setScanStatus(scanResult ? 'success' : 'idle')
          return
        }
        if (err instanceof ApiError && err.code === 'INVALID_RISE_PERCENT') {
          // Backend fallback only — client-side validation already blocks this in normal
          // use. Must name the offending card, not a page-wide generic error. `risePercent`
          // belongs to a params-driven card's own `params` for 累積上漲/反彈, and to the
          // dedicated 漲幅門檻 input for the three sensitivity-driven cards.
          const code = (err.strategy as StrategyCode) ?? null
          const strategy = code ? catalog.find((s) => s.code === code) : undefined
          if (code && isParamsDriven(strategy)) {
            const param = getStrategyParam(strategy, 'risePercent')
            setParamServerError({ code, message: param ? paramErrorMessage(param) : '漲幅門檻不合法' })
          } else {
            setInvalidRisePercentStrategy(code)
          }
          setScanStatus(scanResult ? 'success' : 'idle')
          return
        }
        if (
          err instanceof ApiError &&
          err.code !== null &&
          Object.prototype.hasOwnProperty.call(PARAM_ERROR_CODE_TO_PARAM_CODE, err.code)
        ) {
          // Backend fallback only — client-side validation already blocks this in normal
          // use. Must name the offending card, not a page-wide generic error.
          const code = (err.strategy as StrategyCode) ?? null
          const strategy = code ? catalog.find((s) => s.code === code) : undefined
          const param = strategy ? getStrategyParam(strategy, PARAM_ERROR_CODE_TO_PARAM_CODE[err.code]) : undefined
          if (code) setParamServerError({ code, message: param ? paramErrorMessage(param) : '參數不合法' })
          setScanStatus(scanResult ? 'success' : 'idle')
          return
        }
        if (
          err instanceof ApiError &&
          (err.code === 'PARAM_NOT_APPLICABLE' || err.code === 'PRESET_NOT_APPLICABLE' || err.code === 'DAYS_NOT_APPLICABLE')
        ) {
          // All three are treated as a programmer error: normal operation never produces
          // them — each card only ever renders the controls/fields its own catalogue entry
          // declares, so this combination of "selected strategy + submitted field" can't
          // arise from user interaction. Still must land under the named card, not go
          // page-wide, and must not clear existing results or lock 「開始掃描」 — this is a
          // localized diagnostic, not a scan failure. `param` (when present) is echoed
          // verbatim from the response, never hard-coded per strategy; when absent
          // (PRESET_NOT_APPLICABLE/DAYS_NOT_APPLICABLE typically don't carry one), a
          // generic message is shown instead.
          const code = (err.strategy as StrategyCode) ?? null
          if (code) {
            setParamServerError({
              code,
              message: err.param ? `帶入了不適用的參數：${err.param}` : '此策略不支援這次請求帶入的參數組合',
            })
          }
          setScanStatus(scanResult ? 'success' : 'idle')
          return
        }
        setScanStatus('error')
        setScanErrorMessage('掃描失敗，請稍後再試')
      })
  }

  const noStrategySelected = selectionOrder.length === 0
  const noStockSelected = scope === 'SELECTED' && selectedStocks.length === 0
  const hasInvalidRisePercent = selectionOrder.some((code) => {
    const strategy = catalog.find((s) => s.code === code)
    if (isParamsDriven(strategy)) return false
    return isRisePercentInputInvalid(risePercentInputs[code] ?? '', SENSITIVITY_RISE_PERCENT_RANGE)
  })
  const hasInvalidParams = selectionOrder.some((code) => {
    const strategy = catalog.find((s) => s.code === code)
    if (!isParamsDriven(strategy)) return false
    const values = paramInputs[code] ?? {}
    return (strategy?.params ?? []).some((param) => {
      if (!isGroupOn(strategy, groupEnabled, param)) return false
      return isParamInputInvalid(values[param.code] ?? '', param)
    })
  })
  const canScan =
    !noStrategySelected &&
    !noStockSelected &&
    !dateInvalid &&
    !hasInvalidRisePercent &&
    !hasInvalidParams &&
    scanStatus !== 'scanning'

  const handleScanClick = () => {
    if (!canScan) return
    runScan(buildScanPayload())
  }

  const handleRetry = () => {
    if (lastScanPayload) runScan(lastScanPayload)
  }

  // Merged hit table — always one table regardless of how many strategies were selected.
  const unionRows = scanResult ? buildUnionRows(scanResult) : []

  /** Toggles a single position (single-group row, or one child of an expanded parent). */
  const toggleItemChecked = (stockId: string, buyDate: string) => {
    const key = itemKey(stockId, buyDate)
    setCheckedItemKeys((keys) => {
      const next = new Set(keys)
      if (next.has(key)) next.delete(key)
      else next.add(key)
      return next
    })
  }

  /** Parent checkbox click — 「點擊半選狀態的父列勾選框，一律變成全部勾選」falls out of
   * this for free: the controlled `checked` prop is only `true` when every child is already
   * checked, so a half-checked (indeterminate) parent's native click event always reports
   * `newChecked === true` (browser toggles off the *checked* value, not off `indeterminate`),
   * landing in the "check every child" branch below rather than "uncheck every child". */
  const toggleParentChecked = (stockId: string, groups: BuyDateGroup[], newChecked: boolean) => {
    setCheckedItemKeys((keys) => {
      const next = new Set(keys)
      for (const group of groups) {
        const key = itemKey(stockId, group.buyDate)
        if (newChecked) next.add(key)
        else next.delete(key)
      }
      return next
    })
  }

  const toggleExpanded = (stockId: string) => {
    setExpandedStockIds((ids) => {
      const next = new Set(ids)
      if (next.has(stockId)) next.delete(stockId)
      else next.add(stockId)
      return next
    })
  }

  /** 「重試回測」— only rendered while `backtestStatus === 'error'` (see JSX below); re-sends
   * ONLY the backtest, with the exact same `items[]` `buildBacktestItems` would derive from
   * this same `unionRows` (the scan itself is never re-run). */
  const handleRetryBacktest = () => {
    if (backtestStatus !== 'error') return
    runBacktest(buildBacktestItems(unionRows), true)
  }

  const handleSyncClick = () => {
    setSyncErrorMessage(null)
    setSyncMeta(null)
    setSyncStatus('running')
    // 沿用頁面層級的「只看上市普通股」設定 — captured from the prop at click time, not
    // re-read later, so the completion summary always describes the population this
    // particular request actually asked for.
    startBackfill({ startDate: BACKFILL_START_DATE, endDate: toIsoDate(today()), catchUp: true, commonStocksOnly })
      .then((resp) => {
        setSyncMeta({ targetCount: resp.targetCount, caughtUpCount: resp.caughtUpCount, commonStocksOnly })
      })
      .catch((err: unknown) => {
        if (err instanceof ApiError && err.code === 'JOB_ALREADY_RUNNING') {
          // Not an error — someone else's sync is already running; stay in the
          // running state and let the polling effect pick up its progress. We never
          // started that job ourselves, so its caughtUpCount/targetCount stay unknown
          // (syncMeta stays null) — the completion summary falls back to the plain
          // done/failed/skipped counts rather than guessing.
          return
        }
        setSyncStatus('idle')
        setSyncErrorMessage('同步啟動失敗，請稍後再試')
      })
  }

  // "更新股票清單" — a short synchronous action (single upstream request), deliberately
  // independent of `syncStatus`: neither button disables the other (no shared concurrency
  // lock on the backend either — see specs/backend/stock-universe-import.md 的「併發」).
  // Never polls a progress endpoint and never auto-triggers a sync or a re-scan.
  const handleImportUniverseClick = () => {
    setUniverseImportErrorMessage(null)
    setUniverseImportSummary(null)
    setUniverseImportStatus('running')
    importStockUniverse()
      .then((resp) => {
        setUniverseImportSummary(resp)
        setTotalStockCount(resp.totalActiveCount)
        setUniverseImportStatus('idle')
      })
      .catch((err: unknown) => {
        setUniverseImportStatus('idle')
        // All three failure paths leave `stock` completely unwritten — the displayed
        // "共 N 檔" (`totalStockCount`) is deliberately left untouched here.
        if (err instanceof ApiError && err.code === 'UPSTREAM_EMPTY') {
          setUniverseImportErrorMessage('交易所尚未發布今日清單，請稍後再試')
          return
        }
        if (err instanceof ApiError && (err.code === 'UPSTREAM_UNAVAILABLE' || err.code === 'UPSTREAM_MALFORMED')) {
          setUniverseImportErrorMessage('無法取得交易所股票清單，請稍後再試')
          return
        }
        setUniverseImportErrorMessage('更新股票清單失敗，請稍後再試')
      })
  }

  const completedCount = (p: ProgressResponse) => p.done + p.failed + p.skipped
  const progressPercent = (p: ProgressResponse) => (p.total > 0 ? Math.round((completedCount(p) / p.total) * 100) : 0)

  const strategyName = (code: StrategyCode) => catalog.find((s) => s.code === code)?.name ?? code
  const presetName = (code: StrategyCode, preset: PresetCode) =>
    catalog.find((s) => s.code === code)?.presets.find((p) => p.code === preset)?.name ?? preset

  /** 「本次採用參數」一行的其中一段 — always sourced from the scan RESPONSE, never from
   * the current (possibly since-edited) card inputs, so editing an input after scanning
   * can never retroactively rewrite what the results say they were computed with.
   * `preset`/`days`/(`dropDays`+`dropPercent`[+`riseDays`+`risePercent`]) are mutually
   * exclusive on the response — whichever is present decides the format, never a branch on
   * `result.strategy`. */
  const formatStrategyParams = (result: StrategyResult): string => {
    const name = strategyName(result.strategy)
    if (result.preset !== undefined) {
      return `${name}（${presetName(result.strategy, result.preset as PresetCode)}）`
    }
    if (result.days !== undefined) {
      return `${name}（${result.days} 日）`
    }
    if (result.dropDays !== undefined && result.dropPercent !== undefined) {
      const dropPart = `${result.dropDays} 日跌 ${formatTrimmedPercent(result.dropPercent)}%`
      const risePart =
        result.requireRise === true && result.riseDays !== undefined && result.risePercent !== undefined
          ? ` → ${result.riseDays} 日反彈 ${formatTrimmedPercent(result.risePercent)}%`
          : ''
      return `${name}（${dropPart}${risePart}）`
    }
    return name
  }

  const renderUnionHits = (hits: UnionHit[]) => {
    const nodes: ReactNode[] = []
    hits.forEach((hit, idx) => {
      if (idx > 0) nodes.push(
        <span key={`sep-${hit.strategyCode}-${idx}`} className="st-union-sep">
          ・
        </span>,
      )
      nodes.push(
        <span key={`${hit.strategyCode}-${idx}`} className="st-union-tag">
          <span className="st-union-tag-name">{strategyName(hit.strategyCode)}</span>{' '}
          <span className="st-union-tag-date">{hit.signalDate}</span>
        </span>,
      )
    })
    return nodes
  }

  const backtestItemsByKey = (() => {
    const map = new Map<string, BacktestResultItem>()
    if (backtestResult) for (const item of backtestResult.items) map.set(itemKey(item.stockId, item.buyDate), item)
    return map
  })()

  const backtestTotals = backtestResult ? computeBacktestTotals(backtestResult, checkedItemKeys) : null

  // ---------- 總計浮動跟隨 ----------
  // A plain scroll/resize listener (not CSS `position: sticky`) so "should the floating
  // copy be showing right now" is an explicit, unit-testable boolean rather than something
  // only a real layout engine can prove — see `computeFloatingTotalsVisible` above.
  // Registered only while the three totals exist (回測完成後) and torn down the instant they
  // don't (重新掃描／回測前／回測中／回測失敗), per「只在回測完成後存在，與三個總計同生同
  // 滅」. Never issues a network request — pure DOM measurement, run once immediately on
  // registration (so a completed backtest reflects the scroll position the user is already
  // at) and again on every subsequent scroll/resize.
  const hasBacktestTotals = backtestTotals !== null
  useEffect(() => {
    if (!hasBacktestTotals) return
    const measure = () => {
      const anchor = totalsAnchorRef.current
      const tableEnd = tableEndRef.current
      const panel = unionBlockRef.current
      if (!anchor || !tableEnd || !panel) return
      setFloatingTotalsVisible(computeFloatingTotalsVisible(anchor.getBoundingClientRect().top, tableEnd.getBoundingClientRect().top))
      setFloatingRightOffset(window.innerWidth - panel.getBoundingClientRect().right)
    }
    measure()
    window.addEventListener('scroll', measure, { passive: true })
    window.addEventListener('resize', measure)
    return () => {
      window.removeEventListener('scroll', measure)
      window.removeEventListener('resize', measure)
      setFloatingTotalsVisible(false)
    }
  }, [hasBacktestTotals])

  /** The three 總成本／總報酬率／總收益 items — shared verbatim between their in-flow
   * position and the floating copy that appears once they've scrolled out of view, so there
   * is exactly one computation and one set of formatting/color rules for both
   * (specs/frontend/strategy.md「總計浮動跟隨」「浮動的數字與原位的數字是同一份…不得另算一
   * 份」). */
  const renderTotalsTriplet = (totals: BacktestTotals) => (
    <>
      <div className="st-total-item">
        <span className="st-total-label">總成本（每筆 1 張）</span>
        <span className="st-total-value">
          {totals.includedCount === 0 ? <span className="sl-muted">—</span> : formatAmount(totals.totalCost)}
        </span>
      </div>
      <div className="st-total-item">
        <span className="st-total-label">總報酬率</span>
        <span className={`st-total-value ${signColorClass(totals.totalReturnPercent)}`}>
          {formatPercent2(totals.totalReturnPercent)}
        </span>
      </div>
      <div className="st-total-item">
        <span className="st-total-label">總收益（每筆 1 張）</span>
        <span className={`st-total-value ${signColorClass(totals.includedCount > 0 ? totals.totalProfit : null)}`}>
          {totals.includedCount === 0 ? '—' : formatAmount(totals.totalProfit)}
        </span>
      </div>
    </>
  )

  // ---------- 買進價／報酬率欄排序 ----------
  // Rows to actually render: `unionRows`'s own default order (最新 signalDate 由新到舊、
  // 同日 stockId 升冪) whenever no sort is active, or the frozen snapshot from the last
  // header click otherwise. Looking rows up by stockId rather than storing row objects
  // directly in `sortedRowOrder` keeps the snapshot itself trivially serializable state
  // (just stock IDs) while still always rendering the current `unionRows` row data.
  const displayRows = (() => {
    if (!sortState || !sortedRowOrder) return unionRows
    const byStockId = new Map(unionRows.map((row) => [row.stockId, row]))
    return sortedRowOrder.map((id) => byStockId.get(id)).filter((row): row is UnionRow => row != null)
  })()

  /** Header click for 買進價／報酬率 — 降冪 → 升冪 → 還原預設排序 循環；switching to the
   * OTHER sortable column always restarts at 降冪 for that column. The row order is
   * snapshotted here, from what's on screen at the moment of the click, into
   * `sortedRowOrder`; nothing else ever mutates that snapshot, so a later checkbox toggle
   * changes what a parent row DISPLAYS without moving it (specs/frontend/strategy.md「排序
   * 後切換勾選，列的位置不動…下一次點表頭時，才依當下顯示的值…重排」). */
  const handleSortClick = (column: SortColumn) => {
    if (!backtestResult) return
    const nextState: SortState | null =
      sortState?.column !== column
        ? { column, direction: 'desc' }
        : sortState.direction === 'desc'
          ? { column, direction: 'asc' }
          : null
    if (!nextState) {
      setSortState(null)
      setSortedRowOrder(null)
      return
    }
    const lotSize = backtestResult.lotSize
    const order = [...unionRows]
      .sort((a, b) =>
        compareBySortDirection(
          rowSortValue({ ...a, buyDateGroups: visibleGroups(a) }, nextState.column, backtestItemsByKey, checkedItemKeys, lotSize),
          rowSortValue({ ...b, buyDateGroups: visibleGroups(b) }, nextState.column, backtestItemsByKey, checkedItemKeys, lotSize),
          nextState.direction,
        ),
      )
      .map((row) => row.stockId)
    setSortState(nextState)
    setSortedRowOrder(order)
  }

  // ---------- 「取消全選」勾選框 ----------
  // Every position (筆) across every row — single-buy-date rows, every child of every
  // multi-buy-date stock, including unbacktestable positions and positions currently
  // hidden inside a collapsed parent. `checkedItemKeys` already tracks exactly this set
  // (populated in full the moment a scan succeeds, see `runScan`), so "全部的筆都沒有勾選"
  // is just "the tracked set is empty" — no separate derivation is needed.
  const allItemKeys = unionRows.flatMap((row) => row.buyDateGroups.map((group) => itemKey(row.stockId, group.buyDate)))
  // 打勾代表「目前全部的筆都沒有勾選」——一個從各筆推導出來的值，不是獨立記住的旗標。
  const cancelAllChecked = backtestResult !== null && allItemKeys.length > 0 && checkedItemKeys.size === 0

  /** 勾選「取消全選」→ 全部取消；取消勾選「取消全選」→ 全部勾回。完全在前端完成，不重新
   * 呼叫任何端點——`allItemKeys` 已含表上（含摺疊中的子列與無法回測的筆）的每一筆。 */
  const toggleCancelAll = (turnOn: boolean) => {
    setCheckedItemKeys(turnOn ? new Set() : new Set(allItemKeys))
    setPriceThresholdChecked(false)
    setHideIncomplete(false)
  }

  // ---------- 「取消買進價高於 N 元」勾選框 ----------
  const priceThresholdInvalid = isPriceThresholdInputInvalid(priceThresholdInput)
  const priceThresholdAmount = priceThresholdInvalid ? null : Number(priceThresholdInput)
  // 高價組：`buyPrice` 嚴格大於金額的每一筆——單筆列與每一個子列各自判斷，包含摺疊中的子列
  // 與無法回測（`sellDate` 為 null）但 `buyPrice` 有值的筆；`buyPrice` 為 null 的筆一律不
  // 屬於高價組。逐筆讀 `backtestItemsByKey` 的 `buyPrice`（唯一帶著它的地方是回測回應），
  // 不看父列的成本加權均價——「逐筆判斷，不看父列均價」。
  const highPriceGroupKeys =
    priceThresholdAmount === null
      ? []
      : allItemKeys.filter((key) => {
          const buyPrice = backtestItemsByKey.get(key)?.buyPrice
          return buyPrice != null && buyPrice > priceThresholdAmount
        })
  // 高價組為空、或金額本身不合法時 disabled（未勾選）——沒有東西可以作用，或連範圍都算不出來。
  const priceThresholdDisabled = backtestResult === null || priceThresholdInvalid || highPriceGroupKeys.length === 0

  /** 勾選「取消買進價高於 N 元」→ 高價組全部取消勾選；取消勾選 → 高價組全部勾回。其餘筆完
   * 全不動。完全在前端完成，不重新呼叫任何端點；**修改金額本身從不呼叫這個函式**——只有點
   * 擊勾選框才會，金額變動只改變 `highPriceGroupKeys` 這個推導範圍。 */
  const togglePriceThreshold = (turnOn: boolean) => {
    setPriceThresholdChecked(turnOn)
    setCheckedItemKeys((keys) => {
      const next = new Set(keys)
      for (const key of highPriceGroupKeys) {
        if (turnOn) next.delete(key)
        else next.add(key)
      }
      return next
    })
  }

  const incompleteGroupKeys = allItemKeys.filter((key) => backtestItemsByKey.get(key)?.sellDate === null)
  const hiddenItemKeys = new Set([
    ...(priceThresholdChecked ? highPriceGroupKeys : []),
    ...(hideIncomplete ? incompleteGroupKeys : []),
  ])
  const visibleGroups = (row: UnionRow) => row.buyDateGroups.filter((group) => !hiddenItemKeys.has(itemKey(row.stockId, group.buyDate)))

  /** 各策略的 insufficientData／pendingConfirm — 合併表格下方逐策略各一行，行首標明策略
   * 名稱。這些標的不是命中，因此永遠不進入 `unionRows` 或回測請求。 */
  const renderScanNotes = () =>
    (scanResult?.results ?? []).flatMap((result) => {
      const notes: ReactNode[] = []
      if (result.insufficientData.length > 0) {
        notes.push(
          <ExpandableNote
            key={`${result.strategy}-insufficient`}
            label={`${strategyName(result.strategy)}：另有 ${result.insufficientData.length} 檔因區間前的歷史資料不足而未納入判定`}
            ids={result.insufficientData}
          />,
        )
      }
      if (result.strategy === 'BOX_BREAKOUT' && result.pendingConfirm.length > 0) {
        notes.push(
          <ExpandableNote
            key={`${result.strategy}-pending`}
            label={`${strategyName(result.strategy)}：另有 ${result.pendingConfirm.length} 檔已突破，但確認日尚未到`}
            ids={result.pendingConfirm}
          />,
        )
      }
      if (result.strategy === 'RISING_SUPPORT' && result.pendingConfirm.length > 0) {
        notes.push(
          <ExpandableNote
            key={`${result.strategy}-pending`}
            label={`${strategyName(result.strategy)}：另有 ${result.pendingConfirm.length} 檔已上漲，但後兩日的確認尚未完成`}
            ids={result.pendingConfirm}
          />,
        )
      }
      return notes
    })

  /** The six 買進日／買進價／賣出日／賣出價／報酬率／收益 cells for one position — shared by
   * a single-buy-date row (parent acting as its own only child) and every expanded child
   * row, so the null-dash handling and formatting live in exactly one place. */
  const renderPositionCells = (buyDate: string, item: BacktestResultItem | null) => (
    <>
      <td>{buyDate}</td>
      <td className="sl-r">{item?.buyPrice != null ? formatPrice2(item.buyPrice) : <span className="sl-muted">—</span>}</td>
      <td>{item?.sellDate ?? <span className="sl-muted">—</span>}</td>
      <td className="sl-r">{item?.sellPrice != null ? formatPrice2(item.sellPrice) : <span className="sl-muted">—</span>}</td>
      <td className={`sl-r ${signColorClass(item?.returnPercent)}`}>
        {item?.returnPercent == null ? <span className="sl-muted">—</span> : formatPercent2(item.returnPercent)}
      </td>
      <td className={`sl-r ${signColorClass(item?.profit)}`}>
        {item?.profit == null ? <span className="sl-muted">—</span> : formatAmount(item.profit)}
      </td>
    </>
  )

  /** One of the two sortable headers (買進價／報酬率) — hand-picked cursor + icon + text
   * color so the AC's「以 computed style 驗證」can read them straight off the rendered
   * `<th>`. Only ever called once `backtestResult` exists (the caller gates it), so the
   * click handler never needs its own null-check beyond `handleSortClick`'s own guard. */
  const renderSortableHeader = (column: SortColumn, label: string) => {
    const active = sortState?.column === column
    const icon = active ? (sortState!.direction === 'desc' ? '▼' : '▲') : '↕'
    return (
      <th
        className={`sl-r st-sortable${active ? ' st-sort-header-active' : ''}`}
        onClick={() => handleSortClick(column)}
        aria-sort={active ? (sortState!.direction === 'desc' ? 'descending' : 'ascending') : 'none'}
      >
        <span className="st-sort-label">{label}</span>
        <span className={`st-sort-icon ${active ? 'st-sort-icon-active' : 'st-sort-icon-idle'}`}>{icon}</span>
      </th>
    )
  }

  const renderMergedTable = () => (
    <div className="st-result-block st-union-block" ref={unionBlockRef}>
      {backtestTotals && floatingTotalsVisible ? (
        // 總計浮動跟隨 — a separate, position:fixed copy of the SAME `backtestTotals`
        // object (via the shared `renderTotalsTriplet`, never a second computation),
        // right-aligned to this panel's own right edge. Only the three totals float —
        // the title, both batch checkboxes, and the 「未計入」notes never leave their
        // in-flow position (they simply aren't part of this block).
        <div className="st-floating-totals" style={{ right: `${floatingRightOffset}px` }}>
          {renderTotalsTriplet(backtestTotals)}
        </div>
      ) : null}
      <div className="st-union-header">
        <h3 className="st-result-title">命中彙總 — 共 {unionRows.length} 檔</h3>
        {backtestTotals ? (
          <div className="st-backtest-totals">
            <div className="st-totals-anchor" ref={totalsAnchorRef}>
              {renderTotalsTriplet(backtestTotals)}
            </div>
          </div>
        ) : null}
      </div>
      <div className="st-result-details">
        {scanResult && scanResult.results.length > 0 ? (
          <p className="st-params-line">{scanResult.results.map(formatStrategyParams).join('・')}</p>
        ) : null}
        {backtestTotals ? (
          <div className="st-batch-controls">
            <label className="st-total-item st-selectall-item">
              <input
                type="checkbox"
                className="st-row-checkbox"
                checked={cancelAllChecked}
                onChange={(e) => toggleCancelAll(e.target.checked)}
                aria-label="取消全選"
              />
              <span className="st-total-label">取消全選</span>
            </label>
            <div className="st-total-item st-price-threshold-item">
              <div className="st-price-threshold-row">
                <input
                  type="checkbox"
                  className="st-row-checkbox"
                  checked={priceThresholdChecked}
                  disabled={priceThresholdDisabled}
                  onChange={(e) => togglePriceThreshold(e.target.checked)}
                  aria-label="取消買進價高於金額元"
                />
                <span className={`st-total-label${priceThresholdDisabled ? ' st-total-label-disabled' : ''}`}>
                  取消買進價高於
                </span>
                <input
                  type="number"
                  min={0}
                  step="0.01"
                  className="st-price-threshold-input"
                  value={priceThresholdInput}
                  disabled={priceThresholdChecked}
                  onChange={(e) => setPriceThresholdInput(e.target.value)}
                  aria-label="取消買進價高於的金額"
                />
                <span className={`st-total-label${priceThresholdDisabled ? ' st-total-label-disabled' : ''}`}>元</span>
              </div>
              {priceThresholdInvalid ? (
                <p className="st-inline-error st-price-threshold-hint">金額需為 0 以上、最多兩位小數</p>
              ) : null}
            </div>
            <label className="st-selectall-item st-incomplete-item">
              <input
                type="checkbox"
                className="st-row-checkbox"
                checked={hideIncomplete}
                disabled={incompleteGroupKeys.length === 0}
                onChange={(e) => setHideIncomplete(e.target.checked)}
                aria-label="隱藏資料不齊（無賣出日）"
              />
              <span className={`st-total-label${incompleteGroupKeys.length === 0 ? ' st-total-label-disabled' : ''}`}>隱藏資料不齊（無賣出日）</span>
            </label>
          </div>
        ) : null}
      </div>
      {backtestTotals ? (
        <div className="st-uncounted-notes">
          {backtestTotals.includedCount === 0 ? (
            <p className="st-uncounted-note">
              {backtestResult?.totalReturnPercent === null ? '沒有可回測的標的' : '未勾選任何標的'}
            </p>
          ) : null}
          {backtestTotals.uncountedNoSellDate > 0 ? (
            <p className="st-uncounted-note">另 {backtestTotals.uncountedNoSellDate} 筆尚無可賣出交易日，未計入</p>
          ) : null}
          {backtestTotals.uncountedUnchecked > 0 ? (
            <p className="st-uncounted-note">另 {backtestTotals.uncountedUnchecked} 筆未勾選，未計入</p>
          ) : null}
          {hiddenItemKeys.size > 0 ? <p className="st-uncounted-note">另 {hiddenItemKeys.size} 筆已隱藏</p> : null}
        </div>
      ) : null}
      {unionRows.length === 0 ? (
        <div className="st-result-zero">
          <p>此區間內沒有命中的股票</p>
          <p className="st-last-sync-hint">最後同步：{formatSyncTime(lastSyncedAt)}</p>
        </div>
      ) : (
        <table className={`sl-table st-result-table st-union-table${backtestResult ? ' st-union-table-backtested' : ''}`}>

          <thead>
            <tr>
              <th className="st-expand-col" aria-label="展開"></th>
              {backtestResult ? <th className="st-checkbox-col" aria-label="納入計算"></th> : null}
              <th>代號 / 名稱</th>
              <th>命中策略與訊號日</th>
              {backtestResult ? <th>買進日</th> : null}
              {backtestResult ? renderSortableHeader('buyPrice', '買進價') : null}
              {backtestResult ? <th>賣出日</th> : null}
              {backtestResult ? <th className="sl-r">賣出價</th> : null}
              {backtestResult ? renderSortableHeader('returnPercent', '報酬率') : null}
              {backtestResult ? <th className="sl-r">收益（每筆 1 張）</th> : null}
            </tr>
          </thead>
          <tbody>
            {displayRows.flatMap((row) => {
              const groups = visibleGroups(row)
              if (groups.length === 0) return []
              const hasChildren = groups.length >= 2
              const isExpanded = hasChildren && expandedStockIds.has(row.stockId)
              const lotSize = backtestResult?.lotSize ?? 0

              // ---- parent/single-group row's own backtest cells ----
              let backtestCells: ReactNode = null
              let parentChecked = true
              let parentIndeterminate = false
              if (backtestResult) {
                if (hasChildren) {
                  const agg = computeParentAggregate(row.stockId, groups, backtestItemsByKey, checkedItemKeys, lotSize)
                  parentChecked = agg.checkedChildren === agg.totalChildren && agg.totalChildren > 0
                  parentIndeterminate = agg.checkedChildren > 0 && agg.checkedChildren < agg.totalChildren
                  backtestCells = (
                    <>
                      {/* 買進日：該檔每一筆的 buyDate，由新到舊逐行列出，一行一筆；已勾選
                          為主要文字色（繼承 td 預設值），未勾選為弱化色（重用 sl-muted）。 */}
                      <td>
                        {groups.map((group) => {
                          const checked = checkedItemKeys.has(itemKey(row.stockId, group.buyDate))
                          return (
                            <div key={group.buyDate} className={checked ? undefined : 'sl-muted'}>
                              {group.buyDate}
                            </div>
                          )
                        })}
                      </td>
                      <td className="sl-r">
                        {agg.avgBuyPrice != null ? formatPrice2(agg.avgBuyPrice) : <span className="sl-muted">—</span>}
                      </td>
                      {/* 賣出日：與買進日欄同一順序、同一行數，第 k 行屬於同一筆；無法回測的
                          那一筆該行為弱化色「—」，已勾選／未勾選的配色同買進日欄。 */}
                      <td>
                        {groups.map((group) => {
                          const checked = checkedItemKeys.has(itemKey(row.stockId, group.buyDate))
                          const item = backtestItemsByKey.get(itemKey(row.stockId, group.buyDate)) ?? null
                          const sellDate = item?.sellDate ?? null
                          const muted = !checked || sellDate == null
                          return (
                            <div key={group.buyDate} className={muted ? 'sl-muted' : undefined}>
                              {sellDate ?? '—'}
                            </div>
                          )
                        })}
                      </td>
                      {/* 賣出價：恆為弱化色「—」（多筆各有各的賣出價，沒有單一合計值），且
                          需與同欄其他列的賣出價同樣靠右對齊（sl-r，過去這裡漏掉，造成錯位）。 */}
                      <td className="sl-r">
                        <span className="sl-muted">—</span>
                      </td>
                      <td className={`sl-r ${agg.includedCount > 0 ? signColorClass(agg.returnPercent) : 'sl-muted'}`}>
                        {agg.includedCount > 0 && agg.returnPercent != null ? (
                          formatPercent2(agg.returnPercent)
                        ) : (
                          <span className="sl-muted">—</span>
                        )}
                      </td>
                      <td className={`sl-r ${agg.includedCount > 0 ? signColorClass(agg.totalProfit) : 'sl-muted'}`}>
                        {agg.includedCount > 0 ? formatAmount(agg.totalProfit) : <span className="sl-muted">—</span>}
                      </td>
                    </>
                  )
                } else {
                  const group = groups[0]
                  const item = backtestItemsByKey.get(itemKey(row.stockId, group.buyDate)) ?? null
                  parentChecked = checkedItemKeys.has(itemKey(row.stockId, group.buyDate))
                  backtestCells = renderPositionCells(group.buyDate, item)
                }
              }
              // 父列只在其全部子筆都未勾選時才整列反灰，半選狀態下不反灰 — 單訊號日的列則
              // 直接看自己的勾選狀態，行為與過去一致。
              const rowUnchecked = backtestResult ? !parentChecked && !parentIndeterminate : false

              // 點選列＝點選該列勾選框：before a successful 回測 there is no checkbox column
              // at all, so the row body does nothing — no handler is attached. After 回測,
              // clicking anywhere in the row body toggles exactly what clicking its own
              // checkbox would: a plain single-group row flips that one position, and a
              // multi-buy-date parent flips its whole group (checking everything when
              // fully unchecked OR half-checked, unchecking everything only when fully
              // checked — the same `!parentChecked` a native click on the (possibly
              // indeterminate-but-`checked=false`) checkbox itself would report).
              const handleParentRowClick = backtestResult
                ? () => {
                    if (hasChildren) toggleParentChecked(row.stockId, groups, !parentChecked)
                    else toggleItemChecked(row.stockId, groups[0].buyDate)
                  }
                : undefined

              const parentRow = (
                <tr
                  key={row.stockId}
                  className={`sl-row${rowUnchecked ? ' st-row-unchecked' : ''}`}
                  onClick={handleParentRowClick}
                >
                  <td className="st-expand-col">
                    {hasChildren ? (
                      <button
                        type="button"
                        className={`st-expand-btn${isExpanded ? ' st-expand-btn-open' : ''}`}
                        onClick={(e) => {
                          // 展開鈕只展開／收合，不切換勾選 — stop the click from also
                          // reaching the row's own onClick above, which would otherwise
                          // toggle the checkbox on every expand/collapse.
                          e.stopPropagation()
                          toggleExpanded(row.stockId)
                        }}
                        aria-label={`${isExpanded ? '收合' : '展開'} ${row.stockId} 的訊號日明細`}
                        aria-expanded={isExpanded}
                      >
                        {isExpanded ? '▾' : '▸'}
                      </button>
                    ) : null}
                  </td>
                  {backtestResult ? (
                    <td className="st-checkbox-col">
                      {hasChildren ? (
                        <RowCheckbox
                          checked={parentChecked}
                          indeterminate={parentIndeterminate}
                          onChange={(next) => toggleParentChecked(row.stockId, groups, next)}
                          ariaLabel={`納入 ${row.stockId} 全部計算`}
                        />
                      ) : (
                        <RowCheckbox
                          checked={parentChecked}
                          onChange={() => toggleItemChecked(row.stockId, groups[0].buyDate)}
                          ariaLabel={`納入 ${row.stockId} 計算`}
                        />
                      )}
                    </td>
                  ) : null}
                  <td>
                    {row.stockId} {row.stockName}
                  </td>
                  <td className="st-union-hits">{renderUnionHits(groups.flatMap((group) => group.hits))}</td>
                  {backtestCells}
                </tr>
              )

              if (!isExpanded) return [parentRow]

              // 展開中的子列依同一欄、同一方向排序，「—」同樣視為最小值、值相同依買進日由
              // 新到舊；還原預設排序（sortState === null）時就是 `groups` 自己已經有的
              // 由新到舊順序，不必另外排。This is independent of `sortedRowOrder`'s
              // top-level freeze — a child's own buyPrice/returnPercent never changes with
              // a checkbox toggle, so re-deriving this order every render can't move a
              // child row underneath the user either.
              const childOrderGroups = sortState
                ? [...groups].sort((a, b) => {
                    const aItem = backtestItemsByKey.get(itemKey(row.stockId, a.buyDate)) ?? null
                    const bItem = backtestItemsByKey.get(itemKey(row.stockId, b.buyDate)) ?? null
                    const aValue = sortState.column === 'buyPrice' ? (aItem?.buyPrice ?? null) : (aItem?.returnPercent ?? null)
                    const bValue = sortState.column === 'buyPrice' ? (bItem?.buyPrice ?? null) : (bItem?.returnPercent ?? null)
                    const cmp = compareBySortDirection(aValue, bValue, sortState.direction)
                    return cmp !== 0 ? cmp : a.buyDate < b.buyDate ? 1 : a.buyDate > b.buyDate ? -1 : 0
                  })
                : groups

              const childRows = childOrderGroups.map((group) => {
                const key = itemKey(row.stockId, group.buyDate)
                const childChecked = checkedItemKeys.has(key)
                const item = backtestItemsByKey.get(key) ?? null
                return (
                  <tr
                    key={key}
                    className={`sl-row st-child-row${childChecked ? '' : ' st-row-unchecked'}`}
                    onClick={backtestResult ? () => toggleItemChecked(row.stockId, group.buyDate) : undefined}
                  >
                    <td className="st-expand-col"></td>
                    {backtestResult ? (
                      <td className="st-checkbox-col">
                        <RowCheckbox
                          checked={childChecked}
                          onChange={() => toggleItemChecked(row.stockId, group.buyDate)}
                          ariaLabel={`納入 ${row.stockId} ${group.buyDate} 計算`}
                        />
                      </td>
                    ) : null}
                    <td className="st-child-indent">
                      {row.stockId} {row.stockName}
                    </td>
                    <td className="st-union-hits">{renderUnionHits(group.hits)}</td>
                    {backtestResult ? renderPositionCells(group.buyDate, item) : null}
                  </tr>
                )
              })

              return [parentRow, ...childRows]
            })}
          </tbody>
        </table>
      )}
      {/* 總計浮動跟隨's "table scrolled fully past the top" sentinel — a zero-height marker
          right after the hit table's last row, read only via getBoundingClientRect() in the
          scroll/resize effect above; never rendered with any visible content of its own. */}
      <div ref={tableEndRef} className="st-table-end-sentinel" aria-hidden="true" />
      {renderScanNotes()}
    </div>
  )

  return (
    <div className="strategy-tab">
      {/* ---------- sync row ---------- */}
      <div className="st-sync-row">
        <div className="st-sync-info">
          <div className="st-last-sync">最後同步：{formatSyncTime(lastSyncedAt)}</div>
          <div className="st-stock-total">股票清單：共 {totalStockCount ?? '—'} 檔</div>
        </div>
        <div className="st-sync-actions">
          {/* Both are secondary buttons — 開始掃描 is the page's only primary button.
              Neither disables the other: no shared backend concurrency lock (see
              specs/backend/stock-universe-import.md 的「併發」), so the frontend must not
              invent a mutual-exclusion it doesn't need. */}
          <button
            type="button"
            className="sl-btn"
            disabled={universeImportStatus === 'running'}
            onClick={handleImportUniverseClick}
          >
            {universeImportStatus === 'running' ? '更新中…' : '更新股票清單'}
          </button>
          {syncErrorMessage ? <span className="st-inline-error">{syncErrorMessage}</span> : null}
          <button
            type="button"
            className="sl-btn"
            disabled={syncStatus === 'running'}
            onClick={handleSyncClick}
          >
            {syncStatus === 'running' ? '同步中…' : '同步日 K 至今日'}
          </button>
        </div>
      </div>
      {universeImportErrorMessage ? (
        <div className="st-universe-summary">
          <span className="st-inline-error">{universeImportErrorMessage}</span>
        </div>
      ) : universeImportSummary ? (
        <div className="st-universe-summary">
          <span className="st-universe-summary-text">
            股票清單已更新：共 {universeImportSummary.totalActiveCount} 檔（新增 {universeImportSummary.insertedCount}
            、更新 {universeImportSummary.updatedCount}）・產業別 {universeImportSummary.industryCount} 類，未分類{' '}
            {universeImportSummary.uncategorizedStockCount} 檔
          </span>
          {universeImportSummary.industrySourceStatus !== 'OK' ? (
            <span className="st-industry-warning">產業別未更新（來源暫時無法取得），股票清單已更新</span>
          ) : null}
        </div>
      ) : null}
      {syncStatus === 'running' && syncProgress ? (
        <div className="st-sync-progress">
          <div className="st-progress-bar">
            <div className="st-progress-fill" style={{ width: `${progressPercent(syncProgress)}%` }} />
          </div>
          <span className="st-progress-text">
            已完成 {completedCount(syncProgress)} / {syncProgress.total} 檔
          </span>
        </div>
      ) : null}
      {syncStatus === 'idle' && syncSummary ? (
        <div className="st-sync-summary">
          {syncMeta && syncMeta.targetCount > 0 && syncMeta.caughtUpCount === syncMeta.targetCount ? (
            <span className="st-caught-up-note">
              已是最新，無需更新（{syncMeta.targetCount} 檔{syncMeta.commonStocksOnly ? '上市普通股' : ''}）
            </span>
          ) : (
            <span>
              完成 {syncSummary.done} 檔{syncMeta?.commonStocksOnly ? '上市普通股' : ''}／失敗 {syncSummary.failed} 檔／略過{' '}
              {syncSummary.skipped} 檔
              {syncMeta && syncMeta.caughtUpCount > 0 ? (
                <span className="st-caught-up-note">，另 {syncMeta.caughtUpCount} 檔已是最新</span>
              ) : null}
            </span>
          )}
          {syncSummary.failed > 0 ? (
            <button type="button" className="st-note-toggle" onClick={() => setShowFailedList((v) => !v)}>
              查看失敗清單<span className="st-note-caret">{showFailedList ? ' ▲' : ' ▼'}</span>
            </button>
          ) : null}
          {showFailedList && syncSummary.failedItems.length > 0 ? (
            <ul className="st-failed-list">
              {syncSummary.failedItems.map((f) => (
                <li key={f.stockId}>
                  {f.stockId}：{f.lastError}
                </li>
              ))}
            </ul>
          ) : null}
        </div>
      ) : null}

      {/* ---------- condition area ---------- */}
      <div className="st-condition">
        <div className="st-section-label">策略</div>
        {catalogStatus === 'loading' ? (
          <div className="st-strategy-cards-loading">載入策略清單中…</div>
        ) : catalogStatus === 'error' ? (
          <div className="st-inline-error">策略清單載入失敗，請重新整理頁面</div>
        ) : (
          <div className="st-strategy-cards">
            {catalog.map((strategy) => {
              const selected = selectionOrder.includes(strategy.code)
              const paramsDriven = isParamsDriven(strategy)

              if (paramsDriven) {
                const values = paramInputs[strategy.code] ?? {}
                const groups = strategy.paramGroups ?? []
                const params = strategy.params ?? []
                const ungrouped = params.filter((p) => !p.group)
                // 「窗口只有當天」hint applies to the one ungrouped day-count param, paired
                // with the one ungrouped amount param — e.g. 累積上漲's 天數/漲幅門檻,
                // 反彈's 下跌天數/跌幅門檻. Purely derived from this strategy's own
                // `params`, never from `code`.
                const dayParam = ungrouped.find((p) => p.unit === '日')
                const amountParam = ungrouped.find((p) => p !== dayParam)
                const dayValue = dayParam ? (values[dayParam.code] ?? '') : ''
                const amountValue = amountParam ? (values[amountParam.code] ?? '') : ''
                const dayInvalid = !!dayParam && selected && isParamInputInvalid(dayValue, dayParam)
                const amountInvalid = !!amountParam && selected && isParamInputInvalid(amountValue, amountParam)
                const oneDayHintText = dayParam && ONE_DAY_WINDOW_HINT[dayParam.code]
                const showOneDayHint =
                  selected &&
                  !!dayParam &&
                  !!amountParam &&
                  !dayInvalid &&
                  !amountInvalid &&
                  Number(dayValue) === 1 &&
                  Number(amountValue) > 0 &&
                  !!oneDayHintText

                const renderParamRow = (param: StrategyParam) => {
                  const groupOn = isGroupOn(strategy, groupEnabled, param)
                  const disabled = !selected || !groupOn
                  const value = values[param.code] ?? ''
                  const invalid = selected && groupOn && isParamInputInvalid(value, param)
                  return (
                    <div key={param.code} className="st-param-block">
                      <div className="st-param-input-row">
                        <label htmlFor={`st-param-${strategy.code}-${param.code}`} className="st-param-label">
                          {param.name}
                        </label>
                        <input
                          id={`st-param-${strategy.code}-${param.code}`}
                          type="number"
                          className="st-param-input"
                          disabled={disabled}
                          min={param.min}
                          max={param.max}
                          step={param.step}
                          value={value}
                          onChange={(e) => changeParamInput(strategy.code, param.code, e.target.value)}
                        />
                        <span className="st-param-suffix">{param.unit}</span>
                      </div>
                      {param.unit === '日' ? <p className="st-param-hint">回看的交易日數，不含週末與休市日</p> : null}
                      {invalid ? <div className="st-inline-error">{paramErrorMessage(param)}</div> : null}
                    </div>
                  )
                }

                return (
                  <div
                    key={strategy.code}
                    className={`st-strategy-card${selected ? ' st-strategy-card-selected' : ''}`}
                  >
                    <label className="st-strategy-card-head">
                      <input type="checkbox" checked={selected} onChange={() => toggleStrategy(strategy.code)} />
                      <span className="st-strategy-name">{strategy.name}</span>
                    </label>
                    {ungrouped.map(renderParamRow)}
                    {groups.map((group) => {
                      const groupOn = groupEnabled[strategy.code]?.[group.code] ?? group.default
                      return (
                        <div key={group.code} className="st-param-group">
                          <label className="st-group-checkbox">
                            <input
                              type="checkbox"
                              disabled={!selected}
                              checked={groupOn}
                              onChange={() => toggleGroup(strategy, group.code)}
                            />
                            <span className="st-group-label">{group.name}</span>
                          </label>
                          {params.filter((p) => p.group === group.code).map(renderParamRow)}
                        </div>
                      )
                    })}
                    {showOneDayHint ? <div className="st-days-one-hint">{oneDayHintText}</div> : null}
                    {paramServerError?.code === strategy.code ? (
                      <div className="st-inline-error">{paramServerError.message}</div>
                    ) : null}
                    <p className="st-strategy-desc">{strategy.description}</p>
                  </div>
                )
              }

              const preset = selectedPresets[strategy.code] ?? 'STANDARD'
              const presetMeta = strategy.presets.find((p) => p.code === preset)
              const riseValue = risePercentInputs[strategy.code] ?? ''
              const riseInvalid = selected && isRisePercentInputInvalid(riseValue, SENSITIVITY_RISE_PERCENT_RANGE)
              return (
                <div
                  key={strategy.code}
                  className={`st-strategy-card${selected ? ' st-strategy-card-selected' : ''}`}
                >
                  <label className="st-strategy-card-head">
                    <input type="checkbox" checked={selected} onChange={() => toggleStrategy(strategy.code)} />
                    <span className="st-strategy-name">{strategy.name}</span>
                  </label>
                  <select
                    className="sl-select"
                    disabled={!selected}
                    value={preset}
                    onChange={(e) => changePreset(strategy.code, e.target.value as PresetCode)}
                  >
                    {strategy.presets.map((p) => (
                      <option key={p.code} value={p.code}>
                        {p.name}
                      </option>
                    ))}
                  </select>
                  <div className="st-rise-input-row">
                    <label htmlFor={`st-rise-${strategy.code}`} className="st-rise-label">
                      漲幅門檻
                    </label>
                    <input
                      id={`st-rise-${strategy.code}`}
                      type="number"
                      className="st-rise-input"
                      disabled={!selected}
                      min={SENSITIVITY_RISE_PERCENT_RANGE.min}
                      max={SENSITIVITY_RISE_PERCENT_RANGE.max}
                      step="0.1"
                      value={riseValue}
                      onChange={(e) => changeRisePercentInput(strategy.code, e.target.value)}
                    />
                    <span className="st-rise-suffix">%</span>
                  </div>
                  {riseInvalid || invalidRisePercentStrategy === strategy.code ? (
                    <div className="st-inline-error">
                      漲幅門檻需介於 {SENSITIVITY_RISE_PERCENT_RANGE.min} ~ {SENSITIVITY_RISE_PERCENT_RANGE.max}
                    </div>
                  ) : null}
                  {paramServerError?.code === strategy.code ? (
                    <div className="st-inline-error">{paramServerError.message}</div>
                  ) : null}
                  <p className="st-strategy-desc">{presetMeta?.description}</p>
                </div>
              )
            })}
          </div>
        )}

        <div className="st-section-label">股票範圍</div>
        <div className="st-scope">
          <label className="st-radio">
            <input type="radio" name="st-scope" checked={scope === 'ALL'} onChange={() => setScope('ALL')} />
            全市場
          </label>
          <label className="st-radio">
            <input
              type="radio"
              name="st-scope"
              checked={scope === 'SELECTED'}
              onChange={() => setScope('SELECTED')}
            />
            指定股票
          </label>
        </div>
        {scope === 'SELECTED' ? (
          <div className="st-stock-picker">
            {selectedStocks.length > 0 ? (
              <div className="st-stock-tags">
                {selectedStocks.map((s) => (
                  <span key={s.stockId} className="st-stock-tag">
                    {s.stockId} {s.stockName}
                    <button type="button" onClick={() => removeStock(s.stockId)} aria-label={`移除 ${s.stockId}`}>
                      ×
                    </button>
                  </span>
                ))}
              </div>
            ) : null}
            <input
              type="text"
              className="st-stock-search"
              placeholder="輸入代號或名稱搜尋加入"
              value={stockSearch}
              disabled={selectedStocks.length >= STOCK_ID_CAP}
              onChange={(e) => setStockSearch(e.target.value)}
            />
            {selectedStocks.length >= STOCK_ID_CAP ? <div className="st-inline-hint">最多 200 檔</div> : null}
            {stockSuggestions.length > 0 && stockSearch ? (
              <div className="st-suggestions">
                {stockSuggestions.map((s) => (
                  <div key={s.stockId} className="st-suggestion-item" onClick={() => addStock(s)}>
                    {s.stockId} {s.stockName}
                  </div>
                ))}
              </div>
            ) : null}
            {unknownIds && unknownIds.length > 0 ? (
              <div className="st-inline-error">以下代號不存在：{unknownIds.join('、')}，請移除後重新掃描</div>
            ) : null}
          </div>
        ) : null}

        <div className="st-section-label">區間</div>
        <div className="st-date-range">
          <WeekSelector
            ariaLabel="起始週"
            monday={parseIsoDate(weekRange.startMonday)}
            onPrev={() => shiftWeek('start', -1)}
            onNext={() => shiftWeek('start', 1)}
          />
          <span className="st-date-sep">至</span>
          <WeekSelector
            ariaLabel="結束週"
            monday={parseIsoDate(weekRange.endMonday)}
            onPrev={() => shiftWeek('end', -1)}
            onNext={() => shiftWeek('end', 1)}
            nextDisabled={weekRange.endMonday >= currentWeekMonday}
          />
          <button
            type="button"
            className={`st-shortcut${activeShortcut === '1m' ? ' st-shortcut-active' : ''}`}
            onClick={() => applyShortcut('1m')}
          >
            近一個月
          </button>
          <button
            type="button"
            className={`st-shortcut${activeShortcut === '3m' ? ' st-shortcut-active' : ''}`}
            onClick={() => applyShortcut('3m')}
          >
            近三個月
          </button>
          <button
            type="button"
            className={`st-shortcut${activeShortcut === '6m' ? ' st-shortcut-active' : ''}`}
            onClick={() => applyShortcut('6m')}
          >
            近半年
          </button>
        </div>
        <div className="st-range-hint">
          實際區間 {scanStartDate} ~ {scanEndDate}
        </div>
        {dateInvalid || dateRangeServerError ? <div className="st-inline-error">起始週不可晚於結束週</div> : null}

        <div className="st-scan-row">
          <button type="button" className="sl-btn sl-btn-primary" disabled={!canScan} onClick={handleScanClick}>
            {scanStatus === 'scanning' ? '掃描中…' : '開始掃描'}
          </button>
          {/* 本頁沒有「回測」按鈕：掃描成功且命中至少一檔時自動送出回測，這裡只呈現其狀態。
              自動回測進行中（非重試）— 沒有訊息、沒有按鈕，只有這行次要文字色的提示，
              「開始掃描」本身仍可按（未 disabled）。 */}
          {backtestStatus === 'running' && !isRetryingBacktest ? <span className="st-backtest-hint">回測中…</span> : null}
          {/* 自動回測失敗，或「重試回測」進行中 — 錯誤訊息保留至重試有結果為止，其右緊接
              「重試回測」按鈕；重試進行中按鈕 disabled 並顯示「回測中…」。 */}
          {backtestStatus === 'error' || (backtestStatus === 'running' && isRetryingBacktest) ? (
            <>
              <span className="st-inline-error">{backtestErrorMessage}</span>
              <button type="button" className="sl-btn" disabled={backtestStatus === 'running'} onClick={handleRetryBacktest}>
                {backtestStatus === 'running' ? '回測中…' : '重試回測'}
              </button>
            </>
          ) : null}
          {noStrategySelected ? <span className="st-inline-hint">請至少勾選一個策略</span> : null}
          {!noStrategySelected && noStockSelected ? (
            <span className="st-inline-hint">請至少選擇一檔股票</span>
          ) : null}
        </div>
      </div>

      {/* ---------- results area ---------- */}
      <div className="st-results">
        {scanStatus === 'error' ? (
          <div className="st-results-error">
            <p>{scanErrorMessage}</p>
            <button type="button" className="sl-btn sl-btn-primary" onClick={handleRetry}>
              重試
            </button>
          </div>
        ) : !scanResult && scanStatus !== 'scanning' ? (
          <div className="st-results-placeholder">
            <p>選擇策略與區間後開始掃描</p>
          </div>
        ) : !scanResult ? (
          <div className="st-results-placeholder">
            <p>掃描中…</p>
          </div>
        ) : (
          <div className={scanStatus === 'scanning' ? 'st-results-list st-results-dimmed' : 'st-results-list'}>
            {renderMergedTable()}
          </div>
        )}
      </div>
    </div>
  )
}
