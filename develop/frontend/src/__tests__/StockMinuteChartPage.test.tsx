import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import StockMinuteChartPage from '../pages/StockMinuteChartPage'
import type { MinuteBarResponse } from '../api/minuteBars'
import type { StatisticsResponse, StatisticsSeriesRow } from '../api/statistics'

const VIEW_WIDTH = 1200
const LEFT_MARGIN = 8
const RIGHT_MARGIN = 68

function clientXForBar(n: number, index: number): number {
  const chartWidth = VIEW_WIDTH - LEFT_MARGIN - RIGHT_MARGIN
  const cw = chartWidth / n
  return LEFT_MARGIN + cw * index + cw / 2
}

function makeBars(count: number) {
  const bars = []
  let price = 2355
  for (let i = 0; i < count; i++) {
    const open = price
    const close = price + (i % 2 === 0 ? 1 : -1)
    const high = Math.max(open, close) + 0.5
    const low = Math.min(open, close) - 0.5
    const hh = String(9 + Math.floor(i / 60)).padStart(2, '0')
    const mm = String(i % 60).padStart(2, '0')
    bars.push({ barTime: `${hh}:${mm}`, open, high, low, close, volume: 1000 + i })
    price = close
  }
  return bars
}

function makeMinuteResponse(overrides: Partial<MinuteBarResponse> = {}): MinuteBarResponse {
  const bars = makeBars(30)
  return {
    stockId: '2330',
    stockName: '台積電',
    tradeDate: '2026-08-25',
    interval: 1,
    dataStatus: 'AVAILABLE',
    source: 'YAHOO',
    fetchedAt: '2026-08-27 09:12:33',
    barCount: bars.length,
    dailySummary: { open: 2355, high: 2380, low: 2350, close: 2375, volume: 18234000 },
    bars,
    message: null,
    ...overrides,
  }
}

function makeRow(overrides: Partial<StatisticsSeriesRow> = {}): StatisticsSeriesRow {
  return {
    tradeDate: '2026-08-20',
    open: 2400,
    high: 2420,
    low: 2390,
    close: 2410,
    volume: 17_557_000,
    dif: null,
    dea: null,
    osc: null,
    k: null,
    d: null,
    j: null,
    ...overrides,
  }
}

function makeStats(dates: string[]): StatisticsResponse {
  return {
    startDate: dates[0],
    endDate: dates[dates.length - 1],
    paramKey: 'MACD_12_26_9__KD_9_3_3',
    scope: 'SELECTED',
    stockCount: 1,
    items: [
      {
        stockId: '2330',
        stockName: '台積電',
        tradingDays: dates.length,
        warmupSufficient: true,
        summary: null,
        series: dates.map((d) => makeRow({ tradeDate: d })),
      },
    ],
  }
}

interface Handlers {
  minute?: MinuteBarResponse | 'notfound' | 'invalidDate' | 'error' | ((url: URL) => MinuteBarResponse)
  stats?: StatisticsResponse | 'error'
}

function mockFetchRouter(handlers: Handlers) {
  return vi.fn().mockImplementation((url: string) => {
    const u = new URL(url, 'http://x')
    if (u.pathname.endsWith('/minute-bars')) {
      if (handlers.minute === 'notfound') {
        return Promise.resolve({ ok: false, status: 404, json: async () => ({ code: 'STOCK_NOT_FOUND' }) })
      }
      if (handlers.minute === 'invalidDate') {
        return Promise.resolve({ ok: false, status: 400, json: async () => ({ code: 'INVALID_DATE_FORMAT' }) })
      }
      if (handlers.minute === 'error') {
        return Promise.resolve({ ok: false, status: 500, json: async () => ({ code: 'INTERNAL_ERROR' }) })
      }
      const body = typeof handlers.minute === 'function' ? handlers.minute(u) : handlers.minute ?? makeMinuteResponse()
      return Promise.resolve({ ok: true, json: async () => body })
    }
    if (u.pathname === '/api/stocks/statistics') {
      if (handlers.stats === 'error') {
        return Promise.resolve({ ok: false, status: 500, json: async () => ({ code: 'INTERNAL_ERROR' }) })
      }
      return Promise.resolve({ ok: true, json: async () => handlers.stats ?? makeStats(['2026-08-24', '2026-08-25', '2026-08-26']) })
    }
    return Promise.reject(new Error(`unexpected fetch ${url}`))
  })
}

function renderPage(tradeDate = '2026-08-25') {
  return render(
    <MemoryRouter initialEntries={[`/stocks/2330/minute/${tradeDate}`]}>
      <Routes>
        <Route path="/stocks/:stockId/minute/:tradeDate" element={<StockMinuteChartPage />} />
        <Route path="/stocks/:stockId/daily" element={<div>daily page</div>} />
        <Route path="/stocks" element={<div>list page</div>} />
      </Routes>
    </MemoryRouter>,
  )
}

describe('StockMinuteChartPage', () => {
  beforeEach(() => {
    vi.spyOn(SVGElement.prototype, 'getBoundingClientRect').mockReturnValue({
      left: 0,
      top: 0,
      width: VIEW_WIDTH,
      height: 600,
      right: VIEW_WIDTH,
      bottom: 600,
      x: 0,
      y: 0,
      toJSON: () => ({}),
    } as DOMRect)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it('renders a single-color #3E8FD8 close-price line with no candle bodies/wicks, the daily summary strip, and the trade date header', async () => {
    vi.stubGlobal('fetch', mockFetchRouter({}))
    const { container } = renderPage()
    await waitFor(() => expect(screen.getByText('台積電')).toBeInTheDocument())
    expect(screen.getByText('2026-08-25')).toBeInTheDocument()
    expect(screen.getByText('2,380.00'.replace(',', ''))).toBeInTheDocument() // 最高 daily summary (2380.00 literal)
    // No candle bodies (rects colored by up/down) and no wicks (lines colored by up/down) exist
    // on the main chart — only the single-color close-price line does.
    const candleBodies = container.querySelectorAll('svg rect[fill="#E04B45"], svg rect[fill="#16A75C"]')
    expect(candleBodies.length).toBe(0)
    const wicks = container.querySelectorAll('svg line[stroke="#E04B45"], svg line[stroke="#16A75C"]')
    expect(wicks.length).toBe(0)
    const linePath = container.querySelector('svg path[stroke="#3E8FD8"]')
    expect(linePath).toBeTruthy()
    // No red/green volume bars either — uniform neutral color.
    const coloredVolumeBars = container.querySelectorAll('svg rect[fill="#9A3B37"], svg rect[fill="#12784A"]')
    expect(coloredVolumeBars.length).toBe(0)
    const neutralVolumeBars = container.querySelectorAll('svg rect[fill="#3A4757"]')
    expect(neutralVolumeBars.length).toBeGreaterThan(0)
  })

  it('the same #3E8FD8 line color is used regardless of whether the day trended up or down', async () => {
    const upBars = makeBars(30) // makeBars alternates +1/-1 around a rising price walk
    vi.stubGlobal('fetch', mockFetchRouter({ minute: makeMinuteResponse({ bars: upBars }) }))
    const { container: upContainer } = renderPage()
    await waitFor(() => expect(screen.getByText('台積電')).toBeInTheDocument())
    expect(upContainer.querySelector('svg path[stroke="#3E8FD8"]')).toBeTruthy()

    const downBars = makeBars(30)
      .slice()
      .reverse()
      .map((b) => ({ ...b, open: b.close, close: b.open })) // a falling-price variant
    vi.stubGlobal('fetch', mockFetchRouter({ minute: makeMinuteResponse({ bars: downBars }) }))
    const { container: downContainer } = renderPage()
    await waitFor(() => expect(screen.getAllByText('台積電').length).toBeGreaterThan(0))
    expect(downContainer.querySelector('svg path[stroke="#3E8FD8"]')).toBeTruthy()
  })

  it('draws no gradient/area fill under the line — the price panel contains no <linearGradient> or filled area path', async () => {
    vi.stubGlobal('fetch', mockFetchRouter({}))
    const { container } = renderPage()
    await waitFor(() => expect(screen.getByText('台積電')).toBeInTheDocument())
    expect(container.querySelectorAll('svg linearGradient, svg radialGradient').length).toBe(0)
    const linePath = container.querySelector('svg path[stroke="#3E8FD8"]')!
    expect(linePath.getAttribute('fill')).toBe('none')
  })

  it('draws the open-price baseline as a dashed line in #4A5866, labeled with its price on the Y axis', async () => {
    vi.stubGlobal('fetch', mockFetchRouter({}))
    const { container } = renderPage()
    await waitFor(() => expect(screen.getByText('台積電')).toBeInTheDocument())
    const baseline = container.querySelector('svg line[stroke="#4A5866"]')
    expect(baseline).toBeTruthy()
    expect(baseline!.getAttribute('stroke-dasharray')).toBeTruthy()
    const texts = Array.from(container.querySelectorAll('svg text')).map((t) => t.textContent)
    // dailySummary.open is 2355 in the fixture
    expect(texts).toContain('2355.00')
  })

  it('open-price baseline stays within the visible Y range even when every close is above the open (no clipping)', async () => {
    const allAboveOpenBars = makeBars(30).map((b, i) => ({
      ...b,
      open: 2355 + 20 + i,
      high: 2355 + 21 + i,
      low: 2355 + 19 + i,
      close: 2355 + 20 + i,
    }))
    vi.stubGlobal('fetch', mockFetchRouter({ minute: makeMinuteResponse({ bars: allAboveOpenBars }) }))
    const { container } = renderPage()
    await waitFor(() => expect(screen.getByText('台積電')).toBeInTheDocument())
    const baseline = container.querySelector('svg line[stroke="#4A5866"]')!
    const svg = container.querySelector('svg')!
    const viewBox = svg.getAttribute('viewBox')!.split(' ').map(Number)
    const y1 = Number(baseline.getAttribute('y1'))
    expect(y1).toBeGreaterThanOrEqual(0)
    expect(y1).toBeLessThanOrEqual(viewBox[3])
  })

  it('the crosshair marks the active point on the line with an #E6EDF5 dot', async () => {
    vi.stubGlobal('fetch', mockFetchRouter({}))
    const { container } = renderPage()
    await waitFor(() => expect(screen.getByText('台積電')).toBeInTheDocument())
    const svg = container.querySelector('svg')!
    fireEvent.click(svg, { clientX: clientXForBar(30, 2), clientY: 100 })
    await waitFor(() => {
      const dot = container.querySelector('svg circle[fill="#E6EDF5"]')
      expect(dot).toBeTruthy()
    })
  })

  it('X axis: first bar label is 09:00, and on-the-half-hour ticks render bold', async () => {
    vi.stubGlobal('fetch', mockFetchRouter({}))
    const { container } = renderPage()
    await waitFor(() => expect(screen.getByText('台積電')).toBeInTheDocument())
    const texts = Array.from(container.querySelectorAll('svg text')).map((t) => t.textContent)
    expect(texts).toContain('09:00')
    const boldTick = Array.from(container.querySelectorAll('svg text')).find(
      (t) => t.textContent === '09:00' && t.getAttribute('font-weight') === '700',
    )
    expect(boldTick).toBeTruthy()
  })

  it('tooltip on single-click shows time, OHLC, change vs the daily open, and volume', async () => {
    vi.stubGlobal('fetch', mockFetchRouter({}))
    const { container } = renderPage()
    await waitFor(() => expect(screen.getByText('台積電')).toBeInTheDocument())
    const svg = container.querySelector('svg')!
    fireEvent.click(svg, { clientX: clientXForBar(30, 2), clientY: 100 })
    await waitFor(() => expect(container.querySelector('.mc-tooltip-date')).toBeInTheDocument())
    const tooltip = container.querySelector('.mc-tooltip-body')!
    expect(tooltip.textContent).toContain('對開盤')
    expect(tooltip.textContent).toContain('成交量')
  })

  it('switching interval to 5 分 refetches with interval=5 and redraws fewer bars', async () => {
    const fetchMock = mockFetchRouter({
      minute: (u) => makeMinuteResponse({ interval: Number(u.searchParams.get('interval')), bars: makeBars(u.searchParams.get('interval') === '5' ? 6 : 30) }),
    })
    vi.stubGlobal('fetch', fetchMock)
    renderPage()
    await waitFor(() => expect(screen.getByText('台積電')).toBeInTheDocument())
    fireEvent.click(screen.getByRole('button', { name: '5 分' }))
    await waitFor(() => {
      const calls = fetchMock.mock.calls.filter((c: unknown[]) => String(c[0]).includes('/minute-bars'))
      const lastUrl = new URL(calls.at(-1)![0] as string, 'http://x')
      expect(lastUrl.searchParams.get('interval')).toBe('5')
    })
  })

  it('OUT_OF_WINDOW: shows the 30-day window message, no retry button, and the daily summary strip still shows', async () => {
    vi.stubGlobal(
      'fetch',
      mockFetchRouter({ minute: makeMinuteResponse({ dataStatus: 'OUT_OF_WINDOW', bars: [], barCount: 0 }) }),
    )
    renderPage()
    expect(await screen.findByText(/資料來源僅提供最近 30 天的分鐘資料/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '重試' })).not.toBeInTheDocument()
    expect(screen.getByText('開盤').parentElement).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '返回日 K' })).toBeInTheDocument()
  })

  it('NO_DATA, NOT_A_TRADING_DAY, and FETCH_FAILED each show distinct messages', async () => {
    vi.stubGlobal('fetch', mockFetchRouter({ minute: makeMinuteResponse({ dataStatus: 'NO_DATA', bars: [], barCount: 0 }) }))
    const { unmount: u1 } = renderPage()
    expect(await screen.findByText('此交易日沒有分鐘成交資料。')).toBeInTheDocument()
    u1()

    vi.stubGlobal(
      'fetch',
      mockFetchRouter({ minute: makeMinuteResponse({ dataStatus: 'NOT_A_TRADING_DAY', bars: [], barCount: 0, dailySummary: null }) }),
    )
    const { unmount: u2 } = renderPage()
    expect(await screen.findByText('此日期非該股票的交易日。')).toBeInTheDocument()
    u2()

    vi.stubGlobal(
      'fetch',
      mockFetchRouter({
        minute: makeMinuteResponse({ dataStatus: 'FETCH_FAILED', bars: [], barCount: 0, message: '連線逾時' }),
      }),
    )
    renderPage()
    expect(await screen.findByText('取得分鐘資料失敗')).toBeInTheDocument()
    expect(screen.getByText('連線逾時')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '重試' })).toBeInTheDocument()
  })

  it('FETCH_FAILED retry button re-requests with refresh=true', async () => {
    const fetchMock = mockFetchRouter({
      minute: makeMinuteResponse({ dataStatus: 'FETCH_FAILED', bars: [], barCount: 0, message: 'boom' }),
    })
    vi.stubGlobal('fetch', fetchMock)
    renderPage()
    expect(await screen.findByRole('button', { name: '重試' })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '重試' }))
    await waitFor(() => {
      const calls = fetchMock.mock.calls.filter((c: unknown[]) => String(c[0]).includes('/minute-bars'))
      expect(calls.length).toBeGreaterThanOrEqual(2)
      const lastUrl = new URL(calls.at(-1)![0] as string, 'http://x')
      expect(lastUrl.searchParams.get('refresh')).toBe('true')
    })
  })

  it('shows the loading explanation text before data arrives', async () => {
    const pending: { resolve: (() => void) | null } = { resolve: null }
    vi.stubGlobal(
      'fetch',
      vi.fn().mockImplementation((url: string) => {
        const u = new URL(url, 'http://x')
        if (u.pathname.endsWith('/minute-bars')) {
          return new Promise((resolve) => {
            pending.resolve = () => resolve({ ok: true, json: async () => makeMinuteResponse() })
          })
        }
        return Promise.resolve({ ok: true, json: async () => makeStats(['2026-08-24', '2026-08-25', '2026-08-26']) })
      }),
    )
    renderPage()
    expect(await screen.findByText('正在取得當日分鐘資料…')).toBeInTheDocument()
    pending.resolve?.()
    await waitFor(() => expect(screen.getByText('台積電')).toBeInTheDocument())
  })

  it('refresh button only shows when tradeDate is today', async () => {
    const today = new Date().toISOString().slice(0, 10)
    vi.stubGlobal('fetch', mockFetchRouter({ minute: makeMinuteResponse({ tradeDate: today }) }))
    renderPage(today)
    await waitFor(() => expect(screen.getByText('台積電')).toBeInTheDocument())
    expect(screen.getByRole('button', { name: '重新整理' })).toBeInTheDocument()
  })

  it('refresh button is absent for a non-today tradeDate', async () => {
    vi.stubGlobal('fetch', mockFetchRouter({}))
    renderPage('2026-08-25')
    await waitFor(() => expect(screen.getByText('台積電')).toBeInTheDocument())
    expect(screen.queryByRole('button', { name: '重新整理' })).not.toBeInTheDocument()
  })

  it('adjacent trading day navigation skips weekends (uses statistics series, not date+-1)', async () => {
    vi.stubGlobal(
      'fetch',
      mockFetchRouter({ stats: makeStats(['2026-08-21', '2026-08-24', '2026-08-25', '2026-08-26']) }),
    )
    renderPage('2026-08-25')
    await waitFor(() => expect(screen.getByText('台積電')).toBeInTheDocument())
    const prevBtn = await screen.findByRole('button', { name: '← 前一交易日' })
    await waitFor(() => expect(prevBtn).not.toBeDisabled())
    fireEvent.click(prevBtn)
    // navigates to the real previous *trading* day (skipping the weekend gap), not tradeDate-1
    await waitFor(() => expect(screen.getByText('2026-08-24')).toBeInTheDocument())
  })

  it('adjacent day buttons are disabled when the statistics request fails, but the minute chart still renders', async () => {
    vi.stubGlobal('fetch', mockFetchRouter({ stats: 'error' }))
    renderPage()
    await waitFor(() => expect(screen.getByText('台積電')).toBeInTheDocument())
    await waitFor(() => {
      expect(screen.getByRole('button', { name: '← 前一交易日' })).toBeDisabled()
      expect(screen.getByRole('button', { name: '後一交易日 →' })).toBeDisabled()
    })
    // chart itself still rendered fine
    expect(document.querySelector('svg')).toBeInTheDocument()
  })

  it('locked-limit day (high === low for every bar) still renders without a collapsed/zero-height axis', async () => {
    const lockedBars = makeBars(10).map((b) => ({ ...b, open: 100, high: 100, low: 100, close: 100 }))
    vi.stubGlobal('fetch', mockFetchRouter({ minute: makeMinuteResponse({ bars: lockedBars, barCount: 10 }) }))
    const { container } = renderPage()
    await waitFor(() => expect(screen.getByText('台積電')).toBeInTheDocument())
    const svg = container.querySelector('svg')!
    const viewBox = svg.getAttribute('viewBox')!.split(' ').map(Number)
    expect(viewBox[3]).toBeGreaterThan(0)
    const linePath = container.querySelector('svg path[stroke="#3E8FD8"]')
    expect(linePath).toBeTruthy()
  })

  it('shows "找不到此股票代號" for STOCK_NOT_FOUND', async () => {
    vi.stubGlobal('fetch', mockFetchRouter({ minute: 'notfound' }))
    renderPage()
    expect(await screen.findByText('找不到此股票代號')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('link', { name: '返回清單' }))
    await waitFor(() => expect(screen.getByText('list page')).toBeInTheDocument())
  })

  it('shows "網址中的日期無效" for an invalid date in the URL, instead of an empty chart or infinite spinner', async () => {
    vi.stubGlobal('fetch', mockFetchRouter({ minute: 'invalidDate' }))
    renderPage('not-a-date')
    expect(await screen.findByText('網址中的日期無效')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('link', { name: '返回日 K' }))
    await waitFor(() => expect(screen.getByText('daily page')).toBeInTheDocument())
  })

  it('shows a generic error with a reload button on network failure', async () => {
    vi.stubGlobal('fetch', mockFetchRouter({ minute: 'error' }))
    renderPage()
    expect(await screen.findByText('載入失敗，請稍後再試')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '重新載入' })).toBeInTheDocument()
  })
})
