import { useEffect, useState, type ReactNode } from 'react'
import { useNavigate } from 'react-router-dom'
import { ApiError, fetchStocks, importStockUniverse, type StockListItem, type UniverseImportResponse } from '../api/stocks'
import {
  fetchStrategyCatalog,
  scanStrategies,
  type BoxBreakoutDetail,
  type ConfirmClosePoint,
  type HigherLowsDetail,
  type PresetCode,
  type RisingSupportDetail,
  type ScanRequest,
  type ScanResponse,
  type StrategyCatalogItem,
  type StrategyCode,
  type StrategyDetail,
  type StrategyHit,
  type StrategyResult,
} from '../api/strategies'
import { fetchSyncProgress, startBackfill, type ProgressResponse } from '../api/sync'
import { useDebouncedValue } from '../hooks/useDebouncedValue'
import './StockListPage.css'

/** Matches the backend's configured startup catch-up start date
 * (specs/backend/stock-price-ingestion.md `app.backfill.startup-catch-up.start-date`).
 * Only matters for a stock that has never been synced at all — every other stock's
 * `catchUp` request resumes from its own `last_synced_date`, ignoring this value. */
const BACKFILL_START_DATE = '2026-01-01'
const POLL_INTERVAL_MS = 5000
const STOCK_ID_CAP = 200

type ScanStatus = 'idle' | 'scanning' | 'success' | 'error'
type SyncStatus = 'idle' | 'running'
type ShortcutKey = '1m' | '3m' | '6m'

function pad2(n: number): string {
  return String(n).padStart(2, '0')
}

function toIsoDate(d: Date): string {
  return `${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())}`
}

function monthsAgo(months: number, from: Date): Date {
  // Plain `d.setMonth(d.getMonth() - months)` silently overflows when the source day
  // doesn't exist in the target month (e.g. Aug 30 minus 6 months lands on "Feb 30",
  // which JS Date normalizes into March 2 instead of clamping) — a real, user-visible
  // date-range bug for the 近半年/近三個月 shortcuts around month-end dates. Clamp the
  // day to the last day of the target month instead, matching calendar-correct
  // "N months ago" semantics.
  const targetMonthIndex = from.getMonth() - months
  const lastDayOfTargetMonth = new Date(from.getFullYear(), targetMonthIndex + 1, 0).getDate()
  const day = Math.min(from.getDate(), lastDayOfTargetMonth)
  return new Date(from.getFullYear(), targetMonthIndex, day)
}

function defaultDateRange(): { startDate: string; endDate: string } {
  const today = new Date()
  return { startDate: toIsoDate(monthsAgo(1, today)), endDate: toIsoDate(today) }
}

function formatPrice2(value: number | null | undefined): string {
  return value == null ? '—' : value.toFixed(2)
}

function formatPercent2(value: number | null | undefined): string {
  return value == null ? '—' : `${value.toFixed(2)}%`
}

function formatMultiple2(value: number | null | undefined): string {
  return value == null ? '—' : `${value.toFixed(2)}×`
}

function formatSyncTime(value: string | null | undefined): string {
  if (!value) return '尚未同步'
  return value.slice(0, 16).replace('T', ' ')
}

function isBoxDetail(detail: StrategyDetail): detail is BoxBreakoutDetail {
  return 'boxHigh' in detail
}

function isHigherLowsDetail(detail: StrategyDetail): detail is HigherLowsDetail {
  return 'lows' in detail
}

function isRisingSupportDetail(detail: StrategyDetail): detail is RisingSupportDetail {
  return 'riseClose' in detail
}

interface UnionHit {
  strategyCode: StrategyCode
  signalDate: string
}

interface UnionRow {
  stockId: string
  stockName: string
  hits: UnionHit[]
}

/** Union across every requested strategy's `items` (never `insufficientData`/`pendingConfirm` —
 * those never appear in `items` per specs/backend/strategy-scan.md), deduped by `stockId`.
 * Hits are appended in `result.results` array order, which the backend already returns in
 * request order — so no dependency on the component's current (possibly since-changed)
 * selection order is needed to keep "策略的排列順序與勾選順序一致". */
function buildUnionRows(result: ScanResponse): UnionRow[] {
  const map = new Map<string, UnionRow>()
  for (const strategyResult of result.results) {
    for (const item of strategyResult.items) {
      let row = map.get(item.stockId)
      if (!row) {
        row = { stockId: item.stockId, stockName: item.stockName, hits: [] }
        map.set(item.stockId, row)
      }
      row.hits.push({ strategyCode: strategyResult.strategy, signalDate: item.signalDate })
    }
  }
  const latestSignalDate = (hits: UnionHit[]) =>
    hits.reduce((max, h) => (h.signalDate > max ? h.signalDate : max), hits[0].signalDate)
  return Array.from(map.values()).sort((a, b) => {
    const latestA = latestSignalDate(a.hits)
    const latestB = latestSignalDate(b.hits)
    if (latestA !== latestB) return latestA < latestB ? 1 : -1
    return a.stockId < b.stockId ? -1 : a.stockId > b.stockId ? 1 : 0
  })
}

function formatLowsSequence(lows: HigherLowsDetail['lows']): string {
  return lows.map((p) => `${p.tradeDate.slice(5)} ${p.low.toFixed(2)}`).join('→')
}

function formatConfirmCloses(points: ConfirmClosePoint[]): string {
  return points.map((p) => `${p.tradeDate.slice(5)} ${p.close.toFixed(2)}`).join('→')
}

function cumulativeRise(lows: HigherLowsDetail['lows']): number | null {
  if (lows.length < 2) return null
  const first = lows[0].low
  const last = lows[lows.length - 1].low
  if (!first) return null
  return (last / first - 1) * 100
}

/** Collapsible "N 檔…" note used for both `insufficientData` and `pendingConfirm` —
 * both are "checked but excluded" lists that must stay visually distinct from the
 * matched-hits table, never merged into it and never silently dropped. */
function ExpandableNote({ label, ids }: { label: string; ids: string[] }) {
  const [open, setOpen] = useState(false)
  return (
    <div className="st-note">
      <button type="button" className="st-note-toggle" onClick={() => setOpen((o) => !o)}>
        {label}
        <span className="st-note-caret">{open ? ' ▲' : ' ▼'}</span>
      </button>
      {open ? <div className="st-note-ids">{ids.join('、')}</div> : null}
    </div>
  )
}

export default function StrategyTab() {
  const navigate = useNavigate()

  // ---------- strategy catalogue ----------
  const [catalog, setCatalog] = useState<StrategyCatalogItem[]>([])
  const [catalogStatus, setCatalogStatus] = useState<'loading' | 'success' | 'error'>('loading')

  const [selectionOrder, setSelectionOrder] = useState<StrategyCode[]>([])
  const [selectedPresets, setSelectedPresets] = useState<Partial<Record<StrategyCode, PresetCode>>>({})

  // ---------- stock scope ----------
  const [scope, setScope] = useState<'ALL' | 'SELECTED'>('ALL')
  const [selectedStocks, setSelectedStocks] = useState<{ stockId: string; stockName: string }[]>([])
  const [stockSearch, setStockSearch] = useState('')
  const debouncedStockSearch = useDebouncedValue(stockSearch, 300)
  const [stockSuggestions, setStockSuggestions] = useState<StockListItem[]>([])

  // ---------- date range ----------
  const [dateRange, setDateRange] = useState(defaultDateRange())
  const [activeShortcut, setActiveShortcut] = useState<ShortcutKey | null>('1m')
  const dateInvalid = dateRange.startDate > dateRange.endDate

  // ---------- scan ----------
  const [scanStatus, setScanStatus] = useState<ScanStatus>('idle')
  const [scanResult, setScanResult] = useState<ScanResponse | null>(null)
  const [scanErrorMessage, setScanErrorMessage] = useState<string | null>(null)
  const [unknownIds, setUnknownIds] = useState<string[] | null>(null)
  const [lastScanPayload, setLastScanPayload] = useState<ScanRequest | null>(null)

  // ---------- sync ----------
  const [lastSyncedAt, setLastSyncedAt] = useState<string | null>(null)
  const [syncStatus, setSyncStatus] = useState<SyncStatus>('idle')
  const [syncProgress, setSyncProgress] = useState<ProgressResponse | null>(null)
  const [syncSummary, setSyncSummary] = useState<ProgressResponse | null>(null)
  // `targetCount`/`caughtUpCount` from the *this-run's* `202` accept response — kept
  // separate from `syncSummary` (which comes from the progress poll) because the two
  // requests race independently; combining them at render time (rather than baking one
  // into the other at whichever moment happens to resolve first) means whichever arrives
  // second still triggers a correct re-render instead of freezing in a half-known state.
  // Reset to `null` on every new click so a slow-to-arrive previous job's numbers can
  // never be attributed to the next job's completion.
  const [syncMeta, setSyncMeta] = useState<{ targetCount: number; caughtUpCount: number } | null>(null)
  const [syncErrorMessage, setSyncErrorMessage] = useState<string | null>(null)
  const [showFailedList, setShowFailedList] = useState(false)

  // ---------- stock universe (list) size + "更新股票清單" ----------
  const [totalStockCount, setTotalStockCount] = useState<number | null>(null)
  const [universeImportStatus, setUniverseImportStatus] = useState<SyncStatus>('idle')
  const [universeImportSummary, setUniverseImportSummary] = useState<UniverseImportResponse | null>(null)
  const [universeImportErrorMessage, setUniverseImportErrorMessage] = useState<string | null>(null)

  // Catalogue — the sole source of strategy names / preset names / description text.
  useEffect(() => {
    const controller = new AbortController()
    fetchStrategyCatalog(controller.signal)
      .then((resp) => {
        setCatalog(resp.strategies ?? [])
        setCatalogStatus('success')
      })
      .catch((err: unknown) => {
        if (err instanceof DOMException && err.name === 'AbortError') return
        setCatalogStatus('error')
      })
    return () => controller.abort()
  }, [])

  // Last-synced time on entry; also detect a sync already in flight (e.g. the startup
  // catch-up, or another tab having just started one) so we start polling right away.
  useEffect(() => {
    const controller = new AbortController()
    fetchSyncProgress('PRICE_BACKFILL', controller.signal)
      .then((resp) => {
        setLastSyncedAt(resp.lastSyncedAt ?? null)
        setSyncProgress(resp)
        if ((resp.pending ?? 0) > 0 || (resp.running ?? 0) > 0) {
          setSyncStatus('running')
        }
      })
      .catch((err: unknown) => {
        if (err instanceof DOMException && err.name === 'AbortError') return
        // Non-fatal — the page just keeps whatever last-synced display it already has.
      })
    return () => controller.abort()
  }, [])

  // Stock universe size on entry — `GET /api/stocks?page=1&size=1`, `size=1` because only
  // `total` is needed here, not the list content.
  useEffect(() => {
    const controller = new AbortController()
    fetchStocks(
      { keyword: '', market: 'ALL', includeInactive: false, page: 1, size: 1, sort: 'stockId', order: 'asc' },
      controller.signal,
    )
      .then((resp) => setTotalStockCount(resp.total))
      .catch((err: unknown) => {
        if (err instanceof DOMException && err.name === 'AbortError') return
        // Non-fatal — the page just keeps whatever count is already shown.
      })
    return () => controller.abort()
  }, [])

  // Poll while a sync is running. Stays alive even while this tab is hidden
  // (display:none) because StockListPage keeps both tabs mounted.
  useEffect(() => {
    if (syncStatus !== 'running') return
    let cancelled = false

    const poll = () => {
      fetchSyncProgress('PRICE_BACKFILL')
        .then((resp) => {
          if (cancelled) return
          setSyncProgress(resp)
          if (resp.pending === 0 && resp.running === 0) {
            setSyncStatus('idle')
            setLastSyncedAt(resp.lastSyncedAt ?? null)
            setSyncSummary(resp)
          }
        })
        .catch(() => {
          // Transient network hiccup — keep polling on the next tick rather than giving up.
        })
    }

    poll()
    const timer = setInterval(poll, POLL_INTERVAL_MS)
    return () => {
      cancelled = true
      clearInterval(timer)
    }
  }, [syncStatus])

  // Stock-scope search suggestions.
  useEffect(() => {
    if (scope !== 'SELECTED' || !debouncedStockSearch) {
      setStockSuggestions([])
      return
    }
    const controller = new AbortController()
    fetchStocks(
      {
        keyword: debouncedStockSearch,
        market: 'ALL',
        includeInactive: true,
        page: 1,
        size: 20,
        sort: 'stockId',
        order: 'asc',
      },
      controller.signal,
    )
      .then((resp) => setStockSuggestions(resp.items))
      .catch((err: unknown) => {
        if (err instanceof DOMException && err.name === 'AbortError') return
        setStockSuggestions([])
      })
    return () => controller.abort()
  }, [scope, debouncedStockSearch])

  const toggleStrategy = (code: StrategyCode) => {
    setSelectionOrder((order) => (order.includes(code) ? order.filter((c) => c !== code) : [...order, code]))
    setSelectedPresets((presets) => (presets[code] ? presets : { ...presets, [code]: 'STANDARD' }))
  }

  const applyShortcut = (key: ShortcutKey) => {
    const months = key === '1m' ? 1 : key === '3m' ? 3 : 6
    const today = new Date()
    setDateRange({ startDate: toIsoDate(monthsAgo(months, today)), endDate: toIsoDate(today) })
    setActiveShortcut(key)
  }

  const setStartDate = (value: string) => {
    setDateRange((r) => ({ ...r, startDate: value }))
    setActiveShortcut(null)
  }

  const setEndDate = (value: string) => {
    setDateRange((r) => ({ ...r, endDate: value }))
    setActiveShortcut(null)
  }

  const addStock = (stock: StockListItem) => {
    setSelectedStocks((list) => {
      if (list.length >= STOCK_ID_CAP) return list
      if (list.some((s) => s.stockId === stock.stockId)) return list
      return [...list, { stockId: stock.stockId, stockName: stock.stockName }]
    })
    setStockSearch('')
    setStockSuggestions([])
  }

  const removeStock = (stockId: string) => {
    setSelectedStocks((list) => list.filter((s) => s.stockId !== stockId))
    setUnknownIds((ids) => (ids ? ids.filter((id) => id !== stockId) : ids))
  }

  const buildScanPayload = (): ScanRequest => ({
    strategies: selectionOrder.map((code) => ({ code, preset: selectedPresets[code] ?? 'STANDARD' })),
    stockIds: scope === 'SELECTED' ? selectedStocks.map((s) => s.stockId) : undefined,
    startDate: dateRange.startDate,
    endDate: dateRange.endDate,
  })

  const runScan = (payload: ScanRequest) => {
    setScanStatus('scanning')
    setScanErrorMessage(null)
    setUnknownIds(null)
    setLastScanPayload(payload)
    scanStrategies(payload)
      .then((resp) => {
        setScanResult(resp)
        setScanStatus('success')
      })
      .catch((err: unknown) => {
        if (err instanceof ApiError && err.code === 'UNKNOWN_STOCK_ID') {
          setUnknownIds(err.unknownIds ?? [])
          setScanStatus(scanResult ? 'success' : 'idle')
          return
        }
        setScanStatus('error')
        setScanErrorMessage('掃描失敗，請稍後再試')
      })
  }

  const noStrategySelected = selectionOrder.length === 0
  const noStockSelected = scope === 'SELECTED' && selectedStocks.length === 0
  const canScan = !noStrategySelected && !noStockSelected && !dateInvalid && scanStatus !== 'scanning'

  const handleScanClick = () => {
    if (!canScan) return
    runScan(buildScanPayload())
  }

  const handleRetry = () => {
    if (lastScanPayload) runScan(lastScanPayload)
  }

  const handleSyncClick = () => {
    setSyncErrorMessage(null)
    setSyncMeta(null)
    setSyncStatus('running')
    startBackfill({ startDate: BACKFILL_START_DATE, endDate: toIsoDate(new Date()), catchUp: true })
      .then((resp) => {
        setSyncMeta({ targetCount: resp.targetCount, caughtUpCount: resp.caughtUpCount })
      })
      .catch((err: unknown) => {
        if (err instanceof ApiError && err.code === 'JOB_ALREADY_RUNNING') {
          // Not an error — someone else's sync is already running; stay in the
          // running state and let the polling effect pick up its progress. We never
          // started that job ourselves, so its caughtUpCount/targetCount stay unknown
          // (syncMeta stays null) — the completion summary falls back to the plain
          // done/failed/skipped counts rather than guessing.
          return
        }
        setSyncStatus('idle')
        setSyncErrorMessage('同步啟動失敗，請稍後再試')
      })
  }

  // "更新股票清單" — a short synchronous action (single upstream request), deliberately
  // independent of `syncStatus`: neither button disables the other (no shared concurrency
  // lock on the backend either — see specs/backend/stock-universe-import.md 的「併發」).
  // Never polls a progress endpoint and never auto-triggers a sync or a re-scan.
  const handleImportUniverseClick = () => {
    setUniverseImportErrorMessage(null)
    setUniverseImportSummary(null)
    setUniverseImportStatus('running')
    importStockUniverse()
      .then((resp) => {
        setUniverseImportSummary(resp)
        setTotalStockCount(resp.totalActiveCount)
        setUniverseImportStatus('idle')
      })
      .catch((err: unknown) => {
        setUniverseImportStatus('idle')
        // All three failure paths leave `stock` completely unwritten — the displayed
        // "共 N 檔" (`totalStockCount`) is deliberately left untouched here.
        if (err instanceof ApiError && err.code === 'UPSTREAM_EMPTY') {
          setUniverseImportErrorMessage('交易所尚未發布今日清單，請稍後再試')
          return
        }
        if (err instanceof ApiError && (err.code === 'UPSTREAM_UNAVAILABLE' || err.code === 'UPSTREAM_MALFORMED')) {
          setUniverseImportErrorMessage('無法取得交易所股票清單，請稍後再試')
          return
        }
        setUniverseImportErrorMessage('更新股票清單失敗，請稍後再試')
      })
  }

  const completedCount = (p: ProgressResponse) => p.done + p.failed + p.skipped
  const progressPercent = (p: ProgressResponse) => (p.total > 0 ? Math.round((completedCount(p) / p.total) * 100) : 0)

  const strategyName = (code: StrategyCode) => catalog.find((s) => s.code === code)?.name ?? code
  const presetName = (code: StrategyCode, preset: PresetCode) =>
    catalog.find((s) => s.code === code)?.presets.find((p) => p.code === preset)?.name ?? preset

  // Union table — only when 2+ strategies were requested and at least one stock was hit.
  const unionRows = scanResult ? buildUnionRows(scanResult) : []
  const showUnionTable = scanResult !== null && scanResult.results.length >= 2 && unionRows.length > 0

  const renderUnionHits = (hits: UnionHit[]) => {
    const nodes: ReactNode[] = []
    hits.forEach((hit, idx) => {
      if (idx > 0) nodes.push(
        <span key={`sep-${hit.strategyCode}-${idx}`} className="st-union-sep">
          ・
        </span>,
      )
      nodes.push(
        <span key={`${hit.strategyCode}-${idx}`} className="st-union-tag">
          <span className="st-union-tag-name">{strategyName(hit.strategyCode)}</span>{' '}
          <span className="st-union-tag-date">{hit.signalDate}</span>
        </span>,
      )
    })
    return nodes
  }

  const renderUnionTable = () => (
    <div className="st-result-block st-union-block">
      <h3 className="st-result-title">命中彙總 — 共 {unionRows.length} 檔</h3>
      <table className="sl-table st-result-table st-union-table">
        <thead>
          <tr>
            <th>代號 / 名稱</th>
            <th>命中策略與訊號日</th>
          </tr>
        </thead>
        <tbody>
          {unionRows.map((row) => (
            <tr key={row.stockId} className="sl-row" onClick={() => navigate(`/stocks/${row.stockId}/daily`)}>
              <td>
                {row.stockId} {row.stockName}
              </td>
              <td className="st-union-hits">{renderUnionHits(row.hits)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )

  const renderBoxTable = (items: StrategyHit[]) => (
    <table className="sl-table st-result-table">
      <thead>
        <tr>
          <th>代號 / 名稱</th>
          <th>訊號日</th>
          <th>箱型區間</th>
          <th className="sl-r">突破收盤</th>
          <th className="sl-r">突破幅度</th>
          <th className="sl-r">量能倍數</th>
        </tr>
      </thead>
      <tbody>
        {items.map((item) => {
          const detail = item.detail
          const box = isBoxDetail(detail) ? detail : null
          return (
            <tr key={item.stockId} className="sl-row" onClick={() => navigate(`/stocks/${item.stockId}/daily`)}>
              <td>
                {item.stockId} {item.stockName}
              </td>
              <td>{item.signalDate}</td>
              <td>
                {formatPrice2(box?.boxLow)} ~ {formatPrice2(box?.boxHigh)}
              </td>
              <td className="sl-r">{formatPrice2(box?.breakoutClose)}</td>
              <td className="sl-r">{formatPercent2(box?.breakoutPercent)}</td>
              <td className="sl-r">{formatMultiple2(box?.volumeRatio)}</td>
            </tr>
          )
        })}
      </tbody>
    </table>
  )

  const renderHigherLowsTable = (items: StrategyHit[]) => (
    <table className="sl-table st-result-table">
      <thead>
        <tr>
          <th>代號 / 名稱</th>
          <th>訊號日</th>
          <th>低點序列</th>
          <th className="sl-r">累計漲幅</th>
        </tr>
      </thead>
      <tbody>
        {items.map((item) => {
          const detail = item.detail
          const lows = isHigherLowsDetail(detail) ? detail.lows : []
          return (
            <tr key={item.stockId} className="sl-row" onClick={() => navigate(`/stocks/${item.stockId}/daily`)}>
              <td>
                {item.stockId} {item.stockName}
              </td>
              <td>{item.signalDate}</td>
              <td>{formatLowsSequence(lows)}</td>
              <td className="sl-r">{formatPercent2(cumulativeRise(lows))}</td>
            </tr>
          )
        })}
      </tbody>
    </table>
  )

  const renderRisingSupportTable = (items: StrategyHit[]) => (
    <table className="sl-table st-result-table">
      <thead>
        <tr>
          <th>代號 / 名稱</th>
          <th>訊號日</th>
          <th className="sl-r">上漲收盤</th>
          <th className="sl-r">單日漲幅</th>
          <th className="sl-r">支撐價</th>
          <th className="sl-r">前段收盤高點</th>
          <th>確認兩日收盤</th>
        </tr>
      </thead>
      <tbody>
        {items.map((item) => {
          const detail = item.detail
          const rs = isRisingSupportDetail(detail) ? detail : null
          return (
            <tr key={item.stockId} className="sl-row" onClick={() => navigate(`/stocks/${item.stockId}/daily`)}>
              <td>
                {item.stockId} {item.stockName}
              </td>
              <td>{item.signalDate}</td>
              <td className="sl-r">{formatPrice2(rs?.riseClose)}</td>
              <td className="sl-r sl-up">{formatPercent2(rs?.risePercent)}</td>
              <td className="sl-r">{formatPrice2(rs?.supportClose)}</td>
              <td className="sl-r">{formatPrice2(rs?.priorHighClose)}</td>
              <td>{rs ? formatConfirmCloses(rs.confirmCloses) : '—'}</td>
            </tr>
          )
        })}
      </tbody>
    </table>
  )

  const renderResultBlock = (result: StrategyResult) => {
    const title = `${strategyName(result.strategy)}（${presetName(result.strategy, result.preset)}）— 命中 ${result.matchedCount} 檔`
    return (
      <div className="st-result-block" key={`${result.strategy}-${result.preset}`}>
        <h3 className="st-result-title">{title}</h3>
        {result.matchedCount === 0 ? (
          <div className="st-result-zero">
            <p>此區間內沒有命中的股票</p>
            <p className="st-last-sync-hint">最後同步：{formatSyncTime(lastSyncedAt)}</p>
          </div>
        ) : result.strategy === 'BOX_BREAKOUT' ? (
          renderBoxTable(result.items)
        ) : result.strategy === 'HIGHER_LOWS' ? (
          renderHigherLowsTable(result.items)
        ) : (
          renderRisingSupportTable(result.items)
        )}
        {result.insufficientData.length > 0 ? (
          <ExpandableNote
            label={`另有 ${result.insufficientData.length} 檔因區間前的歷史資料不足而未納入判定`}
            ids={result.insufficientData}
          />
        ) : null}
        {result.strategy === 'BOX_BREAKOUT' && result.pendingConfirm.length > 0 ? (
          <ExpandableNote label={`另有 ${result.pendingConfirm.length} 檔已突破，但確認日尚未到`} ids={result.pendingConfirm} />
        ) : null}
        {result.strategy === 'RISING_SUPPORT' && result.pendingConfirm.length > 0 ? (
          <ExpandableNote
            label={`另有 ${result.pendingConfirm.length} 檔已上漲，但後兩日的確認尚未完成`}
            ids={result.pendingConfirm}
          />
        ) : null}
      </div>
    )
  }

  return (
    <div className="strategy-tab">
      {/* ---------- sync row ---------- */}
      <div className="st-sync-row">
        <div className="st-sync-info">
          <div className="st-last-sync">最後同步：{formatSyncTime(lastSyncedAt)}</div>
          <div className="st-stock-total">股票清單：共 {totalStockCount ?? '—'} 檔</div>
        </div>
        <div className="st-sync-actions">
          {/* Both are secondary buttons — 開始掃描 is the page's only primary button.
              Neither disables the other: no shared backend concurrency lock (see
              specs/backend/stock-universe-import.md 的「併發」), so the frontend must not
              invent a mutual-exclusion it doesn't need. */}
          <button
            type="button"
            className="sl-btn"
            disabled={universeImportStatus === 'running'}
            onClick={handleImportUniverseClick}
          >
            {universeImportStatus === 'running' ? '更新中…' : '更新股票清單'}
          </button>
          {syncErrorMessage ? <span className="st-inline-error">{syncErrorMessage}</span> : null}
          <button
            type="button"
            className="sl-btn"
            disabled={syncStatus === 'running'}
            onClick={handleSyncClick}
          >
            {syncStatus === 'running' ? '同步中…' : '同步日 K 至今日'}
          </button>
        </div>
      </div>
      {universeImportErrorMessage ? (
        <div className="st-universe-summary">
          <span className="st-inline-error">{universeImportErrorMessage}</span>
        </div>
      ) : universeImportSummary ? (
        <div className="st-universe-summary">
          <span className="st-universe-summary-text">
            股票清單已更新：共 {universeImportSummary.totalActiveCount} 檔（新增 {universeImportSummary.insertedCount}
            、更新 {universeImportSummary.updatedCount}）・產業別 {universeImportSummary.industryCount} 類，未分類{' '}
            {universeImportSummary.uncategorizedStockCount} 檔
          </span>
          {universeImportSummary.industrySourceStatus !== 'OK' ? (
            <span className="st-industry-warning">產業別未更新（來源暫時無法取得），股票清單已更新</span>
          ) : null}
        </div>
      ) : null}
      {syncStatus === 'running' && syncProgress ? (
        <div className="st-sync-progress">
          <div className="st-progress-bar">
            <div className="st-progress-fill" style={{ width: `${progressPercent(syncProgress)}%` }} />
          </div>
          <span className="st-progress-text">
            已完成 {completedCount(syncProgress)} / {syncProgress.total} 檔
          </span>
        </div>
      ) : null}
      {syncStatus === 'idle' && syncSummary ? (
        <div className="st-sync-summary">
          {syncMeta && syncMeta.targetCount > 0 && syncMeta.caughtUpCount === syncMeta.targetCount ? (
            <span className="st-caught-up-note">已是最新，無需更新（{syncMeta.targetCount} 檔）</span>
          ) : (
            <span>
              完成 {syncSummary.done} 檔／失敗 {syncSummary.failed} 檔／略過 {syncSummary.skipped} 檔
              {syncMeta && syncMeta.caughtUpCount > 0 ? (
                <span className="st-caught-up-note">，另 {syncMeta.caughtUpCount} 檔已是最新</span>
              ) : null}
            </span>
          )}
          {syncSummary.failed > 0 ? (
            <button type="button" className="st-note-toggle" onClick={() => setShowFailedList((v) => !v)}>
              查看失敗清單<span className="st-note-caret">{showFailedList ? ' ▲' : ' ▼'}</span>
            </button>
          ) : null}
          {showFailedList && syncSummary.failedItems.length > 0 ? (
            <ul className="st-failed-list">
              {syncSummary.failedItems.map((f) => (
                <li key={f.stockId}>
                  {f.stockId}：{f.lastError}
                </li>
              ))}
            </ul>
          ) : null}
        </div>
      ) : null}

      {/* ---------- condition area ---------- */}
      <div className="st-condition">
        <div className="st-section-label">策略</div>
        {catalogStatus === 'loading' ? (
          <div className="st-strategy-cards-loading">載入策略清單中…</div>
        ) : catalogStatus === 'error' ? (
          <div className="st-inline-error">策略清單載入失敗，請重新整理頁面</div>
        ) : (
          <div className="st-strategy-cards">
            {catalog.map((strategy) => {
              const selected = selectionOrder.includes(strategy.code)
              const preset = selectedPresets[strategy.code] ?? 'STANDARD'
              const presetMeta = strategy.presets.find((p) => p.code === preset)
              return (
                <div
                  key={strategy.code}
                  className={`st-strategy-card${selected ? ' st-strategy-card-selected' : ''}`}
                >
                  <label className="st-strategy-card-head">
                    <input type="checkbox" checked={selected} onChange={() => toggleStrategy(strategy.code)} />
                    <span className="st-strategy-name">{strategy.name}</span>
                  </label>
                  <select
                    className="sl-select"
                    disabled={!selected}
                    value={preset}
                    onChange={(e) =>
                      setSelectedPresets((presets) => ({
                        ...presets,
                        [strategy.code]: e.target.value as PresetCode,
                      }))
                    }
                  >
                    {strategy.presets.map((p) => (
                      <option key={p.code} value={p.code}>
                        {p.name}
                      </option>
                    ))}
                  </select>
                  <p className="st-strategy-desc">{presetMeta?.description}</p>
                </div>
              )
            })}
          </div>
        )}

        <div className="st-section-label">股票範圍</div>
        <div className="st-scope">
          <label className="st-radio">
            <input type="radio" name="st-scope" checked={scope === 'ALL'} onChange={() => setScope('ALL')} />
            全市場
          </label>
          <label className="st-radio">
            <input
              type="radio"
              name="st-scope"
              checked={scope === 'SELECTED'}
              onChange={() => setScope('SELECTED')}
            />
            指定股票
          </label>
        </div>
        {scope === 'SELECTED' ? (
          <div className="st-stock-picker">
            {selectedStocks.length > 0 ? (
              <div className="st-stock-tags">
                {selectedStocks.map((s) => (
                  <span key={s.stockId} className="st-stock-tag">
                    {s.stockId} {s.stockName}
                    <button type="button" onClick={() => removeStock(s.stockId)} aria-label={`移除 ${s.stockId}`}>
                      ×
                    </button>
                  </span>
                ))}
              </div>
            ) : null}
            <input
              type="text"
              className="st-stock-search"
              placeholder="輸入代號或名稱搜尋加入"
              value={stockSearch}
              disabled={selectedStocks.length >= STOCK_ID_CAP}
              onChange={(e) => setStockSearch(e.target.value)}
            />
            {selectedStocks.length >= STOCK_ID_CAP ? <div className="st-inline-hint">最多 200 檔</div> : null}
            {stockSuggestions.length > 0 && stockSearch ? (
              <div className="st-suggestions">
                {stockSuggestions.map((s) => (
                  <div key={s.stockId} className="st-suggestion-item" onClick={() => addStock(s)}>
                    {s.stockId} {s.stockName}
                  </div>
                ))}
              </div>
            ) : null}
            {unknownIds && unknownIds.length > 0 ? (
              <div className="st-inline-error">以下代號不存在：{unknownIds.join('、')}，請移除後重新掃描</div>
            ) : null}
          </div>
        ) : null}

        <div className="st-section-label">區間</div>
        <div className="st-date-range">
          <input type="date" value={dateRange.startDate} onChange={(e) => setStartDate(e.target.value)} />
          <span className="st-date-sep">至</span>
          <input type="date" value={dateRange.endDate} onChange={(e) => setEndDate(e.target.value)} />
          <button
            type="button"
            className={`st-shortcut${activeShortcut === '1m' ? ' st-shortcut-active' : ''}`}
            onClick={() => applyShortcut('1m')}
          >
            近一個月
          </button>
          <button
            type="button"
            className={`st-shortcut${activeShortcut === '3m' ? ' st-shortcut-active' : ''}`}
            onClick={() => applyShortcut('3m')}
          >
            近三個月
          </button>
          <button
            type="button"
            className={`st-shortcut${activeShortcut === '6m' ? ' st-shortcut-active' : ''}`}
            onClick={() => applyShortcut('6m')}
          >
            近半年
          </button>
        </div>
        {dateInvalid ? <div className="st-inline-error">起日不可晚於迄日</div> : null}

        <div className="st-scan-row">
          <button type="button" className="sl-btn sl-btn-primary" disabled={!canScan} onClick={handleScanClick}>
            {scanStatus === 'scanning' ? '掃描中…' : '開始掃描'}
          </button>
          {noStrategySelected ? <span className="st-inline-hint">請至少勾選一個策略</span> : null}
          {!noStrategySelected && noStockSelected ? (
            <span className="st-inline-hint">請至少選擇一檔股票</span>
          ) : null}
        </div>
      </div>

      {/* ---------- results area ---------- */}
      <div className="st-results">
        {scanStatus === 'error' ? (
          <div className="st-results-error">
            <p>{scanErrorMessage}</p>
            <button type="button" className="sl-btn sl-btn-primary" onClick={handleRetry}>
              重試
            </button>
          </div>
        ) : !scanResult && scanStatus !== 'scanning' ? (
          <div className="st-results-placeholder">
            <p>選擇策略與區間後開始掃描</p>
          </div>
        ) : !scanResult ? (
          <div className="st-results-placeholder">
            <p>掃描中…</p>
          </div>
        ) : (
          <div className={scanStatus === 'scanning' ? 'st-results-list st-results-dimmed' : 'st-results-list'}>
            {showUnionTable ? renderUnionTable() : null}
            {scanResult.results.map(renderResultBlock)}
          </div>
        )}
      </div>
    </div>
  )
}
