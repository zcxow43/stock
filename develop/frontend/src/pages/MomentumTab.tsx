import { useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { ApiError } from '../api/stocks'
import {
  fetchMomentumGain,
  type Metric,
  type MomentumGainParams,
  type MomentumGainResponse,
  type MomentumIndustryGroup,
  type PeriodMode,
} from '../api/momentum'
import './MomentumTab.css'

const DEFAULT_DAYS = 20
const DEFAULT_MIN_GAIN = 5
const WEEK_COUNT = 12

const METRICS: { key: Metric; label: string; hint: string }[] = [
  { key: 'AVERAGE', label: '漲幅平均', hint: '期間內每個交易日漲跌幅的平均' },
  { key: 'SUM', label: '漲幅加總', hint: '期間內每個交易日漲跌幅的相加' },
]

function pad2(n: number): string {
  return String(n).padStart(2, '0')
}

function toIsoDate(d: Date): string {
  return `${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())}`
}

function toMdLabel(d: Date): string {
  return `${pad2(d.getMonth() + 1)}/${pad2(d.getDate())}`
}

interface WeekOption {
  index: number
  monday: Date
  sunday: Date
  isCurrentWeek: boolean
}

/** Monday of the natural (週一至週日) week containing `d`. */
function startOfWeekMonday(d: Date): Date {
  const day = d.getDay() // 0=Sun..6=Sat
  const diffFromMonday = (day + 6) % 7
  return new Date(d.getFullYear(), d.getMonth(), d.getDate() - diffFromMonday)
}

/** Most recent `WEEK_COUNT` natural weeks, newest first (index 0 = this week). */
function buildWeekOptions(today: Date): WeekOption[] {
  const thisMonday = startOfWeekMonday(today)
  const weeks: WeekOption[] = []
  for (let i = 0; i < WEEK_COUNT; i++) {
    const monday = new Date(thisMonday.getFullYear(), thisMonday.getMonth(), thisMonday.getDate() - 7 * i)
    const sunday = new Date(monday.getFullYear(), monday.getMonth(), monday.getDate() + 6)
    weeks.push({ index: i, monday, sunday, isCurrentWeek: i === 0 })
  }
  return weeks
}

function formatPercent(value: number | null | undefined): string {
  if (value == null) return '—'
  return `${value > 0 ? '+' : ''}${value.toFixed(2)}%`
}

function formatPrice(value: number | null | undefined): string {
  return value == null ? '—' : value.toFixed(2)
}

function gainClass(value: number | null | undefined): string {
  if (value == null || value === 0) return 'sl-flat'
  return value > 0 ? 'sl-up' : 'sl-down'
}

function validateMinGain(raw: string): { value: number | null; error: string | null } {
  if (raw.trim() === '') return { value: null, error: '請輸入 -100 – 1000 之間的數值' }
  const n = Number(raw)
  if (!Number.isFinite(n) || n < -100 || n > 1000) {
    return { value: null, error: '請輸入 -100 – 1000 之間的數值' }
  }
  return { value: n, error: null }
}

function validateDays(raw: string): { value: number | null; error: string | null } {
  if (raw.trim() === '') return { value: null, error: '請輸入 1 – 120 之間的交易日數' }
  const n = Number(raw)
  if (!Number.isInteger(n) || n < 1 || n > 120) {
    return { value: null, error: '請輸入 1 – 120 之間的交易日數' }
  }
  return { value: n, error: null }
}

type QueryStatus = 'idle' | 'loading' | 'success' | 'error'

interface MetricState {
  minGainInput: string
  minGainError: string | null
  status: QueryStatus
  data: MomentumGainResponse | null
  errorMessage: string | null
}

function initialMetricState(): MetricState {
  return {
    minGainInput: String(DEFAULT_MIN_GAIN),
    minGainError: null,
    status: 'idle',
    data: null,
    errorMessage: null,
  }
}

/** 動態分頁（產業別漲幅）— specs/frontend/momentum.md. Mirrors StrategyTab.tsx's structure
 * (condition area above / results area below), mounted as the 3rd tab panel by StockListPage. */
export default function MomentumTab() {
  const navigate = useNavigate()
  const weekOptions = useMemo(() => buildWeekOptions(new Date()), [])

  const [activeMetric, setActiveMetric] = useState<Metric>('AVERAGE')
  const [metricStates, setMetricStates] = useState<Record<Metric, MetricState>>(() => ({
    AVERAGE: initialMetricState(),
    SUM: initialMetricState(),
  }))

  // Period condition is shared across both metric tabs ("期間條件沿用") — only the
  // threshold and results are per-metric.
  const [periodMode, setPeriodMode] = useState<PeriodMode>('DAYS')
  const [daysInput, setDaysInput] = useState(String(DEFAULT_DAYS))
  const [selectedWeeks, setSelectedWeeks] = useState<Set<number>>(() => new Set([0]))

  // Whether the very first 查詢 click has happened yet — before that, switching metric
  // tabs must stay idle (not silently fire a request the user never asked for).
  const everQueriedRef = useRef(false)
  const abortControllersRef = useRef<Record<Metric, AbortController | null>>({ AVERAGE: null, SUM: null })

  useEffect(() => {
    // Capture the ref's (never-reassigned) target object now, rather than reading
    // `.current` again inside the cleanup closure.
    const controllers = abortControllersRef.current
    return () => {
      controllers.AVERAGE?.abort()
      controllers.SUM?.abort()
    }
  }, [])

  const current = metricStates[activeMetric]

  const daysValidation = validateDays(daysInput)
  const minGainValidation = validateMinGain(current.minGainInput)

  const sortedSelectedWeeks = Array.from(selectedWeeks).sort((a, b) => a - b)
  const hasWeekSelection = selectedWeeks.size > 0
  // "最早被勾選那一週的週一" -> "最晚被勾選那一週的週日". Index 0 is the current (newest)
  // week, so the earliest selected week is the *largest* index.
  const weeksRange = hasWeekSelection
    ? (() => {
        const minIndex = sortedSelectedWeeks[0]
        const maxIndex = sortedSelectedWeeks[sortedSelectedWeeks.length - 1]
        const startWeek = weekOptions[maxIndex]
        const endWeek = weekOptions[minIndex]
        return { startDate: toIsoDate(startWeek.monday), endDate: toIsoDate(endWeek.sunday), minIndex, maxIndex }
      })()
    : null

  // Trivial O(≤12) scan — not worth memoizing (KISS), unlike weekOptions which does date math.
  const gapWeekLabels: string[] = []
  if (weeksRange) {
    for (let i = weeksRange.minIndex; i <= weeksRange.maxIndex; i++) {
      if (!selectedWeeks.has(i)) gapWeekLabels.push(toMdLabel(weekOptions[i].monday))
    }
  }

  const canQueryPeriod = periodMode === 'DAYS' ? daysValidation.value != null : hasWeekSelection
  const canQuery = canQueryPeriod && minGainValidation.error == null && current.status !== 'loading'

  const updateMetricState = (metric: Metric, patch: Partial<MetricState>) => {
    setMetricStates((prev) => ({ ...prev, [metric]: { ...prev[metric], ...patch } }))
  }

  const buildParams = (metric: Metric, minGain: number): MomentumGainParams | null => {
    if (periodMode === 'DAYS') {
      if (daysValidation.value == null) return null
      return { metric, mode: 'DAYS', days: daysValidation.value, minGain }
    }
    if (!weeksRange) return null
    return { metric, mode: 'WEEKS', startDate: weeksRange.startDate, endDate: weeksRange.endDate, minGain }
  }

  const runQuery = (metric: Metric, overrideMinGainInput?: string) => {
    const minGainInput = overrideMinGainInput ?? metricStates[metric].minGainInput
    const { value: minGain, error: minGainError } = validateMinGain(minGainInput)
    if (minGainError || minGain == null) {
      updateMetricState(metric, { minGainInput, minGainError })
      return
    }
    const params = buildParams(metric, minGain)
    if (!params) return

    abortControllersRef.current[metric]?.abort()
    const controller = new AbortController()
    abortControllersRef.current[metric] = controller

    everQueriedRef.current = true
    updateMetricState(metric, { status: 'loading', minGainError: null, minGainInput })

    fetchMomentumGain(params, controller.signal)
      .then((resp) => {
        updateMetricState(metric, { status: 'success', data: resp, errorMessage: null })
      })
      .catch((err: unknown) => {
        if (err instanceof DOMException && err.name === 'AbortError') return
        let message = '查詢失敗，請稍後再試'
        if (err instanceof ApiError) {
          if (err.code === 'INVALID_DAYS') message = '請輸入 1 – 120 之間的交易日數'
          else if (err.code === 'INVALID_DATE_RANGE') message = '所選週的區間無效'
          else if (err.code === 'INVALID_MIN_GAIN') message = '請輸入 -100 – 1000 之間的數值'
          else if (err.code === 'INVALID_METRIC' || err.code === 'INVALID_MODE') {
            message = '發生非預期的錯誤，請重新整理頁面'
          }
        }
        updateMetricState(metric, { status: 'error', errorMessage: message })
      })
  }

  const handleMetricTabClick = (metric: Metric) => {
    if (metric === activeMetric) return
    setActiveMetric(metric)
    const state = metricStates[metric]
    if (state.status === 'success' || state.status === 'loading') return // has results, or already in flight
    if (!everQueriedRef.current) return // page never queried yet — stay idle
    runQuery(metric)
  }

  const handleQueryClick = () => {
    if (current.status === 'loading') return
    if (!canQueryPeriod) return
    runQuery(activeMetric)
  }

  const handleRetryClick = () => {
    if (current.status === 'loading') return
    runQuery(activeMetric)
  }

  const handleLowerThreshold = () => {
    const { value } = validateMinGain(current.minGainInput)
    if (value == null) return
    const halved = Math.round((value / 2) * 100) / 100
    runQuery(activeMetric, String(halved))
  }

  const toggleWeek = (index: number) => {
    setSelectedWeeks((prev) => {
      const next = new Set(prev)
      if (next.has(index)) next.delete(index)
      else next.add(index)
      return next
    })
  }

  const handleMinGainChange = (value: string) => {
    updateMetricState(activeMetric, { minGainInput: value, minGainError: validateMinGain(value).error })
  }

  const onlyUnclassified = (data: MomentumGainResponse) => {
    const industries = data.industries ?? []
    return industries.length === 1 && industries[0]?.industryId === null
  }

  const renderSummary = (data: MomentumGainResponse) => {
    if (data.startDate == null || data.endDate == null) {
      return <div className="mt-summary">所選期間內沒有交易日</div>
    }
    const insufficientCount = data.insufficientDataCount ?? 0
    return (
      <div className="mt-summary">
        區間 {data.startDate} ~ {data.endDate}（{data.tradingDays} 個交易日）・命中{' '}
        <span className="mt-summary-strong">{data.matchedStockCount}</span> 檔
        {insufficientCount > 0 ? (
          <>
            ・
            <span className="mt-insufficient-note" title="期間開始前沒有行情，無法計算漲幅">
              資料不足 {insufficientCount} 檔
            </span>
          </>
        ) : null}
      </div>
    )
  }

  const renderIndustryBlock = (group: MomentumIndustryGroup, marketWideTradingDays: number) => {
    const items = group.items ?? []
    return (
      <div className="mt-industry-block" key={group.industryId ?? 'unclassified'}>
        <h3 className="mt-industry-title">
          <span className={group.industryId === null ? 'mt-unclassified-name' : undefined}>{group.industryName}</span>
          <span className="mt-industry-count">　{group.matchedCount} 檔</span>
        </h3>
        <table className="sl-table mt-industry-table">
          <thead>
            <tr>
              <th>代號</th>
              <th>名稱</th>
              <th className="sl-r">漲幅</th>
              <th className="sl-r">交易日</th>
              <th className="sl-r">期初收盤</th>
              <th className="sl-r">期末收盤</th>
            </tr>
          </thead>
          <tbody>
            {items.map((item) => {
              const hasSuspension = item.tradingDays < marketWideTradingDays
              return (
                <tr
                  key={`${group.industryId ?? 'na'}-${item.stockId}`}
                  className="sl-row"
                  onClick={() => navigate(`/stocks/${item.stockId}/daily`)}
                >
                  <td className="sl-code">{item.stockId}</td>
                  <td>{item.stockName}</td>
                  <td className={`sl-r ${gainClass(item.gain)}`}>{formatPercent(item.gain)}</td>
                  <td className="sl-r">
                    {item.tradingDays}
                    {hasSuspension ? (
                      <span
                        className="mt-suspension-mark"
                        title={`期間內有停牌（實際交易區間 ${item.firstTradeDate} ~ ${item.lastTradeDate}）`}
                      >
                        {' '}
                        ＊
                      </span>
                    ) : null}
                  </td>
                  <td className="sl-r">{formatPrice(item.startClose)}</td>
                  <td className="sl-r">{formatPrice(item.endClose)}</td>
                </tr>
              )
            })}
          </tbody>
        </table>
      </div>
    )
  }

  const renderResults = () => {
    const { status, data, errorMessage } = current

    if (status === 'error') {
      return (
        <div className="mt-results-error">
          <p>{errorMessage}</p>
          <button type="button" className="sl-btn sl-btn-primary" onClick={handleRetryClick}>
            重新查詢
          </button>
        </div>
      )
    }

    if (status === 'idle') {
      return (
        <div className="mt-results-placeholder">
          <p>設定期間與門檻後按下查詢</p>
        </div>
      )
    }

    if (status === 'loading' && !data) {
      return (
        <div className="mt-skeleton-list">
          {[0, 1, 2].map((i) => (
            <div className="mt-skeleton-block" key={i}>
              <div className="sl-skeleton-bar mt-skeleton-title" />
              <div className="sl-skeleton-bar" />
              <div className="sl-skeleton-bar" />
              <div className="sl-skeleton-bar" />
            </div>
          ))}
        </div>
      )
    }

    if (!data) return null

    const dimmed = status === 'loading'
    const industries = data.industries ?? []

    let body: React.ReactNode
    if (data.startDate == null) {
      body = (
        <div className="mt-results-placeholder">
          <p>請改選其他週</p>
        </div>
      )
    } else if (data.matchedStockCount === 0) {
      body = (
        <div className="mt-results-zero">
          <p>這段期間沒有股票的漲幅達到 {data.minGain.toFixed(2)}%</p>
          <button type="button" className="sl-btn" onClick={handleLowerThreshold}>
            把門檻調低
          </button>
        </div>
      )
    } else {
      body = (
        <div className="mt-results-list">
          {onlyUnclassified(data) ? (
            <div className="mt-unclassified-guide">
              目前沒有任何股票有產業別資料。請到「策略」分頁按「更新股票清單」，該動作會一併帶入交易所的官方產業別。
            </div>
          ) : null}
          {industries.map((group) => renderIndustryBlock(group, data.tradingDays))}
        </div>
      )
    }

    return (
      <div className={dimmed ? 'mt-results-dimmed' : undefined}>
        {renderSummary(data)}
        {body}
      </div>
    )
  }

  return (
    <div className="momentum-tab">
      <div className="sl-tabs mt-metric-tabs" role="tablist">
        {METRICS.map((m) => (
          <button
            key={m.key}
            type="button"
            role="tab"
            aria-selected={activeMetric === m.key}
            className={`sl-tab${activeMetric === m.key ? ' sl-tab-active' : ''}`}
            onClick={() => handleMetricTabClick(m.key)}
          >
            {m.label}
          </button>
        ))}
      </div>
      <div className="mt-metric-hint">{METRICS.find((m) => m.key === activeMetric)?.hint}</div>

      <div className="mt-condition">
        <div className="mt-section-label">期間</div>
        <div className="mt-period-modes">
          <button
            type="button"
            className={`mt-mode-btn${periodMode === 'DAYS' ? ' mt-mode-btn-active' : ''}`}
            onClick={() => setPeriodMode('DAYS')}
          >
            近 N 交易日
          </button>
          <button
            type="button"
            className={`mt-mode-btn${periodMode === 'WEEKS' ? ' mt-mode-btn-active' : ''}`}
            onClick={() => setPeriodMode('WEEKS')}
          >
            指定週
          </button>
        </div>

        {periodMode === 'DAYS' ? (
          <div className="mt-days-row">
            <label className="mt-days-label">
              近{' '}
              <input
                type="number"
                className="mt-num-input"
                min={1}
                max={120}
                value={daysInput}
                onChange={(e) => setDaysInput(e.target.value)}
              />{' '}
              個交易日
            </label>
            {daysValidation.error ? <div className="mt-field-error">{daysValidation.error}</div> : null}
          </div>
        ) : (
          <div className="mt-weeks">
            <div className="mt-week-list">
              {weekOptions.map((w) => (
                <label key={w.index} className="mt-week-item">
                  <input type="checkbox" checked={selectedWeeks.has(w.index)} onChange={() => toggleWeek(w.index)} />
                  {toMdLabel(w.monday)} – {toMdLabel(w.sunday)}
                  {w.isCurrentWeek ? <span className="mt-current-week-mark">本週</span> : null}
                </label>
              ))}
            </div>
            {!hasWeekSelection ? <div className="mt-field-hint">請至少勾選一週</div> : null}
            {hasWeekSelection && gapWeekLabels.length > 0 && weeksRange ? (
              <div className="mt-gap-warning">
                已選 {selectedWeeks.size} 週，實際計算區間 {weeksRange.startDate} ~ {weeksRange.endDate}
                ，含未勾選的 {gapWeekLabels.join('、')} {gapWeekLabels.length > 1 ? '這幾週' : '那一週'}
              </div>
            ) : null}
          </div>
        )}

        <div className="mt-section-label">漲幅門檻</div>
        <div className="mt-mingain-row">
          <label className="mt-mingain-label">
            漲幅 ≥{' '}
            <input
              type="number"
              className="mt-num-input"
              min={-100}
              max={1000}
              value={current.minGainInput}
              onChange={(e) => handleMinGainChange(e.target.value)}
            />{' '}
            %
          </label>
          {current.minGainError ? <div className="mt-field-error">{current.minGainError}</div> : null}
        </div>

        <div className="mt-query-row">
          <button type="button" className="sl-btn sl-btn-primary" disabled={!canQuery} onClick={handleQueryClick}>
            {current.status === 'loading' ? '查詢中…' : '查詢'}
          </button>
          {periodMode === 'WEEKS' && !hasWeekSelection ? <span className="mt-field-hint">請至少勾選一週</span> : null}
        </div>
      </div>

      <div className="mt-results">{renderResults()}</div>
    </div>
  )
}
