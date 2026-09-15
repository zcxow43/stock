import { fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
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

// ---------------------------------------------------------------------------------------------
// Mouse -> bar-index alignment on wide screens where the box is wider than the drawing itself.
//
// The `<svg>` is `viewBox="0 0 1200 <totalHeight>"` with `width="100%"` and the default
// `preserveAspectRatio` ("xMidYMid meet"), so on a box whose aspect ratio is wider than
// 1200:totalHeight the drawing is scaled to the box's *height* and letterboxed — centered with
// blank space on both sides (exactly the real 1650px-viewport measurements from the spec: box
// 1561px wide, drawing only 1200px wide starting at x=218). jsdom has no `getScreenCTM`, so
// these tests exercise the fallback that replicates that scaling from `getBoundingClientRect()`
// alone — mirroring how the real browser's `getScreenCTM()` would resolve it.
// ---------------------------------------------------------------------------------------------
describe('KLineChart — mouse-to-bar alignment when the box is wider than the drawing (letterboxed)', () => {
  // Mirrors KLineChart's private layout constants — not exported, so replicated here to compute
  // expected clientX values without reaching into the component's internals.
  const VIEW_WIDTH = 1200
  const LEFT_MARGIN = 8
  const RIGHT_MARGIN = 68
  const PRICE_HEIGHT = 260 // component default when `priceHeight` prop is omitted
  const BOTTOM_AXIS_HEIGHT = 26
  const PANEL_GAP = 4
  // With `subplots: []` (this suite's fixture), totalHeight collapses to just the price panel
  // plus the bottom axis strip.
  const TOTAL_HEIGHT = PRICE_HEIGHT + PANEL_GAP - PANEL_GAP + BOTTOM_AXIS_HEIGHT // = 286

  function viewXForBar(n: number, index: number): number {
    const chartWidth = VIEW_WIDTH - LEFT_MARGIN - RIGHT_MARGIN
    const cw = chartWidth / n
    return LEFT_MARGIN + cw * index + cw / 2
  }

  /** Mocks a letterboxed box: `scale` < the box's own width/VIEW_WIDTH ratio, so the drawing is
   * narrower than the box and centered within it (blank space on both sides). */
  function mockLetterboxedRect(boxLeft: number, boxWidth: number, scale: number): void {
    const drawnWidth = VIEW_WIDTH * scale
    const boxHeight = TOTAL_HEIGHT * scale
    vi.spyOn(SVGElement.prototype, 'getBoundingClientRect').mockReturnValue({
      left: boxLeft,
      top: 0,
      width: boxWidth,
      height: boxHeight,
      right: boxLeft + boxWidth,
      bottom: boxHeight,
      x: boxLeft,
      y: 0,
      toJSON: () => ({}),
    } as DOMRect)
    // sanity guard for the test fixture itself: the box must actually be wider than the drawing
    if (boxWidth <= drawnWidth) throw new Error('test fixture is not letterboxed')
  }

  function clientXFromViewX(boxLeft: number, boxWidth: number, scale: number, viewX: number): number {
    const drawnWidth = VIEW_WIDTH * scale
    const offsetX = boxLeft + (boxWidth - drawnWidth) / 2
    return offsetX + viewX * scale
  }

  afterEach(() => {
    vi.restoreAllMocks()
  })

  const n = 5
  const bars = makeBars([10, 11, 9, 12, 8])

  it.each([
    ['leftmost', 0],
    ['middle', 2],
    ['rightmost', 4],
  ])('hover: pointer over the %s bar reports that bar index, not a neighbor (scale 1, offset 200px each side)', (_name, index) => {
    const boxLeft = 200
    const boxWidth = 1600 // scale constrained by height -> drawn width 1200, centered with 200px margins
    const scale = 1
    mockLetterboxedRect(boxLeft, boxWidth, scale)
    const onHoverIndexChange = vi.fn()
    const { container } = render(
      <KLineChart {...baseProps()} bars={bars} onHoverIndexChange={onHoverIndexChange} />,
    )
    const svg = container.querySelector('svg')!
    const clientX = clientXFromViewX(boxLeft, boxWidth, scale, viewXForBar(n, index as number))
    fireEvent.mouseMove(svg, { clientX, clientY: 100 })
    expect(onHoverIndexChange).toHaveBeenLastCalledWith(index)
  })

  it.each([
    ['leftmost', 0],
    ['middle', 2],
    ['rightmost', 4],
  ])('click-pin: clicking the %s bar pins that bar index, not a neighbor (fractional scale, asymmetric offset)', (_name, index) => {
    const boxLeft = 37
    const boxWidth = 1561 // the spec's real measured 1650px-viewport numbers
    const scale = 0.9 // drawing narrower than 1200 -> still letterboxed
    mockLetterboxedRect(boxLeft, boxWidth, scale)
    const onBarClick = vi.fn()
    const { container } = render(<KLineChart {...baseProps()} bars={bars} onBarClick={onBarClick} />)
    const svg = container.querySelector('svg')!
    const clientX = clientXFromViewX(boxLeft, boxWidth, scale, viewXForBar(n, index as number))
    fireEvent.click(svg, { clientX, clientY: 100 })
    expect(onBarClick).toHaveBeenCalledWith(index)
  })

  it.each([
    ['leftmost', 0],
    ['middle', 2],
    ['rightmost', 4],
  ])('double-click: dbl-clicking the %s bar reports that bar index, not a neighbor', (_name, index) => {
    const boxLeft = 200
    const boxWidth = 1600
    const scale = 1
    mockLetterboxedRect(boxLeft, boxWidth, scale)
    const onBarDoubleClick = vi.fn()
    const { container } = render(
      <KLineChart {...baseProps()} bars={bars} onBarDoubleClick={onBarDoubleClick} />,
    )
    const svg = container.querySelector('svg')!
    const clientX = clientXFromViewX(boxLeft, boxWidth, scale, viewXForBar(n, index as number))
    fireEvent.doubleClick(svg, { clientX, clientY: 100 })
    expect(onBarDoubleClick).toHaveBeenCalledWith(index)
  })

  it('without the fix, the naive box-width-only scaling would have picked a different (wrong) bar for these fixtures', () => {
    // Documents *why* this suite exists: prove the letterboxed fixtures above are not
    // accidentally scale=1-offset=0 in disguise — the naive `VIEW_WIDTH / rect.width` scaling
    // this fix replaced really does disagree with the correct answer here.
    const boxLeft = 37
    const boxWidth = 1561
    const scale = 0.9
    const index = 4 // rightmost
    const clientX = clientXFromViewX(boxLeft, boxWidth, scale, viewXForBar(n, index))
    const naiveScaleX = VIEW_WIDTH / boxWidth
    const naiveViewX = (clientX - boxLeft) * naiveScaleX
    const chartWidth = VIEW_WIDTH - LEFT_MARGIN - RIGHT_MARGIN
    const cw = chartWidth / n
    const naiveIndex = Math.min(n - 1, Math.max(0, Math.floor((naiveViewX - LEFT_MARGIN) / cw)))
    expect(naiveIndex).not.toBe(index)
  })
})
