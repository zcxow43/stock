import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { ApiError } from '../api/stocks'
import { fetchStockStatistics } from '../api/statistics'
import { fetchMinuteBars, type MinuteBar, type MinuteBarResponse, type MinuteInterval } from '../api/minuteBars'
import KLineChart, { type CandleBar, type SubplotSpec } from '../components/KLineChart'
import './StockMinuteChartPage.css'

// ---------- colors (literal hex, per specs/frontend/stock-minute-chart.md Visual Style —
// identical palette to the daily chart page; never a theme token / anything that shifts with
// prefers-color-scheme) ----------
const COLOR = {
  up: '#E04B45',
  down: '#16A75C',
  flat: '#93A4B8',
  volUp: '#9A3B37',
  volDown: '#12784A',
  grid: '#1F2C3A',
  crosshair: '#6B7C90',
  pinnedOutline: '#3E8FD8',
  axisLabelBg: '#3A4757',
  axisLabelText: '#E6EDF5',
  unavailableText: '#93A4B8',
}

const INTERVAL_OPTIONS: { key: MinuteInterval; label: string }[] = [
  { key: 1, label: '1 分' },
  { key: 5, label: '5 分' },
  { key: 15, label: '15 分' },
  { key: 30, label: '30 分' },
  { key: 60, label: '60 分' },
]

function fmt2(v: number | null | undefined): string {
  return v == null ? '—' : v.toFixed(2)
}
function fmtSigned2(v: number | null | undefined, suffix = ''): string {
  if (v == null) return '—'
  return `${v > 0 ? '+' : ''}${v.toFixed(2)}${suffix}`
}
function changeClass(v: number | null | undefined): string {
  if (v == null) return 'mc-muted'
  if (v > 0) return 'mc-up'
  if (v < 0) return 'mc-down'
  return 'mc-flat'
}
function fmtLots(shares: number): string {
  return Math.round(shares / 1000).toLocaleString('en-US')
}
function lotsFromShares(shares: number): number {
  return Math.round(shares / 1000)
}
function formatAbbrev(v: number): string {
  const abs = Math.abs(v)
  if (abs >= 1_000_000) return `${(v / 1_000_000).toFixed(1)}M`
  if (abs >= 1_000) return `${(v / 1_000).toFixed(0)}K`
  return String(Math.round(v))
}
function todayISO(): string {
  return new Date().toISOString().slice(0, 10)
}
function isValidISODate(dateStr: string): boolean {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(dateStr)) return false
  return !Number.isNaN(new Date(`${dateStr}T00:00:00`).getTime())
}
function addDaysISO(dateStr: string, days: number): string {
  const d = new Date(`${dateStr}T00:00:00`)
  d.setDate(d.getDate() + days)
  return d.toISOString().slice(0, 10)
}
function isBoldXTick(label: string): boolean {
  return /:(00|30)$/.test(label)
}
function fmtFetchedAt(v: string | null | undefined): string {
  if (!v) return '—'
  // Backend serializes LocalDateTime as "2026-08-28T16:18:56.711257" — trim to seconds and
  // swap the ISO 'T' for a space to match the storyboard's "YYYY-MM-DD HH:mm:ss" footer.
  return v.replace('T', ' ').slice(0, 19)
}

type MinuteState =
  | { status: 'loading' }
  | { status: 'success'; data: MinuteBarResponse }
  | { status: 'notfound' }
  | { status: 'invalidDate' }
  | { status: 'networkError' }

interface AdjacentDaysState {
  status: 'loading' | 'success' | 'error'
  prevDate: string | null
  nextDate: string | null
}

const DATA_STATUS_MESSAGE: Record<string, string> = {
  OUT_OF_WINDOW: '資料來源僅提供最近 30 天的分鐘資料，此交易日已超出可取得範圍。',
  NO_DATA: '此交易日沒有分鐘成交資料。',
  NOT_A_TRADING_DAY: '此日期非該股票的交易日。',
}

export default function StockMinuteChartPage() {
  const { stockId = '', tradeDate = '' } = useParams<{ stockId: string; tradeDate: string }>()
  const navigate = useNavigate()

  const [interval, setIntervalValue] = useState<MinuteInterval>(1)
  const [minuteState, setMinuteState] = useState<MinuteState>({ status: 'loading' })
  const [adjacent, setAdjacent] = useState<AdjacentDaysState>({ status: 'loading', prevDate: null, nextDate: null })
  const [requestId, setRequestId] = useState(0)
  const [pinnedIndex, setPinnedIndex] = useState<number | null>(null)
  const pendingRefreshRef = useRef(false)

  // Reset per-date UI state whenever the stock or trade date changes.
  useEffect(() => {
    setPinnedIndex(null)
  }, [stockId, tradeDate])

  useEffect(() => {
    function onKeyDown(e: KeyboardEvent) {
      if (e.key === 'Escape') setPinnedIndex(null)
    }
    window.addEventListener('keydown', onKeyDown)
    return () => window.removeEventListener('keydown', onKeyDown)
  }, [])

  const triggerFetch = useCallback((refresh: boolean) => {
    pendingRefreshRef.current = refresh
    setRequestId((n) => n + 1)
  }, [])

  // ---------- minute bars: GET /api/stocks/{stockId}/minute-bars ----------
  useEffect(() => {
    if (!stockId || !tradeDate) return
    const isRefresh = pendingRefreshRef.current
    pendingRefreshRef.current = false
    const controller = new AbortController()
    setMinuteState({ status: 'loading' })
    fetchMinuteBars({ stockId, tradeDate, interval, refresh: isRefresh }, controller.signal)
      .then((data) => setMinuteState({ status: 'success', data }))
      .catch((err: unknown) => {
        if (err instanceof DOMException && err.name === 'AbortError') return
        if (err instanceof ApiError) {
          if (err.code === 'STOCK_NOT_FOUND') {
            setMinuteState({ status: 'notfound' })
            return
          }
          if (
            err.code === 'INVALID_DATE_FORMAT' ||
            err.code === 'MISSING_TRADE_DATE' ||
            err.code === 'FUTURE_TRADE_DATE'
          ) {
            setMinuteState({ status: 'invalidDate' })
            return
          }
          if (err.code === 'INVALID_INTERVAL') {
            // Programmer-error guard (spec): reset to the default period and re-request.
            setIntervalValue(1)
            return
          }
        }
        setMinuteState({ status: 'networkError' })
      })
    return () => controller.abort()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [stockId, tradeDate, interval, requestId])

  // ---------- adjacent trading days: GET /api/stocks/statistics ----------
  useEffect(() => {
    if (!stockId || !tradeDate) return
    // A malformed URL date is reported by the minute-bars request itself (INVALID_DATE_FORMAT ->
    // full-page "網址中的日期無效"); don't let this best-effort sibling request crash on it.
    if (!isValidISODate(tradeDate)) {
      setAdjacent({ status: 'error', prevDate: null, nextDate: null })
      return
    }
    const controller = new AbortController()
    setAdjacent({ status: 'loading', prevDate: null, nextDate: null })
    fetchStockStatistics(
      { stockId, startDate: addDaysISO(tradeDate, -14), endDate: addDaysISO(tradeDate, 14) },
      controller.signal,
    )
      .then((resp) => {
        const series = resp.items[0]?.series ?? []
        const dates = series.map((row) => row.tradeDate).sort()
        let prevDate: string | null = null
        let nextDate: string | null = null
        for (const d of dates) {
          if (d < tradeDate) prevDate = d
          if (d > tradeDate && nextDate == null) nextDate = d
        }
        setAdjacent({ status: 'success', prevDate, nextDate })
      })
      .catch((err: unknown) => {
        if (err instanceof DOMException && err.name === 'AbortError') return
        setAdjacent({ status: 'error', prevDate: null, nextDate: null })
      })
    return () => controller.abort()
  }, [stockId, tradeDate])

  const isToday = tradeDate === todayISO()
  const data = minuteState.status === 'success' ? minuteState.data : null
  const dailySummary = data?.dailySummary ?? null

  const bars: CandleBar[] = useMemo(() => {
    const rows: MinuteBar[] = data?.bars ?? []
    return rows.map((row) => ({ label: row.barTime, open: row.open, high: row.high, low: row.low, close: row.close }))
  }, [data])

  const minuteRows: MinuteBar[] = useMemo(() => data?.bars ?? [], [data])

  const subplots: SubplotSpec[] = useMemo(
    () => [
      {
        id: 'volume',
        title: '成交量（張）',
        height: 110,
        yTickFormat: formatAbbrev,
        series: [
          {
            kind: 'bars',
            key: 'volume',
            values: minuteRows.map((row) => lotsFromShares(row.volume)),
            colorFor: (i) => (minuteRows[i].close >= minuteRows[i].open ? COLOR.volUp : COLOR.volDown),
          },
        ],
      },
    ],
    [minuteRows],
  )

  function handleBarClick(index: number) {
    setPinnedIndex((prev) => (prev === index ? null : index))
  }

  function renderTooltip(index: number) {
    const row = minuteRows[index]
    if (!row) return null
    const dayOpen = dailySummary?.open ?? null
    const changePercent = dayOpen != null && dayOpen !== 0 ? ((row.close - dayOpen) / dayOpen) * 100 : null
    return (
      <div className="mc-tooltip-body">
        <div className="mc-tooltip-date">
          {tradeDate} {row.barTime}
        </div>
        <div className="mc-tooltip-row">
          <span>開</span>
          <span className="mc-tooltip-value">{fmt2(row.open)}</span>
        </div>
        <div className="mc-tooltip-row">
          <span>高</span>
          <span className="mc-tooltip-value">{fmt2(row.high)}</span>
        </div>
        <div className="mc-tooltip-row">
          <span>低</span>
          <span className="mc-tooltip-value">{fmt2(row.low)}</span>
        </div>
        <div className="mc-tooltip-row">
          <span>收</span>
          <span className="mc-tooltip-value">{fmt2(row.close)}</span>
        </div>
        <div className="mc-tooltip-row">
          <span>對開盤</span>
          <span className={changeClass(changePercent)}>{fmtSigned2(changePercent, '%')}</span>
        </div>
        <div className="mc-tooltip-row">
          <span>成交量</span>
          <span className="mc-tooltip-value">{fmtLots(row.volume)} 張</span>
        </div>
      </div>
    )
  }

  if (minuteState.status === 'notfound') {
    return (
      <div className="mc-page mc-fullstate">
        <p>找不到此股票代號</p>
        <Link to="/stocks" className="mc-btn mc-btn-primary">
          返回清單
        </Link>
      </div>
    )
  }

  if (minuteState.status === 'invalidDate') {
    return (
      <div className="mc-page mc-fullstate">
        <p>網址中的日期無效</p>
        <Link to={`/stocks/${stockId}/daily`} className="mc-btn mc-btn-primary">
          返回日 K
        </Link>
      </div>
    )
  }

  return (
    <div className="mc-page">
      <div className="mc-topbar">
        <span className="mc-brand">台股 K 線瀏覽</span>
        <Link to={`/stocks/${stockId}/daily`} className="mc-back">
          ← 返回日 K
        </Link>
      </div>

      <div className="mc-wrap">
        <div className="mc-head">
          <div className="mc-ident">
            <span className="mc-code">{stockId}</span>
            {data && <span className="mc-name">{data.stockName}</span>}
            <span className="mc-date">{tradeDate}</span>
            <span className="mc-sub">分 K</span>
          </div>
          <div className="mc-actions">
            <div className="mc-seg">
              {INTERVAL_OPTIONS.map((opt) => (
                <button
                  key={opt.key}
                  type="button"
                  className={`mc-seg-btn${interval === opt.key ? ' mc-on' : ''}`}
                  onClick={() => setIntervalValue(opt.key)}
                >
                  {opt.label}
                </button>
              ))}
            </div>
            {isToday && (
              <button
                type="button"
                className="mc-btn"
                disabled={minuteState.status === 'loading'}
                onClick={() => triggerFetch(true)}
              >
                重新整理
              </button>
            )}
          </div>
        </div>

        {dailySummary && (
          <div className="mc-strip">
            <div>
              <div className="mc-k">開盤</div>
              <div className="mc-v">{fmt2(dailySummary.open)}</div>
            </div>
            <div>
              <div className="mc-k">最高</div>
              <div className="mc-v mc-up">{fmt2(dailySummary.high)}</div>
            </div>
            <div>
              <div className="mc-k">最低</div>
              <div className="mc-v mc-down">{fmt2(dailySummary.low)}</div>
            </div>
            <div>
              <div className="mc-k">收盤</div>
              <div className={`mc-v ${changeClass(dailySummary.close - dailySummary.open)}`}>
                {fmt2(dailySummary.close)}
              </div>
            </div>
            <div>
              <div className="mc-k">成交量</div>
              <div className="mc-v">{fmtLots(dailySummary.volume)}</div>
              <div className="mc-sub2">張</div>
            </div>
          </div>
        )}

        <div className="mc-panel">
          {minuteState.status === 'loading' && (
            <div className="mc-skeleton">
              <p className="mc-skeleton-text">正在取得當日分鐘資料…</p>
              <div className="mc-skeleton-block" style={{ height: 320 }} />
              <div className="mc-skeleton-block" style={{ height: 110 }} />
            </div>
          )}

          {minuteState.status === 'networkError' && (
            <div className="mc-error">
              <p>載入失敗，請稍後再試</p>
              <button type="button" className="mc-btn mc-btn-primary" onClick={() => triggerFetch(false)}>
                重新載入
              </button>
            </div>
          )}

          {minuteState.status === 'success' && data!.dataStatus === 'FETCH_FAILED' && (
            <div className="mc-error">
              <p>取得分鐘資料失敗</p>
              {data!.message && <p className="mc-error-detail">{data!.message}</p>}
              <button
                type="button"
                className="mc-btn mc-btn-primary"
                onClick={() => triggerFetch(true)}
              >
                重試
              </button>
            </div>
          )}

          {minuteState.status === 'success' &&
            data!.dataStatus !== 'FETCH_FAILED' &&
            data!.dataStatus !== 'AVAILABLE' && (
              <div className="mc-unavailable">
                <div className="mc-unavailable-icon">—</div>
                <p>{DATA_STATUS_MESSAGE[data!.dataStatus]}</p>
                <Link to={`/stocks/${stockId}/daily`} className="mc-btn">
                  返回日 K
                </Link>
              </div>
            )}

          {minuteState.status === 'success' && data!.dataStatus === 'AVAILABLE' && bars.length > 0 && (
            <>
              <KLineChart
                bars={bars}
                priceHeight={320}
                subplots={subplots}
                pinnedIndex={pinnedIndex}
                onBarClick={handleBarClick}
                onBarDoubleClick={() => {}}
                renderTooltip={renderTooltip}
                upColor={COLOR.up}
                downColor={COLOR.down}
                gridColor={COLOR.grid}
                axisTextColor={COLOR.flat}
                referenceLineColor={COLOR.crosshair}
                crosshairColor={COLOR.crosshair}
                pinnedOutlineColor={COLOR.pinnedOutline}
                axisLabelBg={COLOR.axisLabelBg}
                axisLabelText={COLOR.axisLabelText}
                unavailableTextColor={COLOR.unavailableText}
                boldXTick={isBoldXTick}
              />
              <div className="mc-footer">
                資料來源 {data!.source ?? '—'} ・ 抓取時間 {fmtFetchedAt(data!.fetchedAt)} ・ 共 {data!.barCount} 根 K 棒
              </div>
            </>
          )}

          {minuteState.status === 'success' && data!.dataStatus === 'AVAILABLE' && bars.length === 0 && (
            <div className="mc-unavailable">
              <div className="mc-unavailable-icon">—</div>
              <p>此交易日沒有分鐘成交資料。</p>
              <Link to={`/stocks/${stockId}/daily`} className="mc-btn">
                返回日 K
              </Link>
            </div>
          )}
        </div>

        <div className="mc-nav">
          <button
            type="button"
            className="mc-btn"
            disabled={adjacent.prevDate == null}
            onClick={() => adjacent.prevDate && navigate(`/stocks/${stockId}/minute/${adjacent.prevDate}`)}
          >
            ← 前一交易日
          </button>
          <button
            type="button"
            className="mc-btn"
            disabled={adjacent.nextDate == null}
            onClick={() => adjacent.nextDate && navigate(`/stocks/${stockId}/minute/${adjacent.nextDate}`)}
          >
            後一交易日 →
          </button>
        </div>
      </div>
    </div>
  )
}
