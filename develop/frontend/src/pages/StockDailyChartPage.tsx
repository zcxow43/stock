import { useEffect, useMemo, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { ApiError, fetchStockDetail, type StockDetail } from '../api/stocks'
import { fetchStockStatistics, type StatisticsItem } from '../api/statistics'
import KLineChart, { type CandleBar, type SubplotSpec } from '../components/KLineChart'
import './StockDailyChartPage.css'

// ---------- colors (literal hex, per specs/frontend/stock-daily-chart.md Visual Style —
// never a theme token / anything that shifts with prefers-color-scheme) ----------
const COLOR = {
  up: '#E04B45',
  down: '#16A75C',
  flat: '#93A4B8',
  volUp: '#9A3B37',
  volDown: '#12784A',
  macdDif: '#E8B84B',
  macdDea: '#4B9FE8',
  kdK: '#E8B84B',
  kdD: '#4B9FE8',
  kdJ: '#C77DD8',
  grid: '#1F2C3A',
  refLine: '#3A4757',
  crosshair: '#6B7C90',
  pinnedOutline: '#3E8FD8',
  axisLabelBg: '#3A4757',
  axisLabelText: '#E6EDF5',
  unavailableText: '#6B7C90',
}

type RangeKey = '1M' | '3M' | '6M' | '1Y' | 'CUSTOM'

const RANGE_OPTIONS: { key: RangeKey; label: string; months?: number }[] = [
  { key: '1M', label: '1 個月', months: 1 },
  { key: '3M', label: '3 個月', months: 3 },
  { key: '6M', label: '6 個月', months: 6 },
  { key: '1Y', label: '1 年', months: 12 },
  { key: 'CUSTOM', label: '自訂' },
]

function monthsAgoISO(months: number): string {
  const d = new Date()
  d.setMonth(d.getMonth() - months)
  return d.toISOString().slice(0, 10)
}

function marketLabel(market: string): string {
  return market === 'TSE' ? '上市' : '上櫃'
}
function marketTagClass(market: string): string {
  return market === 'TSE' ? 'dc-tse' : 'dc-otc'
}

function fmt2(v: number | null | undefined): string {
  return v == null ? '—' : v.toFixed(2)
}
function fmt4(v: number | null | undefined): string {
  return v == null ? '—' : v.toFixed(4)
}
function fmtSigned2(v: number | null | undefined, suffix = ''): string {
  if (v == null) return '—'
  return `${v > 0 ? '+' : ''}${v.toFixed(2)}${suffix}`
}
function changeClass(v: number | null | undefined): string {
  if (v == null) return 'dc-muted'
  if (v > 0) return 'dc-up'
  if (v < 0) return 'dc-down'
  return 'dc-flat'
}
function fmtCrossCount(v: number | null | undefined): string {
  return v == null ? '—' : String(v)
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

interface DetailState {
  status: 'loading' | 'success' | 'notfound' | 'error'
  data: StockDetail | null
  message: string | null
}

interface StatsState {
  status: 'loading' | 'success' | 'error'
  item: StatisticsItem | null
  message: string | null
}

export default function StockDailyChartPage() {
  const { stockId = '' } = useParams<{ stockId: string }>()
  const navigate = useNavigate()

  const [detail, setDetail] = useState<DetailState>({ status: 'loading', data: null, message: null })
  const [stats, setStats] = useState<StatsState>({ status: 'loading', item: null, message: null })
  const [retryToken, setRetryToken] = useState(0)

  const [rangeKey, setRangeKey] = useState<RangeKey>('3M')
  const [customStart, setCustomStart] = useState('')
  const [customEnd, setCustomEnd] = useState('')

  const [pinnedIndex, setPinnedIndex] = useState<number | null>(null)
  const [bannerDismissed, setBannerDismissed] = useState(false)

  // Reset stock-scoped UI state whenever the stock changes.
  useEffect(() => {
    setRangeKey('3M')
    setCustomStart('')
    setCustomEnd('')
    setPinnedIndex(null)
    setBannerDismissed(false)
  }, [stockId])

  // Unpin on Esc (native key handling, not a hand-rolled double-click timer).
  useEffect(() => {
    function onKeyDown(e: KeyboardEvent) {
      if (e.key === 'Escape') setPinnedIndex(null)
    }
    window.addEventListener('keydown', onKeyDown)
    return () => window.removeEventListener('keydown', onKeyDown)
  }, [])

  // ---------- header: GET /api/stocks/{stockId} ----------
  useEffect(() => {
    if (!stockId) return
    const controller = new AbortController()
    setDetail({ status: 'loading', data: null, message: null })
    fetchStockDetail(stockId, controller.signal)
      .then((data) => setDetail({ status: 'success', data, message: null }))
      .catch((err: unknown) => {
        if (err instanceof DOMException && err.name === 'AbortError') return
        if (err instanceof ApiError && err.code === 'STOCK_NOT_FOUND') {
          setDetail({ status: 'notfound', data: null, message: null })
          return
        }
        setDetail({ status: 'error', data: null, message: '載入失敗，請稍後再試' })
      })
    return () => controller.abort()
  }, [stockId, retryToken])

  const customInvalid = customStart !== '' && customEnd !== '' && customStart > customEnd

  const activeQuery = useMemo(() => {
    if (!stockId) return null
    if (rangeKey === 'CUSTOM') {
      if (!customStart || !customEnd || customInvalid) return null
      return { stockId, startDate: customStart, endDate: customEnd }
    }
    const months = RANGE_OPTIONS.find((o) => o.key === rangeKey)?.months ?? 3
    return { stockId, startDate: monthsAgoISO(months) }
  }, [stockId, rangeKey, customStart, customEnd, customInvalid])

  // ---------- chart: GET /api/stocks/statistics ----------
  useEffect(() => {
    if (!activeQuery) return
    const controller = new AbortController()
    setPinnedIndex(null)
    setStats({ status: 'loading', item: null, message: null })
    fetchStockStatistics(activeQuery, controller.signal)
      .then((resp) => {
        const item = resp.items[0] ?? null
        setStats({ status: 'success', item, message: null })
      })
      .catch((err: unknown) => {
        if (err instanceof DOMException && err.name === 'AbortError') return
        if (err instanceof ApiError && err.code === 'STOCK_NOT_FOUND') {
          setDetail((d) => ({ ...d, status: 'notfound' }))
          return
        }
        setStats({ status: 'error', item: null, message: '載入失敗，請稍後再試' })
      })
    return () => controller.abort()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeQuery, retryToken])

  const reload = () => setRetryToken((t) => t + 1)

  if (detail.status === 'notfound') {
    return (
      <div className="dc-page dc-fullstate">
        <p>找不到此股票代號</p>
        <Link to="/stocks" className="dc-btn dc-btn-primary">
          返回清單
        </Link>
      </div>
    )
  }

  const item = stats.item
  const series = item?.series ?? []
  const summary = item?.summary ?? null
  const hasNoData = stats.status === 'success' && (item == null || item.tradingDays === 0 || summary == null)

  const bars: CandleBar[] = series.map((row) => ({
    label: row.tradeDate,
    open: row.open,
    high: row.high,
    low: row.low,
    close: row.close,
  }))

  const macdUnavailable = series.length === 0 || series.every((row) => row.dif == null)
  const kdUnavailable = series.length === 0 || series.every((row) => row.k == null)

  const subplots: SubplotSpec[] = [
    {
      id: 'volume',
      title: '成交量（張）',
      height: 78,
      yTickFormat: formatAbbrev,
      series: [
        {
          kind: 'bars',
          key: 'volume',
          values: series.map((row) => lotsFromShares(row.volume)),
          colorFor: (i) => (series[i].close >= series[i].open ? COLOR.volUp : COLOR.volDown),
        },
      ],
    },
    {
      id: 'macd',
      title: 'MACD (12,26,9)',
      height: 90,
      referenceValues: [0],
      unavailable: macdUnavailable,
      series: macdUnavailable
        ? []
        : [
            { kind: 'line', key: 'dif', color: COLOR.macdDif, values: series.map((r) => r.dif) },
            { kind: 'line', key: 'dea', color: COLOR.macdDea, values: series.map((r) => r.dea) },
            {
              kind: 'bars',
              key: 'osc',
              values: series.map((r) => r.osc),
              colorFor: (i) => ((series[i].osc ?? 0) >= 0 ? COLOR.up : COLOR.down),
            },
          ],
    },
    {
      id: 'kd',
      title: 'KD (9,3,3)',
      height: 90,
      referenceValues: [20, 80],
      unavailable: kdUnavailable,
      series: kdUnavailable
        ? []
        : [
            { kind: 'line', key: 'k', color: COLOR.kdK, values: series.map((r) => r.k) },
            { kind: 'line', key: 'd', color: COLOR.kdD, values: series.map((r) => r.d) },
            { kind: 'line', key: 'j', color: COLOR.kdJ, values: series.map((r) => r.j) },
          ],
    },
  ]

  function handleBarClick(index: number) {
    setPinnedIndex((prev) => (prev === index ? null : index))
  }

  function handleBarDoubleClick(index: number) {
    // Pin first so the crosshair/tooltip is already settled on this bar before navigating —
    // it must not flicker after the route changes back (spec requirement).
    setPinnedIndex(index)
    const row = series[index]
    if (row) navigate(`/stocks/${stockId}/minute/${row.tradeDate}`)
  }

  function renderTooltip(index: number) {
    const row = series[index]
    if (!row) return null
    const prevClose = index > 0 ? series[index - 1].close : null
    const changeAmount = prevClose != null ? row.close - prevClose : null
    const changePercent = prevClose != null && prevClose !== 0 ? (changeAmount! / prevClose) * 100 : null
    return (
      <div className="dc-tooltip-body">
        <div className="dc-tooltip-date">{row.tradeDate}</div>
        <div className="dc-tooltip-row">
          <span>開</span>
          <span className="dc-tooltip-value">{fmt2(row.open)}</span>
        </div>
        <div className="dc-tooltip-row">
          <span>高</span>
          <span className="dc-tooltip-value">{fmt2(row.high)}</span>
        </div>
        <div className="dc-tooltip-row">
          <span>低</span>
          <span className="dc-tooltip-value">{fmt2(row.low)}</span>
        </div>
        <div className="dc-tooltip-row">
          <span>收</span>
          <span className="dc-tooltip-value">{fmt2(row.close)}</span>
        </div>
        <div className="dc-tooltip-row">
          <span>漲跌</span>
          <span className={changeClass(changeAmount)}>
            {changeAmount == null ? '—' : `${fmtSigned2(changeAmount)}（${fmtSigned2(changePercent, '%')}）`}
          </span>
        </div>
        <div className="dc-tooltip-row">
          <span>成交量</span>
          <span className="dc-tooltip-value">{fmtLots(row.volume)} 張</span>
        </div>
        <div className="dc-tooltip-row">
          <span>DIF / DEA</span>
          <span className="dc-tooltip-value">
            {fmt4(row.dif)} / {fmt4(row.dea)}
          </span>
        </div>
        <div className="dc-tooltip-row">
          <span>OSC</span>
          <span className="dc-tooltip-value">{fmt4(row.osc)}</span>
        </div>
        <div className="dc-tooltip-row">
          <span>K / D / J</span>
          <span className="dc-tooltip-value">
            {fmt4(row.k)} / {fmt4(row.d)} / {fmt4(row.j)}
          </span>
        </div>
      </div>
    )
  }

  return (
    <div className="dc-page">
      <div className="dc-topbar">
        <Link to="/stocks" className="dc-back">
          ← 返回清單
        </Link>
      </div>

      <div className="dc-wrap">
        <div className="dc-head">
          <div className="dc-ident">
            <span className="dc-code">{stockId}</span>
            {detail.status === 'success' && (
              <>
                <span className="dc-name">{detail.data!.stockName}</span>
                <span className={`dc-tag ${marketTagClass(detail.data!.market)}`}>
                  {marketLabel(detail.data!.market)}
                </span>
                <span className="dc-quote">
                  <span className="dc-last">{fmt2(detail.data!.latestClose)}</span>
                  <span className={`dc-chg ${changeClass(detail.data!.changeAmount)}`}>
                    {fmtSigned2(detail.data!.changeAmount)}（{fmtSigned2(detail.data!.changePercent, '%')}）
                  </span>
                </span>
              </>
            )}
          </div>
          <div className="dc-seg">
            {RANGE_OPTIONS.map((opt) => (
              <button
                key={opt.key}
                type="button"
                className={`dc-seg-btn${rangeKey === opt.key ? ' dc-on' : ''}`}
                onClick={() => setRangeKey(opt.key)}
              >
                {opt.label}
              </button>
            ))}
          </div>
        </div>

        {rangeKey === 'CUSTOM' && (
          <div className="dc-custom-range">
            <label>
              起日
              <input type="date" value={customStart} onChange={(e) => setCustomStart(e.target.value)} />
            </label>
            <label>
              迄日
              <input type="date" value={customEnd} onChange={(e) => setCustomEnd(e.target.value)} />
            </label>
            {customInvalid && <span className="dc-custom-error">起日不可晚於迄日</span>}
          </div>
        )}

        {stats.status === 'success' && summary != null && !bannerDismissed && item && !item.warmupSufficient && (
          <div className="dc-warmup-banner">
            <span>本檔歷史資料不足 250 個交易日，MACD／KD 數值尚未收斂，僅供參考。</span>
            <button type="button" className="dc-banner-close" onClick={() => setBannerDismissed(true)}>
              ×
            </button>
          </div>
        )}

        {stats.status === 'success' && summary != null && (
          <div className="dc-strip">
            <div>
              <div className="dc-k">區間開盤</div>
              <div className="dc-v">{fmt2(summary.firstOpen)}</div>
            </div>
            <div>
              <div className="dc-k">最新收盤</div>
              <div className="dc-v">{fmt2(summary.lastClose)}</div>
            </div>
            <div>
              <div className="dc-k">區間最高</div>
              <div className="dc-v dc-up">{fmt2(summary.highest)}</div>
              <div className="dc-sub">{summary.highestDate}</div>
            </div>
            <div>
              <div className="dc-k">區間最低</div>
              <div className="dc-v dc-down">{fmt2(summary.lowest)}</div>
              <div className="dc-sub">{summary.lowestDate}</div>
            </div>
            <div>
              <div className="dc-k">區間漲跌</div>
              <div className={`dc-v ${changeClass(summary.changeAmount)}`}>{fmtSigned2(summary.changeAmount)}</div>
              <div className="dc-sub">{fmtSigned2(summary.changePercent, '%')}</div>
            </div>
            <div>
              <div className="dc-k">交易日數</div>
              <div className="dc-v">{item?.tradingDays}</div>
            </div>
            <div>
              <div className="dc-k">總成交量</div>
              <div className="dc-v">{fmtLots(summary.totalVolume)}</div>
              <div className="dc-sub">張</div>
            </div>
            <div>
              <div className="dc-k">MACD 交叉</div>
              <div className="dc-v">
                {summary.macdGoldenCross == null
                  ? '—'
                  : `黃金 ${fmtCrossCount(summary.macdGoldenCross)} ／ 死亡 ${fmtCrossCount(summary.macdDeathCross)}`}
              </div>
            </div>
            <div>
              <div className="dc-k">KD 交叉</div>
              <div className="dc-v">
                {summary.kdGoldenCross == null
                  ? '—'
                  : `黃金 ${fmtCrossCount(summary.kdGoldenCross)} ／ 死亡 ${fmtCrossCount(summary.kdDeathCross)}`}
              </div>
            </div>
          </div>
        )}

        <div className="dc-panel">
          {stats.status === 'loading' && (
            <div className="dc-skeleton">
              <div className="dc-skeleton-block" style={{ height: 260 }} />
              <div className="dc-skeleton-block" style={{ height: 78 }} />
              <div className="dc-skeleton-block" style={{ height: 90 }} />
              <div className="dc-skeleton-block" style={{ height: 90 }} />
            </div>
          )}

          {stats.status === 'error' && (
            <div className="dc-error">
              <p>{stats.message}</p>
              <button type="button" className="dc-btn dc-btn-primary" onClick={reload}>
                重新載入
              </button>
            </div>
          )}

          {stats.status === 'success' && hasNoData && (
            <div className="dc-empty">
              <p>此區間內沒有行情資料</p>
              <button type="button" className="dc-btn" onClick={() => setRangeKey('1Y')}>
                改看近 1 年
              </button>
            </div>
          )}

          {stats.status === 'success' && !hasNoData && (
            <KLineChart
              bars={bars}
              subplots={subplots}
              pinnedIndex={pinnedIndex}
              onBarClick={handleBarClick}
              onBarDoubleClick={handleBarDoubleClick}
              renderTooltip={renderTooltip}
              upColor={COLOR.up}
              downColor={COLOR.down}
              gridColor={COLOR.grid}
              axisTextColor={COLOR.flat}
              referenceLineColor={COLOR.refLine}
              crosshairColor={COLOR.crosshair}
              pinnedOutlineColor={COLOR.pinnedOutline}
              axisLabelBg={COLOR.axisLabelBg}
              axisLabelText={COLOR.axisLabelText}
              unavailableTextColor={COLOR.unavailableText}
            />
          )}
        </div>

        {stats.status === 'success' && !hasNoData && <div className="dc-hint">連點兩下任一根 K 棒可查看當日分 K</div>}
      </div>
    </div>
  )
}
