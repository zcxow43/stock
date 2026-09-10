// Wire contract: specs/backend/strategy-scan.md, implemented by
// develop/backend/src/main/java/com/stock/controller/StrategyController.java

import { ApiError, type ApiErrorBody } from './stocks'

export type StrategyCode = 'BOX_BREAKOUT' | 'HIGHER_LOWS' | 'RISING_SUPPORT' | 'REBOUND' | 'CUMULATIVE_RISE'
export type PresetCode = 'STRICT' | 'STANDARD' | 'LOOSE'

export interface PresetOption {
  code: PresetCode
  name: string
  description: string
}

/** A single input a no-sensitivity strategy exposes (`CUMULATIVE_RISE`'s `days`/
 * `risePercent`, `REBOUND`'s `dropDays`/`dropPercent`/`riseDays`/`risePercent`).
 * Default/min/max/step all come from here — never hard-coded in the frontend, per
 * specs/backend/strategy-scan.md「presets 與 params 的關係」. `code` is deliberately a
 * plain `string`, not a fixed union — the set of param names is the backend's to grow,
 * and the frontend must render whatever it's given without a matching code change. */
export interface StrategyParam {
  code: string
  name: string
  unit: string
  default: number
  min: number
  max: number
  step: number
  /** When present, names an entry in the strategy's own `paramGroups` — this input is
   * part of that optional, all-or-nothing group (see `ParamGroup`). Absent means the
   * input always applies and can never be disabled. */
  group?: string
}

/** One optional, all-or-nothing group of `params` entries sharing the same `group` code
 * (currently only `REBOUND`'s `rise` group). Rendered as a single checkbox — unchecking
 * it disables every param in the group and omits all of them from the scan request.
 * Purely data-driven: which strategy has which groups, and which params belong to them,
 * comes entirely from `GET /api/strategies`, never from a `code`/name branch. */
export interface ParamGroup {
  code: string
  name: string
  default: boolean
}

export interface StrategyCatalogItem {
  code: StrategyCode
  name: string
  /** Strategy-level description, only present (and only meaningful) when `presets` is
   * empty — the card shown for a no-sensitivity strategy uses this instead of a preset's
   * own `description`. */
  description?: string
  /** `presets` empty is what decides a card has no sensitivity dropdown and draws its
   * `params` as numeric inputs instead — never branch on `code` for this (see the same
   * spec section). */
  presets: PresetOption[]
  params?: StrategyParam[]
  paramGroups?: ParamGroup[]
}

export interface StrategyCatalogResponse {
  strategies: StrategyCatalogItem[]
}

export interface BoxBreakoutDetail {
  boxHigh: number
  boxLow: number
  breakoutClose: number
  breakoutPercent: number
  volumeRatio: number
}

export interface LowPoint {
  tradeDate: string
  /** 5-day close moving average as of this swing low — what 底底高's rise-vs-previous
   * comparison is actually computed on (「以 5 日均線為基準」). */
  ma5: number
  /** The day's raw lowest price, kept only for side-by-side comparison — never used to
   * compute the累計漲幅 column or to decide a hit. */
  low: number
}

export interface HigherLowsDetail {
  lows: LowPoint[]
}

export interface ConfirmClosePoint {
  tradeDate: string
  close: number
}

export interface RisingSupportDetail {
  supportClose: number
  riseClose: number
  risePercent: number
  priorHighClose: number
  confirmCloses: ConfirmClosePoint[]
}

export interface ReboundDetail {
  peakDate: string
  peakClose: number
  troughDate: string
  troughClose: number
  dropPercent: number
  /** Absent when the scan ran with `requireRise: false` (漲段條件已取消) — the card's
   * "反彈幅度" column must render the weak-text `—` in that case, not `0.00%`. */
  risePercent?: number
}

export interface CumulativeRiseDetail {
  troughDate: string
  troughClose: number
  peakClose: number
  risePercent: number
}

export type StrategyDetail =
  | BoxBreakoutDetail
  | HigherLowsDetail
  | RisingSupportDetail
  | ReboundDetail
  | CumulativeRiseDetail

export interface StrategyHit {
  stockId: string
  stockName: string
  signalDate: string
  detail: StrategyDetail
}

export interface StrategyResult {
  strategy: StrategyCode
  /** `preset`/`days`/(`dropDays` etc.) are mutually exclusive — a strategy with a
   * sensitivity dropdown reports `preset`; `CUMULATIVE_RISE` reports `days`; `REBOUND`
   * reports `requireRise`/`dropDays`/`dropPercent` (and `riseDays`/`risePercent` when
   * `requireRise` is `true`) instead of either. */
  preset?: PresetCode
  days?: number
  requireRise?: boolean
  dropDays?: number
  dropPercent?: number
  riseDays?: number
  risePercent?: number
  matchedCount: number
  items: StrategyHit[]
  insufficientData: string[]
  pendingConfirm: string[]
}

export interface ScanResponse {
  startDate: string
  endDate: string
  scannedStocks: number
  results: StrategyResult[]
}

/** One entry of `ScanRequest.strategies[]`. `preset` is mutually exclusive with the
 * params-driven fields (`days` for `CUMULATIVE_RISE`; `dropDays`/`dropPercent`/
 * `requireRise`/`riseDays`/`risePercent` for `REBOUND`) — a sensitivity-driven strategy
 * sends `preset` (+ optional `risePercent` override) and never the others. */
export interface ScanStrategySelection {
  code: StrategyCode
  preset?: PresetCode
  days?: number
  risePercent?: number
  dropDays?: number
  dropPercent?: number
  requireRise?: boolean
  riseDays?: number
}

export interface ScanRequest {
  strategies: ScanStrategySelection[]
  stockIds?: string[]
  startDate?: string
  endDate?: string
  /** Only sent for a "全市場" scan (`stockIds` omitted) — the page-level 只看上市普通股
   * setting from specs/frontend/stock-list.md. "指定股票" never sends this field. */
  commonStocksOnly?: boolean
}

async function parseErrorBody(response: Response): Promise<ApiErrorBody | null> {
  try {
    return (await response.json()) as ApiErrorBody
  } catch {
    return null
  }
}

/** One entry of `BacktestRequest.items[]` — one stock, one signal date (specs/frontend/
 * strategy.md: the LATEST signalDate among the strategies that stock hit). */
export interface BacktestRequestItem {
  stockId: string
  signalDate: string
}

export interface BacktestRequest {
  items: BacktestRequestItem[]
}

export interface BacktestResultItem {
  stockId: string
  signalDate: string
  /** `null` when the signal date itself has no price row (see
   * specs/backend/strategy-backtest.md「無法回測的標的」). */
  buyPrice: number | null
  /** `null` when the stock has no sellable trading day after its signal date — it stays
   * in `items` and must still render, just with dashes, per the same spec section. */
  sellDate: string | null
  sellPrice: number | null
  returnPercent: number | null
  profit: number | null
}

export interface BacktestResponse {
  asOfDate: string
  /** Shares per lot, currently always 1000 — the frontend must read this rather than
   * hard-code the constant (specs/frontend/strategy.md「總成本以回應的 lotSize 計算」). */
  lotSize: number
  totalCost: number
  totalProfit: number
  /** `null`, not `0`, when no item in the batch was backtestable. */
  totalReturnPercent: number | null
  backtestedCount: number
  items: BacktestResultItem[]
}

/** POST /api/strategies/backtest — sends every hit stock regardless of the UI's checkbox
 * state (checkboxes are a display/aggregation filter applied to this response afterwards,
 * never a request filter). */
export async function backtestStrategies(payload: BacktestRequest, signal?: AbortSignal): Promise<BacktestResponse> {
  let response: Response
  try {
    response = await fetch('/api/strategies/backtest', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
      signal,
    })
  } catch (err) {
    if (err instanceof DOMException && err.name === 'AbortError') throw err
    throw new ApiError(null, '網路連線失敗')
  }

  if (!response.ok) {
    const body = await parseErrorBody(response)
    throw new ApiError(body?.code ?? null, `請求失敗（${response.status}）`)
  }

  return (await response.json()) as BacktestResponse
}

/** GET /api/strategies — catalogue of strategies/presets, including description text.
 * Never hard-code strategy names or preset descriptions in the frontend; this is the sole source. */
export async function fetchStrategyCatalog(signal?: AbortSignal): Promise<StrategyCatalogResponse> {
  let response: Response
  try {
    response = await fetch('/api/strategies', { signal })
  } catch (err) {
    if (err instanceof DOMException && err.name === 'AbortError') throw err
    throw new ApiError(null, '網路連線失敗')
  }

  if (!response.ok) {
    const body = await parseErrorBody(response)
    throw new ApiError(body?.code ?? null, `請求失敗（${response.status}）`)
  }

  return (await response.json()) as StrategyCatalogResponse
}

/** POST /api/strategies/scan */
export async function scanStrategies(payload: ScanRequest, signal?: AbortSignal): Promise<ScanResponse> {
  let response: Response
  try {
    response = await fetch('/api/strategies/scan', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
      signal,
    })
  } catch (err) {
    if (err instanceof DOMException && err.name === 'AbortError') throw err
    throw new ApiError(null, '網路連線失敗')
  }

  if (!response.ok) {
    const body = await parseErrorBody(response)
    throw new ApiError(
      body?.code ?? null,
      `請求失敗（${response.status}）`,
      null,
      body?.unknownIds ?? null,
      body?.strategy ?? null,
      body?.param ?? null,
    )
  }

  return (await response.json()) as ScanResponse
}
