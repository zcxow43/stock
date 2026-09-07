// Wire contract: specs/backend/industry-gain-ranking.md, implemented by
// develop/backend/src/main/java/com/stock/controller/MomentumController.java

import { ApiError, type ApiErrorBody } from './stocks'

export type Metric = 'AVERAGE' | 'SUM'
export type PeriodMode = 'DAYS' | 'WEEKS'
export type MomentumSort = 'MATCH_COUNT' | 'AVG_GAIN'

export interface MomentumGainItem {
  stockId: string
  stockName: string
  gain: number
  tradingDays: number
  startClose: number
  endClose: number
  /** Not shown in the table — kept for the 交易日 column's停牌 tooltip. */
  firstTradeDate: string
  lastTradeDate: string
}

export interface MomentumIndustryGroup {
  industryId: number | null
  industryName: string
  matchedCount: number
  /** Mean of this block's `items[].gain` — population is matched stocks only, so this is
   * always >= the query's `minGain`. Never render it as "the industry's overall gain". */
  avgGain: number
  items: MomentumGainItem[]
}

export interface MomentumGainResponse {
  metric: Metric
  mode: PeriodMode
  sort: MomentumSort
  startDate: string | null
  endDate: string | null
  tradingDays: number
  minGain: number
  scannedStocks: number
  matchedStockCount: number
  insufficientDataCount: number
  industries: MomentumIndustryGroup[]
}

export interface MomentumGainParams {
  metric: Metric
  mode: PeriodMode
  /** Required (and only sent) when `mode === 'DAYS'`. */
  days?: number
  /** Required (and only sent) when `mode === 'WEEKS'`. */
  startDate?: string
  endDate?: string
  minGain: number
  /** Shared by both metric tabs — always sent, not per-metric. */
  sort: MomentumSort
  /** Page-level 只看上市普通股 setting, owned by `StockListPage`, always sent
   * (specs/frontend/stock-list.md「頁面層級設定」). */
  commonStocksOnly: boolean
}

/** GET /api/momentum/gain */
export async function fetchMomentumGain(
  params: MomentumGainParams,
  signal?: AbortSignal,
): Promise<MomentumGainResponse> {
  const query = new URLSearchParams()
  query.set('metric', params.metric)
  query.set('mode', params.mode)
  if (params.mode === 'DAYS' && params.days != null) {
    query.set('days', String(params.days))
  }
  if (params.mode === 'WEEKS' && params.startDate && params.endDate) {
    query.set('startDate', params.startDate)
    query.set('endDate', params.endDate)
  }
  query.set('minGain', String(params.minGain))
  query.set('sort', params.sort)
  query.set('commonStocksOnly', String(params.commonStocksOnly))

  let response: Response
  try {
    response = await fetch(`/api/momentum/gain?${query.toString()}`, { signal })
  } catch (err) {
    if (err instanceof DOMException && err.name === 'AbortError') throw err
    throw new ApiError(null, '網路連線失敗')
  }

  if (!response.ok) {
    let body: ApiErrorBody | null = null
    try {
      body = (await response.json()) as ApiErrorBody
    } catch {
      body = null
    }
    throw new ApiError(body?.code ?? null, `請求失敗（${response.status}）`)
  }

  return (await response.json()) as MomentumGainResponse
}
