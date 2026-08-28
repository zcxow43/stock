// Wire contract: specs/backend/stock-catalog.md, implemented by
// develop/backend/src/main/java/com/stock/controller/StockController.java

export type Market = 'TSE' | 'OTC'

export interface StockListItem {
  stockId: string
  stockName: string
  market: Market
  isActive: boolean
  latestTradeDate: string | null
  latestClose: number | null
  previousClose: number | null
  changeAmount: number | null
  changePercent: number | null
  latestVolume: number | null
}

export interface StockListResponse {
  page: number
  size: number
  total: number
  totalPages: number
  items: StockListItem[]
}

/** GET /api/stocks/{stockId} — list item fields plus first-trade-date / trading-day-count. */
export interface StockDetail extends StockListItem {
  firstTradeDate: string | null
  tradingDayCount: number
}

export interface ApiErrorBody {
  code: string
  unknownIds?: string[]
  limit?: number
  allowed?: unknown[]
  stockId?: string
}

/** Errors GlobalExceptionHandler treats as caller bugs — normal, controlled-input usage never triggers these. */
export const PROGRAMMER_ERROR_CODES = new Set([
  'INVALID_MARKET',
  'INVALID_SORT_FIELD',
  'INVALID_PAGINATION',
  'PAGE_SIZE_EXCEEDED',
])

export class ApiError extends Error {
  code: string | null

  constructor(code: string | null, message: string) {
    super(message)
    this.code = code
  }
}

export type SortField = 'stockId' | 'stockName' | 'market'
export type SortOrder = 'asc' | 'desc'
export type MarketFilter = 'ALL' | Market

export interface StockListParams {
  keyword: string
  market: MarketFilter
  includeInactive: boolean
  page: number
  size: number
  sort: SortField
  order: SortOrder
}

export async function fetchStocks(
  params: StockListParams,
  signal?: AbortSignal,
): Promise<StockListResponse> {
  const query = new URLSearchParams()
  if (params.keyword) {
    query.set('keyword', params.keyword)
  }
  if (params.market !== 'ALL') {
    query.set('market', params.market)
  }
  query.set('includeInactive', String(params.includeInactive))
  query.set('page', String(params.page))
  query.set('size', String(params.size))
  query.set('sort', params.sort)
  query.set('order', params.order)

  let response: Response
  try {
    response = await fetch(`/api/stocks?${query.toString()}`, { signal })
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

  return (await response.json()) as StockListResponse
}

export async function fetchStockDetail(stockId: string, signal?: AbortSignal): Promise<StockDetail> {
  let response: Response
  try {
    response = await fetch(`/api/stocks/${encodeURIComponent(stockId)}`, { signal })
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

  return (await response.json()) as StockDetail
}
