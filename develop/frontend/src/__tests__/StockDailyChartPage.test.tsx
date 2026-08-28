import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import StockDailyChartPage from '../pages/StockDailyChartPage'
import type { StatisticsResponse, StatisticsSeriesRow } from '../api/statistics'
import type { StockDetail } from '../api/stocks'

// Mirrors KLineChart's internal layout constants so tests can compute the clientX of a given
// bar index without exporting private layout details from the component.
const VIEW_WIDTH = 1200
const LEFT_MARGIN = 8
const RIGHT_MARGIN = 68

function clientXForBar(n: number, index: number): number {
  const chartWidth = VIEW_WIDTH - LEFT_MARGIN - RIGHT_MARGIN
  const cw = chartWidth / n
  return LEFT_MARGIN + cw * index + cw / 2
}

function makeDetail(overrides: Partial<StockDetail> = {}): StockDetail {
  return {
    stockId: '2330',
    stockName: '台積電',
    market: 'TSE',
    isActive: true,
    latestTradeDate: '2026-08-27',
    latestClose: 2410.0,
    previousClose: 2415.0,
    changeAmount: -5.0,
    changePercent: -0.21,
    latestVolume: 17557736,
    firstTradeDate: '1994-09-05',
    tradingDayCount: 7800,
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
    dif: 8.6,
    dea: 5.5,
    osc: 3.0,
    k: 64.0,
    d: 56.3,
    j: 79.3,
    ...overrides,
  }
}

function makeStats(overrides: Partial<StatisticsResponse> = {}): StatisticsResponse {
  return {
    startDate: '2026-05-27',
    endDate: '2026-08-27',
    paramKey: 'MACD_12_26_9__KD_9_3_3',
    scope: 'SELECTED',
    stockCount: 1,
    items: [
      {
        stockId: '2330',
        stockName: '台積電',
        tradingDays: 5,
        warmupSufficient: true,
        summary: {
          firstOpen: 2200.0,
          lastClose: 2410.0,
          highest: 2535.0,
          highestDate: '2026-08-12',
          lowest: 2100.0,
          lowestDate: '2026-07-02',
          changeAmount: 210.0,
          changePercent: 9.55,
          totalVolume: 712_345_000,
          avgVolume: 17_374_285,
          latestDif: 8.6416,
          latestDea: 5.574,
          latestOsc: 3.0677,
          latestK: 63.9981,
          latestD: 56.3474,
          latestJ: 79.2995,
          macdGoldenCross: 3,
          macdDeathCross: 2,
          kdGoldenCross: 4,
          kdDeathCross: 3,
        },
        series: [
          makeRow({ tradeDate: '2026-08-20', open: 2380, close: 2400 }), // up (red)
          makeRow({ tradeDate: '2026-08-21', open: 2410, close: 2390 }), // down (green)
          makeRow({ tradeDate: '2026-08-24', open: 2390, close: 2400 }),
          makeRow({ tradeDate: '2026-08-25', open: 2400, close: 2381, dif: null, dea: null, osc: null, k: null, d: null, j: null }),
          makeRow({ tradeDate: '2026-08-26', open: 2381, close: 2410 }),
        ],
      },
    ],
    ...overrides,
  }
}

function mockFetchRouter(handlers: { detail?: StockDetail | 'notfound' | 'error'; stats?: StatisticsResponse | 'error' }) {
  return vi.fn().mockImplementation((url: string) => {
    const u = new URL(url, 'http://x')
    if (u.pathname === '/api/stocks/2330') {
      if (handlers.detail === 'notfound') {
        return Promise.resolve({ ok: false, status: 404, json: async () => ({ code: 'STOCK_NOT_FOUND' }) })
      }
      if (handlers.detail === 'error') {
        return Promise.resolve({ ok: false, status: 500, json: async () => ({ code: 'INTERNAL_ERROR' }) })
      }
      return Promise.resolve({ ok: true, json: async () => handlers.detail ?? makeDetail() })
    }
    if (u.pathname === '/api/stocks/statistics') {
      if (handlers.stats === 'error') {
        return Promise.resolve({ ok: false, status: 500, json: async () => ({ code: 'INTERNAL_ERROR' }) })
      }
      return Promise.resolve({ ok: true, json: async () => handlers.stats ?? makeStats() })
    }
    return Promise.reject(new Error(`unexpected fetch ${url}`))
  })
}

function renderPage() {
  return render(
    <MemoryRouter initialEntries={['/stocks/2330/daily']}>
      <Routes>
        <Route path="/stocks/:stockId/daily" element={<StockDailyChartPage />} />
        <Route path="/stocks" element={<div>list page</div>} />
        <Route path="/stocks/:stockId/minute/:tradeDate" element={<div>minute page</div>} />
      </Routes>
    </MemoryRouter>,
  )
}

describe('StockDailyChartPage', () => {
  beforeEach(() => {
    // jsdom returns an all-zero rect by default; the chart maps clientX -> bar index via this.
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

  it('shows the header (code/name/market/last close+change) and the default 3-month range selected', async () => {
    vi.stubGlobal('fetch', mockFetchRouter({}))
    renderPage()
    await waitFor(() => expect(screen.getByText('台積電')).toBeInTheDocument())
    expect(screen.getByText('上市')).toBeInTheDocument()
    expect(document.querySelector('.dc-last')?.textContent).toBe('2410.00')
    expect(screen.getByRole('button', { name: '3 個月' }).className).toContain('dc-on')
  })

  it('renders one candle per series row, colored red for close>=open and green for close<open', async () => {
    vi.stubGlobal('fetch', mockFetchRouter({}))
    const { container } = renderPage()
    await waitFor(() => expect(screen.getByText('台積電')).toBeInTheDocument())
    const rects = container.querySelectorAll('svg rect[fill="#E04B45"], svg rect[fill="#16A75C"]')
    // 5 candle bodies (only the body rect uses the exact up/down hex — wicks use stroke, not fill)
    const bodies = Array.from(rects).filter((r) => r.getAttribute('width') !== String(VIEW_WIDTH))
    expect(bodies.length).toBeGreaterThanOrEqual(5)
  })

  it('shows the summary strip fields including cross counts formatted per spec', async () => {
    vi.stubGlobal('fetch', mockFetchRouter({}))
    renderPage()
    expect(await screen.findByText('2200.00')).toBeInTheDocument() // 區間開盤
    expect(screen.getByText('黃金 3 ／ 死亡 2')).toBeInTheDocument() // MACD 交叉
    expect(screen.getByText('黃金 4 ／ 死亡 3')).toBeInTheDocument() // KD 交叉
  })

  it('shows "—" for null cross counts (not computed) and "0" for a real zero', async () => {
    vi.stubGlobal(
      'fetch',
      mockFetchRouter({
        stats: makeStats({
          items: [
            {
              ...makeStats().items[0],
              summary: {
                ...makeStats().items[0].summary!,
                macdGoldenCross: null,
                macdDeathCross: null,
                kdGoldenCross: 0,
                kdDeathCross: 0,
              },
            },
          ],
        }),
      }),
    )
    renderPage()
    await waitFor(() => expect(screen.getByText('台積電')).toBeInTheDocument())
    expect(screen.getByText('—', { selector: '.dc-strip .dc-v' })).toBeInTheDocument()
    expect(screen.getByText('黃金 0 ／ 死亡 0')).toBeInTheDocument()
  })

  it('shows the warmup banner when warmupSufficient is false, and it can be dismissed', async () => {
    vi.stubGlobal(
      'fetch',
      mockFetchRouter({
        stats: makeStats({ items: [{ ...makeStats().items[0], warmupSufficient: false }] }),
      }),
    )
    renderPage()
    expect(await screen.findByText(/本檔歷史資料不足 250 個交易日/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '×' }))
    await waitFor(() => expect(screen.queryByText(/本檔歷史資料不足 250 個交易日/)).not.toBeInTheDocument())
  })

  it('shows "指標尚未運算" in MACD/KD subplots when every indicator value in range is null, while candles still render', async () => {
    const rows = makeStats().items[0].series!.map((r) => ({
      ...r,
      dif: null,
      dea: null,
      osc: null,
      k: null,
      d: null,
      j: null,
    }))
    vi.stubGlobal(
      'fetch',
      mockFetchRouter({
        stats: makeStats({
          items: [
            {
              ...makeStats().items[0],
              series: rows,
              summary: {
                ...makeStats().items[0].summary!,
                latestDif: null,
                latestDea: null,
                latestOsc: null,
                latestK: null,
                latestD: null,
                latestJ: null,
                macdGoldenCross: null,
                macdDeathCross: null,
                kdGoldenCross: null,
                kdDeathCross: null,
              },
            },
          ],
        }),
      }),
    )
    const { container } = renderPage()
    await waitFor(() => expect(screen.getByText('台積電')).toBeInTheDocument())
    expect(screen.getAllByText('指標尚未運算').length).toBe(2)
    // candles still drawn
    expect(container.querySelectorAll('svg rect[fill="#E04B45"], svg rect[fill="#16A75C"]').length).toBeGreaterThan(0)
  })

  it('single click pins the crosshair/tooltip without navigating; clicking the same bar again unpins', async () => {
    vi.stubGlobal('fetch', mockFetchRouter({}))
    const { container } = renderPage()
    await waitFor(() => expect(screen.getByText('台積電')).toBeInTheDocument())
    const svg = container.querySelector('svg')!
    fireEvent.click(svg, { clientX: clientXForBar(5, 1), clientY: 100 })
    await waitFor(() => expect(container.querySelector('.dc-tooltip-date')?.textContent).toBe('2026-08-21'))
    expect(screen.queryByText('minute page')).not.toBeInTheDocument()
    // second click on the same bar unpins (tooltip disappears)
    fireEvent.click(svg, { clientX: clientXForBar(5, 1), clientY: 100 })
    await waitFor(() => expect(container.querySelector('.dc-tooltip-date')).not.toBeInTheDocument())
  })

  it('Esc unpins a pinned bar', async () => {
    vi.stubGlobal('fetch', mockFetchRouter({}))
    const { container } = renderPage()
    await waitFor(() => expect(screen.getByText('台積電')).toBeInTheDocument())
    const svg = container.querySelector('svg')!
    fireEvent.click(svg, { clientX: clientXForBar(5, 2), clientY: 100 })
    await waitFor(() => expect(container.querySelector('.dc-tooltip-date')?.textContent).toBe('2026-08-24'))
    fireEvent.keyDown(window, { key: 'Escape' })
    await waitFor(() => expect(container.querySelector('.dc-tooltip-date')).not.toBeInTheDocument())
  })

  it('double-clicking a candle navigates to /stocks/{id}/minute/{tradeDate}', async () => {
    vi.stubGlobal('fetch', mockFetchRouter({}))
    const { container } = renderPage()
    await waitFor(() => expect(screen.getByText('台積電')).toBeInTheDocument())
    const svg = container.querySelector('svg')!
    fireEvent.doubleClick(svg, { clientX: clientXForBar(5, 3), clientY: 100 })
    await waitFor(() => expect(screen.getByText('minute page')).toBeInTheDocument())
  })

  it('the chart area has cursor:pointer via the shared kc-svg class', async () => {
    vi.stubGlobal('fetch', mockFetchRouter({}))
    const { container } = renderPage()
    await waitFor(() => expect(screen.getByText('台積電')).toBeInTheDocument())
    const svg = container.querySelector('svg')!
    expect(svg.className.baseVal).toContain('kc-svg')
  })

  it('shows the persistent hint text below the chart', async () => {
    vi.stubGlobal('fetch', mockFetchRouter({}))
    renderPage()
    await waitFor(() => expect(screen.getByText('台積電')).toBeInTheDocument())
    expect(screen.getByText('連點兩下任一根 K 棒可查看當日分 K')).toBeInTheDocument()
  })

  it('switching to "1 年" refetches with a ~1-year startDate and redraws with the new series length', async () => {
    const fetchMock = mockFetchRouter({})
    vi.stubGlobal('fetch', fetchMock)
    renderPage()
    await waitFor(() => expect(screen.getByText('台積電')).toBeInTheDocument())
    fireEvent.click(screen.getByRole('button', { name: '1 年' }))
    await waitFor(() => {
      const calls = fetchMock.mock.calls.filter((c: unknown[]) =>
        String(c[0]).includes('/api/stocks/statistics'),
      )
      expect(calls.length).toBeGreaterThanOrEqual(2)
      const lastUrl = new URL(calls.at(-1)![0] as string, 'http://x')
      const startDate = lastUrl.searchParams.get('startDate')!
      const daysAgo = (Date.now() - new Date(startDate).getTime()) / (1000 * 60 * 60 * 24)
      expect(daysAgo).toBeGreaterThan(300)
    })
  })

  it('custom range: start date after end date shows an inline error and does not send a request', async () => {
    const fetchMock = mockFetchRouter({})
    vi.stubGlobal('fetch', fetchMock)
    renderPage()
    await waitFor(() => expect(screen.getByText('台積電')).toBeInTheDocument())
    fireEvent.click(screen.getByRole('button', { name: '自訂' }))
    const [startInput, endInput] = Array.from(
      document.querySelectorAll('input[type="date"]'),
    ) as HTMLInputElement[]
    fireEvent.change(startInput, { target: { value: '2026-08-20' } })
    fireEvent.change(endInput, { target: { value: '2026-08-01' } })
    expect(await screen.findByText('起日不可晚於迄日')).toBeInTheDocument()
    const callCountAfter = fetchMock.mock.calls.filter((c: unknown[]) =>
      String(c[0]).includes('/api/stocks/statistics'),
    ).length
    // no new statistics call fired for the invalid range
    fireEvent.change(endInput, { target: { value: '2026-08-01' } })
    expect(
      fetchMock.mock.calls.filter((c: unknown[]) => String(c[0]).includes('/api/stocks/statistics')).length,
    ).toBe(callCountAfter)
  })

  it('shows "此區間內沒有行情資料" with a "改看近 1 年" button when tradingDays is 0', async () => {
    vi.stubGlobal(
      'fetch',
      mockFetchRouter({
        stats: makeStats({
          items: [{ stockId: '2330', stockName: '台積電', tradingDays: 0, warmupSufficient: true, summary: null, series: [] }],
        }),
      }),
    )
    renderPage()
    expect(await screen.findByText('此區間內沒有行情資料')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '改看近 1 年' })).toBeInTheDocument()
  })

  it('shows "找不到此股票代號" with a back-to-list button for a 404', async () => {
    vi.stubGlobal('fetch', mockFetchRouter({ detail: 'notfound' }))
    renderPage()
    expect(await screen.findByText('找不到此股票代號')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('link', { name: '返回清單' }))
    await waitFor(() => expect(screen.getByText('list page')).toBeInTheDocument())
  })

  it('shows a generic error with a reload button when the statistics request fails', async () => {
    vi.stubGlobal('fetch', mockFetchRouter({ stats: 'error' }))
    renderPage()
    expect(await screen.findByText('載入失敗，請稍後再試')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '重新載入' })).toBeInTheDocument()
  })
})
