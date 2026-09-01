import { render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import KLineChart, { type CandleBar, type SubplotSpec } from '../components/KLineChart'

const COLORS = {
  up: '#E04B45',
  down: '#16A75C',
  grid: '#1F2C3A',
  text: '#93A4B8',
  ref: '#3A4757',
  crosshair: '#6B7C90',
  pinnedOutline: '#3E8FD8',
  axisLabelBg: '#3A4757',
  axisLabelText: '#E6EDF5',
  unavailableText: '#6B7C90',
  line: '#3E8FD8',
  openBaseline: '#4A5866',
}

function makeBars(closes: number[]): CandleBar[] {
  return closes.map((close, i) => ({
    label: `09:${String(i).padStart(2, '0')}`,
    open: close,
    high: close + 1,
    low: close - 1,
    close,
  }))
}

const noopSubplots: SubplotSpec[] = []

function baseProps() {
  return {
    subplots: noopSubplots,
    pinnedIndex: null,
    onBarClick: vi.fn(),
    onBarDoubleClick: vi.fn(),
    renderTooltip: () => null,
    upColor: COLORS.up,
    downColor: COLORS.down,
    gridColor: COLORS.grid,
    axisTextColor: COLORS.text,
    referenceLineColor: COLORS.ref,
    crosshairColor: COLORS.crosshair,
    pinnedOutlineColor: COLORS.pinnedOutline,
    axisLabelBg: COLORS.axisLabelBg,
    axisLabelText: COLORS.axisLabelText,
    unavailableTextColor: COLORS.unavailableText,
  }
}

describe('KLineChart — mainType parameter (candle vs line)', () => {
  it('defaults to candle mode: draws a red/green candle rect per bar (daily-K unaffected)', () => {
    const bars = makeBars([10, 9]) // bar0 close(10) >= open(10) -> up; bar1 close(9) < open(9)? open=close so equal -> up too
    // Use distinct open/close to actually exercise both colors.
    bars[0].open = 8 // close 10 >= open 8 -> up (red)
    bars[1].open = 11 // close 9 < open 11 -> down (green)
    const { container } = render(<KLineChart {...baseProps()} bars={bars} />)
    const svg = screen.getByRole('img', { name: 'K 線圖' })
    expect(svg).toBeTruthy()
    const upRects = container.querySelectorAll(`rect[fill="${COLORS.up}"]`)
    const downRects = container.querySelectorAll(`rect[fill="${COLORS.down}"]`)
    expect(upRects.length).toBe(1)
    expect(downRects.length).toBe(1)
    // No line path should be drawn for the main series in candle mode.
    const paths = container.querySelectorAll(`path[stroke="${COLORS.line}"]`)
    expect(paths.length).toBe(0)
  })

  it('line mode: draws a single-color close-price line and no candle bodies at all', () => {
    const bars = makeBars([10, 9, 12]) // mixed up/down closes — must NOT produce red/green rects
    const { container } = render(
      <KLineChart {...baseProps()} bars={bars} mainType="line" lineColor={COLORS.line} />,
    )
    const upRects = container.querySelectorAll(`rect[fill="${COLORS.up}"]`)
    const downRects = container.querySelectorAll(`rect[fill="${COLORS.down}"]`)
    expect(upRects.length).toBe(0)
    expect(downRects.length).toBe(0)
    const linePaths = container.querySelectorAll(`path[stroke="${COLORS.line}"]`)
    expect(linePaths.length).toBe(1)
    // Path must be built solely from `close` — 3 bars => 3 path commands (M + 2 L).
    const d = linePaths[0].getAttribute('d') ?? ''
    expect(d.startsWith('M')).toBe(true)
    expect((d.match(/L/g) ?? []).length).toBe(2)
  })
})

describe('KLineChart — priceReferenceLines folded into Y-axis domain', () => {
  it('renders a reference line at the given value and color', () => {
    const bars = makeBars([100, 101, 99])
    const { container } = render(
      <KLineChart
        {...baseProps()}
        bars={bars}
        priceReferenceLines={[{ value: 105, color: COLORS.openBaseline }]}
      />,
    )
    const refLines = container.querySelectorAll(`line[stroke="${COLORS.openBaseline}"]`)
    expect(refLines.length).toBe(1)
  })

  it('keeps the reference line inside the visible Y range even when every close sits below it', () => {
    // All bars sit well below the reference value (simulating "today's close is under the
    // day's open" for the whole session) — the baseline must still land within the price
    // panel's pixel bounds, not be clipped off the top of the chart.
    const bars = makeBars([50, 51, 49, 52])
    const priceHeight = 260
    const { container } = render(
      <KLineChart
        {...baseProps()}
        bars={bars}
        priceHeight={priceHeight}
        priceReferenceLines={[{ value: 500, color: COLORS.openBaseline, label: '500.00' }]}
      />,
    )
    const refLine = container.querySelector(`line[stroke="${COLORS.openBaseline}"]`)
    expect(refLine).toBeTruthy()
    const y1 = Number(refLine?.getAttribute('y1'))
    // Price panel top is 0 in viewBox coordinates for the first (and only) panel here.
    expect(y1).toBeGreaterThanOrEqual(0)
    expect(y1).toBeLessThanOrEqual(priceHeight)
    // The label must be rendered too, so the axis actually annotates the reference price.
    expect(screen.getByText('500.00')).toBeTruthy()
  })

  it('keeps the reference line inside the visible Y range even when every close sits above it', () => {
    const bars = makeBars([500, 501, 499, 502])
    const priceHeight = 260
    const { container } = render(
      <KLineChart
        {...baseProps()}
        bars={bars}
        priceHeight={priceHeight}
        priceReferenceLines={[{ value: 50, color: COLORS.openBaseline }]}
      />,
    )
    const refLine = container.querySelector(`line[stroke="${COLORS.openBaseline}"]`)
    const y1 = Number(refLine?.getAttribute('y1'))
    expect(y1).toBeGreaterThanOrEqual(0)
    expect(y1).toBeLessThanOrEqual(priceHeight)
  })
})

describe('KLineChart — daily-K page behavior is unchanged (regression)', () => {
  it('with no mainType/lineColor/priceReferenceLines passed, renders exactly as the pre-refactor candlestick component did', () => {
    const bars = makeBars([10, 9])
    bars[0].open = 8
    bars[1].open = 11
    const { container } = render(<KLineChart {...baseProps()} bars={bars} />)
    expect(container.querySelectorAll(`rect[fill="${COLORS.up}"]`).length).toBe(1)
    expect(container.querySelectorAll(`rect[fill="${COLORS.down}"]`).length).toBe(1)
    expect(container.querySelectorAll('line[stroke-dasharray="4 4"]').length).toBe(0) // no price reference lines
  })
})
