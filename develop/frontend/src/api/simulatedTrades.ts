// Wire contract: specs/backend/simulated-trade.md, implemented by
// develop/backend/src/main/java/com/stock/controller/SimulatedTradeController.java

export interface SimulatedTradeItem {
  id: number
  stockId: string
  stockName: string
  buyDate: string
  buyPrice: number
  shares: number
  buyFee: number
  cost: number
  currentDate: string
  currentPrice: number
  sellFee: number
  sellTax: number
  /** Yuan, can be negative. */
  unrealizedProfit: number
  /** Two decimals, can be negative. */
  returnPercent: number
}

export interface SimulatedTradeListResponse {
  asOfDate: string
  lotSize: number
  feeRatePercent: number
  taxRatePercent: number
  totalCost: number
  totalUnrealizedProfit: number
  /** `null` only when `items` is empty. */
  totalReturnPercent: number | null
  /** Ordered by the backend (buyDate desc, stockId asc) — the frontend never re-sorts. */
  items: SimulatedTradeItem[]
}

interface SimulatedTradeErrorBody {
  code: string
  stockId?: string
  unknownIds?: string[]
  buyDate?: string
  id?: number
}

export class SimulatedTradeApiError extends Error {
  code: string | null
  stockId: string | null
  unknownIds: string[] | null
  buyDate: string | null
  id: number | null

  constructor(
    code: string | null,
    message: string,
    stockId: string | null = null,
    unknownIds: string[] | null = null,
    buyDate: string | null = null,
    id: number | null = null,
  ) {
    super(message)
    this.code = code
    this.stockId = stockId
    this.unknownIds = unknownIds
    this.buyDate = buyDate
    this.id = id
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
    throw new SimulatedTradeApiError(null, '網路連線失敗')
  }

  if (!response.ok) {
    let body: SimulatedTradeErrorBody | null = null
    try {
      body = (await response.json()) as SimulatedTradeErrorBody
    } catch {
      body = null
    }
    throw new SimulatedTradeApiError(
      body?.code ?? null,
      `請求失敗（${response.status}）`,
      body?.stockId ?? null,
      body?.unknownIds ?? null,
      body?.buyDate ?? null,
      body?.id ?? null,
    )
  }

  if (response.status === 204) {
    return undefined as T
  }
  return (await response.json()) as T
}

/** GET /api/simulated-trades — fired once on entering the tab (and on every return to it,
 * since re-entry must reflect any changes made elsewhere in the meantime). */
export async function fetchSimulatedTrades(signal?: AbortSignal): Promise<SimulatedTradeListResponse> {
  return request<SimulatedTradeListResponse>('/api/simulated-trades', { method: 'GET' }, signal)
}

/** POST /api/simulated-trades — body is `{ stockId }` only; buyDate/buyPrice/shares are
 * never caller-supplied (specs/backend/simulated-trade.md). The response body is only used
 * to confirm what the backend chose (and to flash the new row) — the caller must still
 * re-fetch the list afterward, since sort order and the three totals are backend-owned. */
export async function createSimulatedTrade(stockId: string, signal?: AbortSignal): Promise<SimulatedTradeItem> {
  return request<SimulatedTradeItem>(
    '/api/simulated-trades',
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ stockId }),
    },
    signal,
  )
}

/** DELETE /api/simulated-trades/{id} — 204 no content. */
export async function deleteSimulatedTrade(id: number, signal?: AbortSignal): Promise<void> {
  await request<void>(`/api/simulated-trades/${id}`, { method: 'DELETE' }, signal)
}
