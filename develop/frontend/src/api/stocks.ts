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
  fields?: string[]
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
  fields: string[] | null
  /** Populated for `UNKNOWN_STOCK_ID` responses — the offending stock ids from the request. */
  unknownIds: string[] | null

  constructor(
    code: string | null,
    message: string,
    fields: string[] | null = null,
    unknownIds: string[] | null = null,
  ) {
    super(message)
    this.code = code
    this.fields = fields
    this.unknownIds = unknownIds
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

// ---------- stock maintenance (create / edit / delist) — specs/frontend/stock-list.md ----------

export interface CreateStockRequest {
  stockId: string
  stockName: string
  market: Market
}

export interface UpdateStockRequest {
  stockName: string
  market: Market
  isActive: boolean
}

export interface StockDeleteResponse {
  stockId: string
  stockName: string
  isActive: boolean
}

/** Shared request/error-parsing helper for the write endpoints below.
 * `fetchStocks`/`fetchStockDetail` above are left untouched (already working, per convention). */
async function requestJson<T>(url: string, init: RequestInit, signal?: AbortSignal): Promise<T> {
  let response: Response
  try {
    response = await fetch(url, { ...init, signal })
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
    throw new ApiError(body?.code ?? null, `請求失敗（${response.status}）`, body?.fields ?? null)
  }

  return (await response.json()) as T
}

/** POST /api/stocks */
export async function createStock(payload: CreateStockRequest, signal?: AbortSignal): Promise<StockDetail> {
  return requestJson<StockDetail>(
    '/api/stocks',
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    },
    signal,
  )
}

/** PUT /api/stocks/{stockId} */
export async function updateStock(
  stockId: string,
  payload: UpdateStockRequest,
  signal?: AbortSignal,
): Promise<StockDetail> {
  return requestJson<StockDetail>(
    `/api/stocks/${encodeURIComponent(stockId)}`,
    {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    },
    signal,
  )
}

/** DELETE /api/stocks/{stockId} — soft delete (sets isActive=false); no rows are ever removed. */
export async function deactivateStock(stockId: string, signal?: AbortSignal): Promise<StockDeleteResponse> {
  return requestJson<StockDeleteResponse>(
    `/api/stocks/${encodeURIComponent(stockId)}`,
    { method: 'DELETE' },
    signal,
  )
}

// ---------- universe import — specs/backend/stock-universe-import.md, specs/frontend/strategy.md ----------

export interface UniverseImportResponse {
  fetchedCount: number
  eligibleCount: number
  skippedCount: number
  insertedCount: number
  updatedCount: number
  totalActiveCount: number
}

/** POST /api/stocks/universe/import — no request body. A short synchronous action (single
 * upstream request); never poll a progress endpoint for this. On failure the body carries
 * `code` of `UPSTREAM_EMPTY` / `UPSTREAM_UNAVAILABLE` / `UPSTREAM_MALFORMED` with a `502` status. */
export async function importStockUniverse(signal?: AbortSignal): Promise<UniverseImportResponse> {
  return requestJson<UniverseImportResponse>('/api/stocks/universe/import', { method: 'POST' }, signal)
}
