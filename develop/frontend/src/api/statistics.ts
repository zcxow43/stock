// Wire contract: specs/backend/stock-indicator-statistics.md, implemented by
// develop/backend/src/main/java/com/stock/controller/StockStatisticsController.java

import { ApiError, type ApiErrorBody } from './stocks'

export interface StatisticsSeriesRow {
  tradeDate: string
  open: number
  high: number
  low: number
  close: number
  volume: number
  dif: number | null
  dea: number | null
  osc: number | null
  k: number | null
  d: number | null
  j: number | null
}

export interface StatisticsSummary {
  firstOpen: number
  lastClose: number
  highest: number
  highestDate: string
  lowest: number
  lowestDate: string
  changeAmount: number
  changePercent: number
  totalVolume: number
  avgVolume: number
  latestDif: number | null
  latestDea: number | null
  latestOsc: number | null
  latestK: number | null
  latestD: number | null
  latestJ: number | null
  macdGoldenCross: number | null
  macdDeathCross: number | null
  kdGoldenCross: number | null
  kdDeathCross: number | null
}

export interface StatisticsItem {
  stockId: string
  stockName: string
  tradingDays: number
  warmupSufficient: boolean
  summary: StatisticsSummary | null
  series?: StatisticsSeriesRow[]
}

export interface StatisticsResponse {
  startDate: string
  endDate: string
  paramKey: string
  scope: 'SELECTED' | 'ALL'
  stockCount: number
  items: StatisticsItem[]
}

export interface StatisticsQuery {
  stockId: string
  startDate?: string
  endDate?: string
}

export async function fetchStockStatistics(
  query: StatisticsQuery,
  signal?: AbortSignal,
): Promise<StatisticsResponse> {
  const params = new URLSearchParams()
  params.set('stockIds', query.stockId)
  params.set('includeSeries', 'true')
  if (query.startDate) params.set('startDate', query.startDate)
  if (query.endDate) params.set('endDate', query.endDate)

  let response: Response
  try {
    response = await fetch(`/api/stocks/statistics?${params.toString()}`, { signal })
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

  return (await response.json()) as StatisticsResponse
}
