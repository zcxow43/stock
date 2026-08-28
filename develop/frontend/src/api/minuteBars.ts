// Wire contract: specs/backend/stock-minute-price.md, implemented by
// develop/backend/src/main/java/com/stock/controller/MinuteBarController.java

import { ApiError, type ApiErrorBody } from './stocks'

export type DataStatus = 'AVAILABLE' | 'OUT_OF_WINDOW' | 'NO_DATA' | 'NOT_A_TRADING_DAY' | 'FETCH_FAILED'

export type MinuteInterval = 1 | 5 | 15 | 30 | 60

export interface MinuteBar {
  barTime: string
  open: number
  high: number
  low: number
  close: number
  volume: number
}

export interface DailySummary {
  open: number
  high: number
  low: number
  close: number
  volume: number
}

export interface MinuteBarResponse {
  stockId: string
  stockName: string
  tradeDate: string
  interval: number
  dataStatus: DataStatus
  source: string | null
  fetchedAt: string | null
  barCount: number
  dailySummary: DailySummary | null
  bars: MinuteBar[]
  /** Only present when dataStatus = FETCH_FAILED. */
  message: string | null
}

export interface MinuteBarQuery {
  stockId: string
  tradeDate: string
  interval: MinuteInterval
  refresh?: boolean
}

export async function fetchMinuteBars(
  query: MinuteBarQuery,
  signal?: AbortSignal,
): Promise<MinuteBarResponse> {
  const params = new URLSearchParams()
  params.set('tradeDate', query.tradeDate)
  params.set('interval', String(query.interval))
  if (query.refresh) {
    params.set('refresh', 'true')
  }

  let response: Response
  try {
    response = await fetch(`/api/stocks/${encodeURIComponent(query.stockId)}/minute-bars?${params.toString()}`, {
      signal,
    })
  } catch (err) {
    if (err instanceof DOMException && err.name === 'AbortError') {
      throw err
    }
    throw new ApiError(null, '網路連線失敗')
  }

  if (!response.ok) {
    let body: ApiErrorBody | null = null
    try {
      body = await response.json()
    } catch {
      body = null
    }
    throw new ApiError(body?.code ?? null, `請求失敗（${response.status}）`)
  }

  return (await response.json()) as MinuteBarResponse
}
