// Wire contract: specs/backend/stock-price-ingestion.md, implemented by
// develop/backend/src/main/java/com/stock/controller/StockSyncController.java

import { ApiError, type ApiErrorBody } from './stocks'

export type JobType = 'PRICE_BACKFILL' | 'INDICATOR_REBUILD'

export interface FailedItem {
  stockId: string
  attemptCount: number
  lastError: string
}

export interface ProgressResponse {
  jobType: JobType
  total: number
  pending: number
  running: number
  done: number
  failed: number
  skipped: number
  /** Time the job last *finished successfully*; `null` when it has never completed once. */
  lastSyncedAt: string | null
  failedItems: FailedItem[]
}

export interface BackfillRequest {
  stockIds?: string[]
  startDate: string
  endDate: string
  resume?: boolean
  catchUp?: boolean
}

export interface BackfillResponse {
  jobType: JobType
  targetCount: number
  startDate: string
  endDate: string
  mode: 'SELECTED' | 'ALL'
}

async function parseErrorBody(response: Response): Promise<ApiErrorBody | null> {
  try {
    return (await response.json()) as ApiErrorBody
  } catch {
    return null
  }
}

/** GET /api/stocks/sync/progress?jobType=... */
export async function fetchSyncProgress(jobType: JobType, signal?: AbortSignal): Promise<ProgressResponse> {
  let response: Response
  try {
    response = await fetch(`/api/stocks/sync/progress?jobType=${encodeURIComponent(jobType)}`, { signal })
  } catch (err) {
    if (err instanceof DOMException && err.name === 'AbortError') throw err
    throw new ApiError(null, '網路連線失敗')
  }

  if (!response.ok) {
    const body = await parseErrorBody(response)
    throw new ApiError(body?.code ?? null, `請求失敗（${response.status}）`)
  }

  return (await response.json()) as ProgressResponse
}

/** POST /api/stocks/sync/backfill — a `409 JOB_ALREADY_RUNNING` is thrown as an ApiError with that code;
 * callers should treat it as "already running", not as a failure. */
export async function startBackfill(payload: BackfillRequest, signal?: AbortSignal): Promise<BackfillResponse> {
  let response: Response
  try {
    response = await fetch('/api/stocks/sync/backfill', {
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

  return (await response.json()) as BackfillResponse
}
