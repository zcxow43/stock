import { useCallback, useMemo, useRef, useState, type MouseEvent, type ReactNode } from 'react'
import './KLineChart.css'

/**
 * Shared candlestick chart: the daily-K page (specs/frontend/stock-daily-chart.md) and the
 * minute-K page (specs/frontend/stock-minute-chart.md) both draw through this component —
 * only the bar labels (date vs time) and the subplot list differ (3 subplots vs volume-only).
 *
 * X axis is a category axis: `bars` is drawn as an evenly spaced sequence with no gaps for
 * missing calendar days (weekends / trading halts never appear as columns at all).
 */

export interface CandleBar {
  /** X-axis category label, e.g. "2026-08-25" for daily bars or "09:35" for minute bars. */
  label: string
  open: number
  high: number
  low: number
  close: number
}

export type LineSeriesSpec = {
  kind: 'line'
  key: string
  color: string
  values: Array<number | null>
}

export type BarSeriesSpec = {
  kind: 'bars'
  key: string
  values: Array<number | null>
  colorFor: (index: number) => string
}

export type SeriesSpec = LineSeriesSpec | BarSeriesSpec

export interface SubplotSpec {
  id: string
  title: string
  /** Panel height in SVG viewBox units. */
  height: number
  series: SeriesSpec[]
  /** Horizontal reference lines drawn in this panel's own value space (e.g. MACD's 0, KD's 20/80). */
  referenceValues?: number[]
  /** Y-axis tick label formatter; defaults to 2-decimal fixed. */
  yTickFormat?: (value: number) => string
  /** When true, no series is drawn — a centered message is shown instead (indicator not yet computed). */
  unavailable?: boolean
  unavailableMessage?: string
}

export interface KLineChartProps {
  bars: CandleBar[]
  priceHeight?: number
  subplots: SubplotSpec[]
  pinnedIndex: number | null
  /** Fired on every click on the chart area — toggling pin-on-same-bar is the caller's responsibility. */
  onBarClick: (index: number) => void
  /** Fired on native browser dblclick — never hand-rolled from two click events (see spec). */
  onBarDoubleClick: (index: number) => void
  renderTooltip: (index: number) => ReactNode
  upColor: string
  downColor: string
  gridColor: string
  axisTextColor: string
  referenceLineColor: string
  crosshairColor: string
  pinnedOutlineColor: string
  axisLabelBg: string
  axisLabelText: string
  unavailableTextColor: string
  maxXTicks?: number
  /** When it returns true for a given bar label, that X-axis tick is rendered bold (e.g. the
   * minute chart's on-the-hour / on-the-half-hour labels). Unset for the daily chart. */
  boldXTick?: (label: string) => boolean
}

const VIEW_WIDTH = 1200
const LEFT_MARGIN = 8
const RIGHT_MARGIN = 68
const BOTTOM_AXIS_HEIGHT = 26
const PANEL_GAP = 4

function computeDomain(
  valueArrays: Array<Array<number | null>>,
  refs: number[] = [],
  paddingRatio = 0.08,
): [number, number] {
  let min = Infinity
  let max = -Infinity
  for (const arr of valueArrays) {
    for (const v of arr) {
      if (v == null) continue
      if (v < min) min = v
      if (v > max) max = v
    }
  }
  for (const r of refs) {
    if (r < min) min = r
    if (r > max) max = r
  }
  if (!isFinite(min) || !isFinite(max)) {
    min = 0
    max = 1
  }
  if (min === max) {
    min -= 1
    max += 1
  }
  const pad = (max - min) * paddingRatio
  return [min - pad, max + pad]
}

function buildLinePath(
  values: Array<number | null>,
  xFor: (i: number) => number,
  yFor: (v: number) => number,
): string {
  let d = ''
  let drawing = false
  values.forEach((v, i) => {
    if (v == null) {
      drawing = false
      return
    }
    d += `${drawing ? 'L' : 'M'}${xFor(i).toFixed(1)} ${yFor(v).toFixed(1)} `
    drawing = true
  })
  return d.trim()
}

export default function KLineChart({
  bars,
  priceHeight = 260,
  subplots,
  pinnedIndex,
  onBarClick,
  onBarDoubleClick,
  renderTooltip,
  upColor,
  downColor,
  gridColor,
  axisTextColor,
  referenceLineColor,
  crosshairColor,
  pinnedOutlineColor,
  axisLabelBg,
  axisLabelText,
  unavailableTextColor,
  maxXTicks = 10,
  boldXTick,
}: KLineChartProps) {
  const svgRef = useRef<SVGSVGElement>(null)
  const [hoverIndex, setHoverIndex] = useState<number | null>(null)

  const n = bars.length
  const chartWidth = VIEW_WIDTH - LEFT_MARGIN - RIGHT_MARGIN
  const cw = n > 0 ? chartWidth / n : 0
  const bw = Math.max(2, cw * 0.62)
  const xFor = useCallback((i: number) => LEFT_MARGIN + cw * i + cw / 2, [cw])

  // Panel vertical layout: price panel first, then each subplot stacked below.
  // `bottom` is the Y just past the last panel's content (before its trailing gap) — the
  // previous version conflated this with the last panel's *top*, which clipped the final
  // subplot (its content extended past the computed totalHeight).
  const { panelTops, bottom } = useMemo(() => {
    const tops: number[] = []
    let top = 0
    tops.push(top)
    top += priceHeight + PANEL_GAP
    for (const sp of subplots) {
      tops.push(top)
      top += sp.height + PANEL_GAP
    }
    return { panelTops: tops, bottom: top - PANEL_GAP }
  }, [priceHeight, subplots])

  const totalHeight = bottom + BOTTOM_AXIS_HEIGHT
  const priceTop = panelTops[0]
  const lastPanelBottom = bottom

  const [priceLo, priceHi] = useMemo(
    () => computeDomain([bars.map((b) => b.high), bars.map((b) => b.low)], [], 0.05),
    [bars],
  )
  const yForPrice = useCallback(
    (v: number) => priceTop + priceHeight - ((v - priceLo) / (priceHi - priceLo)) * priceHeight,
    [priceTop, priceHeight, priceLo, priceHi],
  )

  const activeIndex = pinnedIndex ?? hoverIndex

  const indexFromClientX = useCallback(
    (clientX: number): number => {
      const svg = svgRef.current
      if (!svg || n === 0) return 0
      const rect = svg.getBoundingClientRect()
      const scaleX = VIEW_WIDTH / rect.width
      const vx = (clientX - rect.left) * scaleX
      const idx = Math.floor((vx - LEFT_MARGIN) / cw)
      return Math.min(n - 1, Math.max(0, idx))
    },
    [cw, n],
  )

  const handleMouseMove = (e: MouseEvent) => {
    if (pinnedIndex != null || n === 0) return
    setHoverIndex(indexFromClientX(e.clientX))
  }
  const handleMouseLeave = () => {
    if (pinnedIndex == null) setHoverIndex(null)
  }
  const handleClick = (e: MouseEvent) => {
    if (n === 0) return
    onBarClick(indexFromClientX(e.clientX))
  }
  const handleDoubleClick = (e: MouseEvent) => {
    if (n === 0) return
    e.preventDefault()
    onBarDoubleClick(indexFromClientX(e.clientX))
  }

  // Evenly-spaced X tick indices so labels never crowd regardless of range length.
  const xTickIndices = useMemo(() => {
    if (n === 0) return []
    const step = Math.max(1, Math.ceil(n / maxXTicks))
    const idxs: number[] = []
    for (let i = 0; i < n; i += step) idxs.push(i)
    const last = idxs[idxs.length - 1]
    if (last !== n - 1) {
      // Append the final index, but if it would land right next to the previous tick
      // (crowding/overlapping its label), replace that tick instead of adding a new one.
      if (n - 1 - last < step / 2) idxs[idxs.length - 1] = n - 1
      else idxs.push(n - 1)
    }
    return idxs
  }, [n, maxXTicks])

  if (n === 0) {
    return null
  }

  const active = activeIndex != null ? bars[activeIndex] : null

  // Keep the tooltip from overflowing the right edge of the chart.
  const activeXRatio = activeIndex != null ? xFor(activeIndex) / VIEW_WIDTH : 0
  const tooltipOnLeftSide = activeXRatio > 0.6

  return (
    <div className="kc-root">
      <svg
        ref={svgRef}
        className="kc-svg"
        viewBox={`0 0 ${VIEW_WIDTH} ${totalHeight}`}
        width="100%"
        height={totalHeight}
        onMouseMove={handleMouseMove}
        onMouseLeave={handleMouseLeave}
        onClick={handleClick}
        onDoubleClick={handleDoubleClick}
        role="img"
        aria-label="K 線圖"
      >
        {/* ---------- price panel ---------- */}
        <g>
          <rect
            x={LEFT_MARGIN}
            y={priceTop}
            width={chartWidth}
            height={priceHeight}
            fill="none"
            stroke={gridColor}
          />
          {[0, 1, 2, 3, 4].map((g) => {
            const v = priceLo + ((priceHi - priceLo) * g) / 4
            const y = yForPrice(v)
            return (
              <g key={g}>
                {g > 0 && g < 4 && (
                  <line
                    x1={LEFT_MARGIN}
                    y1={y}
                    x2={LEFT_MARGIN + chartWidth}
                    y2={y}
                    stroke={gridColor}
                    strokeDasharray="2 4"
                  />
                )}
                <text x={LEFT_MARGIN + chartWidth + 8} y={y + 4} fill={axisTextColor} fontSize={11}>
                  {v.toFixed(2)}
                </text>
              </g>
            )
          })}
          {bars.map((b, i) => {
            const col = b.close >= b.open ? upColor : downColor
            const yo = yForPrice(b.open)
            const yc = yForPrice(b.close)
            const yh = yForPrice(b.high)
            const yl = yForPrice(b.low)
            const isPinned = pinnedIndex === i
            return (
              <g key={i}>
                <line x1={xFor(i)} y1={yh} x2={xFor(i)} y2={yl} stroke={col} strokeWidth={1.3} />
                <rect
                  x={xFor(i) - bw / 2}
                  y={Math.min(yo, yc)}
                  width={bw}
                  height={Math.max(1.4, Math.abs(yc - yo))}
                  fill={col}
                />
                {isPinned && (
                  <rect
                    x={xFor(i) - bw / 2 - 2}
                    y={yh - 3}
                    width={bw + 4}
                    height={yl - yh + 6}
                    fill="none"
                    stroke={pinnedOutlineColor}
                    strokeWidth={1.5}
                  />
                )}
              </g>
            )
          })}
        </g>

        {/* ---------- subplots ---------- */}
        {subplots.map((sp, spIdx) => {
          const top = panelTops[spIdx + 1]
          const allValues = sp.series.map((s) => s.values)
          // Bar subplots (volume, MACD osc) always draw from a zero baseline, so the domain
          // must include 0 even if every value happens to sit on one side of it — otherwise the
          // baseline itself would fall outside the panel.
          const domainRefs = sp.series.some((s) => s.kind === 'bars')
            ? [...(sp.referenceValues ?? []), 0]
            : (sp.referenceValues ?? [])
          const [lo, hi] = computeDomain(allValues, domainRefs, 0.12)
          const yFor = (v: number) => top + sp.height - ((v - lo) / (hi - lo)) * sp.height
          const fmt = sp.yTickFormat ?? ((v: number) => v.toFixed(2))
          return (
            <g key={sp.id}>
              <rect x={LEFT_MARGIN} y={top} width={chartWidth} height={sp.height} fill="none" stroke={gridColor} />
              <text x={LEFT_MARGIN + 6} y={top + 15} fill={axisTextColor} fontSize={12}>
                {sp.title}
              </text>
              {!sp.unavailable &&
                [0, 1, 2].map((g) => {
                  const v = lo + ((hi - lo) * (g + 1)) / 4
                  const y = yFor(v)
                  return (
                    <text key={g} x={LEFT_MARGIN + chartWidth + 8} y={y + 4} fill={axisTextColor} fontSize={10}>
                      {fmt(v)}
                    </text>
                  )
                })}
              {sp.unavailable ? (
                <text
                  x={LEFT_MARGIN + chartWidth / 2}
                  y={top + sp.height / 2 + 4}
                  fill={unavailableTextColor}
                  fontSize={13}
                  textAnchor="middle"
                >
                  {sp.unavailableMessage ?? '指標尚未運算'}
                </text>
              ) : (
                <>
                  {(sp.referenceValues ?? []).map((rv, i) => (
                    <line
                      key={i}
                      x1={LEFT_MARGIN}
                      y1={yFor(rv)}
                      x2={LEFT_MARGIN + chartWidth}
                      y2={yFor(rv)}
                      stroke={referenceLineColor}
                      strokeDasharray="3 3"
                    />
                  ))}
                  {sp.series.map((s) => {
                    if (s.kind === 'bars') {
                      const zeroY = yFor(0)
                      return (
                        <g key={s.key}>
                          {s.values.map((v, i) => {
                            if (v == null) return null
                            const y1 = yFor(v)
                            return (
                              <rect
                                key={i}
                                x={xFor(i) - bw / 2}
                                y={Math.min(zeroY, y1)}
                                width={bw}
                                height={Math.max(1, Math.abs(y1 - zeroY))}
                                fill={s.colorFor(i)}
                              />
                            )
                          })}
                        </g>
                      )
                    }
                    return (
                      <path
                        key={s.key}
                        d={buildLinePath(s.values, xFor, yFor)}
                        fill="none"
                        stroke={s.color}
                        strokeWidth={1.5}
                      />
                    )
                  })}
                </>
              )}
            </g>
          )
        })}

        {/* ---------- X axis labels ---------- */}
        {xTickIndices.map((i) => (
          <text
            key={i}
            x={xFor(i)}
            y={lastPanelBottom + 18}
            fill={axisTextColor}
            fontSize={11}
            fontWeight={boldXTick?.(bars[i].label) ? 700 : 400}
            textAnchor="middle"
          >
            {bars[i].label}
          </text>
        ))}

        {/* ---------- crosshair ---------- */}
        {active && activeIndex != null && (
          <g>
            <line
              x1={xFor(activeIndex)}
              y1={priceTop}
              x2={xFor(activeIndex)}
              y2={lastPanelBottom}
              stroke={crosshairColor}
              strokeDasharray="4 4"
            />
            <line
              x1={LEFT_MARGIN}
              y1={yForPrice(active.close)}
              x2={LEFT_MARGIN + chartWidth}
              y2={yForPrice(active.close)}
              stroke={crosshairColor}
              strokeDasharray="4 4"
            />
            <rect
              x={LEFT_MARGIN + chartWidth + 2}
              y={yForPrice(active.close) - 9}
              width={64}
              height={18}
              fill={axisLabelBg}
              rx={3}
            />
            <text
              x={LEFT_MARGIN + chartWidth + 34}
              y={yForPrice(active.close) + 4}
              fill={axisLabelText}
              fontSize={11}
              textAnchor="middle"
            >
              {active.close.toFixed(2)}
            </text>
            <rect
              x={xFor(activeIndex) - 42}
              y={lastPanelBottom + 4}
              width={84}
              height={18}
              fill={axisLabelBg}
              rx={3}
            />
            <text
              x={xFor(activeIndex)}
              y={lastPanelBottom + 17}
              fill={axisLabelText}
              fontSize={11}
              textAnchor="middle"
            >
              {bars[activeIndex].label}
            </text>
          </g>
        )}
      </svg>

      {activeIndex != null && (
        <div
          className={`kc-tooltip${tooltipOnLeftSide ? ' kc-tooltip-left' : ''}`}
          style={{ left: `${activeXRatio * 100}%`, top: `${(priceTop / totalHeight) * 100}%` }}
        >
          {renderTooltip(activeIndex)}
        </div>
      )}
    </div>
  )
}
