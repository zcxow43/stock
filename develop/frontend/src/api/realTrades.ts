// Wire contract: specs/backend/real-trade.md, implemented by
// develop/backend/src/main/java/com/stock/controller/RealTradeController.java

export interface RealTradeItem {
  /** Position of the row in the data file — only stable between one GET and the next
   * POST/DELETE, so the list is always re-fetched after a write. */
  id: number
  stockId: string
  stockName: string | null
  buyDate: string
  buyPrice: number
  /** Shares, not lots (1 lot = 1,000 shares). */
  shares: number
  buyFee: number
  cost: number
  /** The four fields below are all `null` together when the stock has no positive close. */
  currentDate: string | null
  currentPrice: number | null
  sellFee: number | null
  sellTax: number | null
  /** Yuan, can be negative. */
  unrealizedProfit: number | null
  /** Two decimals, can be negative. */
  returnPercent: number | null
}

export interface SkippedLine {
  /** Actual line number in the data file (header = line 1). */
  lineNumber: number
  content: string
}

export interface RealTradeListResponse {
  asOfDate: string
  feeRatePercent: number
  taxRatePercent: number
  totalCost: number
  totalUnrealizedProfit: number
  /** `null` when there is nothing priced to divide by. */
  totalReturnPercent: number | null
  /** Ordered by the backend — the frontend never re-sorts. */
  items: RealTradeItem[]
  skippedLines: SkippedLine[]
}

interface RealTradeErrorBody {
  code: string
  unknownIds?: string[]
}

export class RealTradeApiError extends Error {
  code: string | null
  unknownIds: string[] | null

  constructor(code: string | null, message: string, unknownIds: string[] | null = null) {
    super(message)
    this.code = code
    this.unknownIds = unknownIds
  }
}

async function request<T>(url: string, init?: RequestInit, signal?: AbortSignal): Promise<T> {
  let response: Response
  try {
    response = await fetch(url, { ...init, signal })
  } catch (err) {
    if (err instanceof DOMException && err.name === 'AbortError') {
      throw err
    }
    throw new RealTradeApiError(null, '網路連線失敗')
  }

  if (!response.ok) {
    let body: RealTradeErrorBody | null = null
    try {
      body = (await response.json()) as RealTradeErrorBody
    } catch {
      body = null
    }
    throw new RealTradeApiError(body?.code ?? null, `請求失敗（${response.status}）`, body?.unknownIds ?? null)
  }

  if (response.status === 204) {
    return undefined as T
  }
  return (await response.json()) as T
}

/** GET /api/real-trades — the whole list, the three totals, the rates and `skippedLines`. */
export async function fetchRealTrades(signal?: AbortSignal): Promise<RealTradeListResponse> {
  return request<RealTradeListResponse>('/api/real-trades', { method: 'GET' }, signal)
}

/** POST /api/real-trades — all four fields are caller-supplied. The caller must re-fetch the
 * list afterward: ordering, totals and every row's `id` are backend-owned. */
export async function createRealTrade(
  stockId: string,
  buyDate: string,
  buyPrice: number,
  shares: number,
  signal?: AbortSignal,
): Promise<RealTradeItem> {
  return request<RealTradeItem>(
    '/api/real-trades',
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ stockId, buyDate, buyPrice, shares }),
    },
    signal,
  )
}

/** DELETE /api/real-trades/{id} — 204 no content. */
export async function deleteRealTrade(id: number, signal?: AbortSignal): Promise<void> {
  await request<void>(`/api/real-trades/${id}`, { method: 'DELETE' }, signal)
}
