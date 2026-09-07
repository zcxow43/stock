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

export interface StrategyCatalogItem {
  code: StrategyCode
  name: string
  presets: PresetOption[]
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
  troughClose: number
  dropPercent: number
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
  preset: PresetCode
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

export interface ScanRequest {
  strategies: { code: StrategyCode; preset: PresetCode; risePercent?: number }[]
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
    )
  }

  return (await response.json()) as ScanResponse
}
