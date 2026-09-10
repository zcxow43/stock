import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import StrategyTab from '../pages/StrategyTab'

function jsonResponse(status: number, body: unknown) {
  return { ok: status >= 200 && status < 300, status, json: async () => body }
}

const CATALOG = {
  strategies: [
    {
      code: 'BOX_BREAKOUT',
      name: '箱型突破',
      presets: [
        { code: 'STRICT', name: '嚴格', description: '回看 60 根，箱高 < 5%，突破 2% 且量增 2 倍，需連 2 根確認' },
        { code: 'STANDARD', name: '標準', description: '回看 20 根，箱高 < 8%，突破 1.5% 且量增 1.5 倍' },
        { code: 'LOOSE', name: '寬鬆', description: '回看 20 根，不驗證盤整，收盤突破上緣即計' },
      ],
    },
    {
      code: 'HIGHER_LOWS',
      name: '底底高',
      presets: [
        { code: 'STRICT', name: '嚴格', description: '左右各 5 根，需 3 段遞增，每段高過 2%' },
        { code: 'STANDARD', name: '標準', description: '左右各 3 根，需 2 段遞增，每段高過 1%' },
        { code: 'LOOSE', name: '寬鬆', description: '左右各 2 根，需 2 段遞增，高過即計' },
      ],
    },
    {
      code: 'RISING_SUPPORT',
      name: '上漲支撐',
      presets: [
        { code: 'STRICT', name: '嚴格', description: '收盤突破前 20 日收盤高點且單日漲幅 ≥ 5%，其後 2 日不跌破起漲收盤' },
        { code: 'STANDARD', name: '標準', description: '收盤突破前 10 日收盤高點且單日漲幅 ≥ 3%，其後 2 日不跌破起漲收盤' },
        { code: 'LOOSE', name: '寬鬆', description: '收盤突破前 5 日收盤高點且單日漲幅 ≥ 2%，其後 2 日不跌破起漲收盤' },
      ],
    },
    {
      code: 'REBOUND',
      name: '反彈',
      description: '先在回看窗口內自最高收盤跌幅達門檻築出谷底，其後指定天數內自谷底反彈幅度達門檻',
      presets: [],
      paramGroups: [{ code: 'rise', name: '另外要求反彈漲幅', default: true }],
      params: [
        { code: 'dropDays', name: '下跌天數', unit: '日', default: 3, min: 1, max: 90, step: 1 },
        { code: 'dropPercent', name: '跌幅門檻', unit: '%', default: 10, min: 0, max: 50, step: 0.1 },
        { code: 'riseDays', name: '反彈天數', unit: '日', default: 1, min: 1, max: 90, step: 1, group: 'rise' },
        { code: 'risePercent', name: '反彈幅度', unit: '%', default: 5, min: 0, max: 50, step: 0.1, group: 'rise' },
      ],
    },
    {
      code: 'CUMULATIVE_RISE',
      name: '累積上漲',
      description: '回看指定天數，自窗口內最低收盤累積漲幅達門檻的最高點',
      presets: [],
      params: [
        { code: 'days', name: '天數', unit: '日', default: 20, min: 1, max: 90, step: 1 },
        { code: 'risePercent', name: '漲幅門檻', unit: '%', default: 15, min: 0, max: 50, step: 0.1 },
      ],
    },
  ],
}

function progressResponse(overrides: Record<string, unknown> = {}) {
  return {
    jobType: 'PRICE_BACKFILL',
    total: 34,
    pending: 0,
    running: 0,
    done: 34,
    failed: 0,
    skipped: 0,
    lastSyncedAt: '2026-08-30T12:00:00',
    failedItems: [],
    ...overrides,
  }
}

function boxScanResponse() {
  return {
    startDate: '2026-06-01',
    endDate: '2026-08-30',
    scannedStocks: 3,
    results: [
      {
        strategy: 'BOX_BREAKOUT',
        preset: 'STANDARD',
        matchedCount: 1,
        items: [
          {
            stockId: '2330',
            stockName: '台積電',
            signalDate: '2026-08-27',
            detail: { boxHigh: 2380.0, boxLow: 2250.0, breakoutClose: 2420.0, breakoutPercent: 1.68, volumeRatio: 1.82 },
          },
        ],
        insufficientData: ['6669'],
        pendingConfirm: ['1101'],
      },
    ],
  }
}

function higherLowsScanResponse() {
  return {
    startDate: '2026-06-01',
    endDate: '2026-08-30',
    scannedStocks: 2,
    results: [
      {
        strategy: 'HIGHER_LOWS',
        preset: 'STRICT',
        matchedCount: 1,
        items: [
          {
            stockId: '2317',
            stockName: '鴻海',
            signalDate: '2026-08-25',
            detail: {
              lows: [
                { tradeDate: '2026-07-08', ma5: 243.1, low: 240.0 },
                { tradeDate: '2026-08-25', ma5: 265.2, low: 262.5 },
              ],
            },
          },
        ],
        insufficientData: [],
        pendingConfirm: [],
      },
    ],
  }
}

function risingSupportScanResponse() {
  return {
    startDate: '2026-06-01',
    endDate: '2026-08-30',
    scannedStocks: 3,
    results: [
      {
        strategy: 'RISING_SUPPORT',
        preset: 'STANDARD',
        matchedCount: 1,
        items: [
          {
            stockId: '2454',
            stockName: '聯發科',
            signalDate: '2026-08-26',
            detail: {
              supportClose: 1200.0,
              riseClose: 1296.0,
              risePercent: 8.0,
              priorHighClose: 1236.0,
              confirmCloses: [
                { tradeDate: '2026-08-27', close: 1272.0 },
                { tradeDate: '2026-08-28', close: 1248.0 },
              ],
            },
          },
        ],
        insufficientData: ['6669'],
        pendingConfirm: ['3008'],
      },
    ],
  }
}

// Values match the spec's own hand-calculated 反彈 example: peak 120.00 on 2026-08-10,
// trough 100.00 on 2026-08-25, dropPercent 16.67.
function reboundScanResponse() {
  return {
    startDate: '2026-06-01',
    endDate: '2026-08-30',
    scannedStocks: 3,
    results: [
      {
        strategy: 'REBOUND',
        requireRise: true,
        dropDays: 3,
        dropPercent: 10,
        riseDays: 1,
        risePercent: 5,
        matchedCount: 1,
        items: [
          {
            stockId: '2454',
            stockName: '聯發科',
            signalDate: '2026-08-26',
            detail: {
              peakDate: '2026-08-10',
              peakClose: 120.0,
              troughDate: '2026-08-25',
              troughClose: 100.0,
              dropPercent: 16.67,
              risePercent: 6.0,
            },
          },
        ],
        insufficientData: ['6669'],
        pendingConfirm: [],
      },
    ],
  }
}

// Values match the spec's own hand-calculated 累積上漲 example: trough 80.00 on
// 2026-08-05, peak 100.00 on 2026-08-28, risePercent 25.00.
function cumulativeRiseScanResponse() {
  return {
    startDate: '2026-06-01',
    endDate: '2026-08-30',
    scannedStocks: 3,
    results: [
      {
        strategy: 'CUMULATIVE_RISE',
        days: 20,
        matchedCount: 1,
        items: [
          {
            stockId: '2317',
            stockName: '鴻海',
            signalDate: '2026-08-28',
            detail: { troughDate: '2026-08-05', troughClose: 80.0, peakClose: 100.0, risePercent: 25.0 },
          },
        ],
        insufficientData: ['6669'],
        pendingConfirm: [],
      },
    ],
  }
}

// All five strategies, one hit each, in submission order.
function allFiveStrategiesResponse() {
  return {
    startDate: '2026-06-01',
    endDate: '2026-08-30',
    scannedStocks: 3,
    results: [
      { ...boxScanResponse().results[0] },
      { ...higherLowsScanResponse().results[0] },
      { ...risingSupportScanResponse().results[0] },
      { ...reboundScanResponse().results[0] },
      { ...cumulativeRiseScanResponse().results[0] },
    ],
  }
}

function zeroHitResponse() {
  return {
    startDate: '2026-08-25',
    endDate: '2026-08-27',
    scannedStocks: 34,
    results: [
      { strategy: 'BOX_BREAKOUT', preset: 'STRICT', matchedCount: 0, items: [], insufficientData: [], pendingConfirm: [] },
    ],
  }
}

function bothStrategiesResponse() {
  return {
    startDate: '2026-06-01',
    endDate: '2026-08-30',
    scannedStocks: 3,
    results: [
      { ...boxScanResponse().results[0] },
      { ...higherLowsScanResponse().results[0] },
    ],
  }
}

// Two strategies, one stock (2330) hit by both with different signalDates (matching the
// spec's own worked example: 箱型突破 2026-08-28 / 底底高 2026-08-25), plus one stock unique
// to each strategy — enough to exercise dedup, per-strategy signal dates, and the
// newest-signalDate-desc / stockId-asc sort (2317 and 2330 tie on 2026-08-28).
function unionScanResponse() {
  return {
    startDate: '2026-06-01',
    endDate: '2026-08-30',
    scannedStocks: 5,
    results: [
      {
        strategy: 'BOX_BREAKOUT',
        preset: 'STANDARD',
        matchedCount: 2,
        items: [
          {
            stockId: '2330',
            stockName: '台積電',
            signalDate: '2026-08-28',
            detail: { boxHigh: 2380.0, boxLow: 2250.0, breakoutClose: 2420.0, breakoutPercent: 1.68, volumeRatio: 1.82 },
          },
          {
            stockId: '2454',
            stockName: '聯發科',
            signalDate: '2026-08-20',
            detail: { boxHigh: 900.0, boxLow: 850.0, breakoutClose: 910.0, breakoutPercent: 1.11, volumeRatio: 1.5 },
          },
        ],
        insufficientData: ['6669'],
        pendingConfirm: ['1101'],
      },
      {
        strategy: 'HIGHER_LOWS',
        preset: 'STRICT',
        matchedCount: 2,
        items: [
          {
            stockId: '2330',
            stockName: '台積電',
            signalDate: '2026-08-25',
            detail: {
              lows: [
                { tradeDate: '2026-07-08', ma5: 243.1, low: 240.0 },
                { tradeDate: '2026-08-25', ma5: 265.2, low: 262.5 },
              ],
            },
          },
          {
            stockId: '2317',
            stockName: '鴻海',
            signalDate: '2026-08-28',
            detail: {
              lows: [
                { tradeDate: '2026-07-01', ma5: 200.0, low: 200.0 },
                { tradeDate: '2026-08-28', ma5: 220.0, low: 220.0 },
              ],
            },
          },
        ],
        insufficientData: [],
        pendingConfirm: [],
      },
    ],
  }
}

function zeroHitBothResponse() {
  return {
    startDate: '2026-08-25',
    endDate: '2026-08-27',
    scannedStocks: 34,
    results: [
      { strategy: 'BOX_BREAKOUT', preset: 'STANDARD', matchedCount: 0, items: [], insufficientData: [], pendingConfirm: [] },
      { strategy: 'HIGHER_LOWS', preset: 'STANDARD', matchedCount: 0, items: [], insufficientData: [], pendingConfirm: [] },
    ],
  }
}

// Matches boxScanResponse()'s single hit (2330, signalDate 2026-08-27) — the default
// `backtestResponder` fixture for tests that don't care about the exact numbers.
function singleBacktestResponse() {
  return {
    asOfDate: '2026-09-10',
    lotSize: 1000,
    totalCost: 2420000,
    totalProfit: 30000,
    totalReturnPercent: 1.24,
    backtestedCount: 1,
    items: [
      { stockId: '2330', signalDate: '2026-08-27', buyPrice: 2420, sellDate: '2026-09-01', sellPrice: 2450, returnPercent: 1.24, profit: 30000 },
    ],
  }
}

// Matches boxScanResponse()'s single hit but with no sellable trading day — exercises the
// "全部標的皆無法回測" (totalReturnPercent: null) path.
function allUnbacktestableResponse() {
  return {
    asOfDate: '2026-09-10',
    lotSize: 1000,
    totalCost: 0,
    totalProfit: 0,
    totalReturnPercent: null,
    backtestedCount: 0,
    items: [{ stockId: '2330', signalDate: '2026-08-27', buyPrice: 2420, sellDate: null, sellPrice: null, returnPercent: null, profit: null }],
  }
}

// Matches unionScanResponse()'s three merged rows (2317, 2330, 2454 — in that sorted
// order): one profitable, one losing, one unbacktestable — exercises totals, colors, and
// both "未計入" reasons together.
function unionBacktestResponse() {
  return {
    asOfDate: '2026-09-10',
    lotSize: 1000,
    totalCost: 2500000,
    totalProfit: -90000,
    totalReturnPercent: -3.6,
    backtestedCount: 2,
    items: [
      { stockId: '2317', signalDate: '2026-08-28', buyPrice: 100, sellDate: '2026-09-01', sellPrice: 110, returnPercent: 10, profit: 10000 },
      { stockId: '2330', signalDate: '2026-08-28', buyPrice: 2400, sellDate: '2026-09-02', sellPrice: 2300, returnPercent: -4.17, profit: -100000 },
      { stockId: '2454', signalDate: '2026-08-20', buyPrice: 900, sellDate: null, sellPrice: null, returnPercent: null, profit: null },
    ],
  }
}

function renderTab(commonStocksOnly = true) {
  return render(
    <MemoryRouter initialEntries={['/stocks?tab=strategy']}>
      <Routes>
        <Route path="/stocks" element={<StrategyTab commonStocksOnly={commonStocksOnly} />} />
        <Route path="/stocks/:stockId/daily" element={<div>daily page for the clicked row</div>} />
        <Route path="/stocks/:stockId/minute/:tradeDate" element={<div>minute page for the clicked row</div>} />
      </Routes>
    </MemoryRouter>,
  )
}

/** Scoped to the `.st-strategy-name` heading specifically — once a scan has run, a
 * strategy's name can also appear as a merged-table hit tag (`.st-union-tag-name`) or a
 * below-table note, so a bare `getByText(name)` becomes ambiguous. */
function cardFor(name: string): HTMLElement {
  const heading = screen.getAllByText(name).find((el) => el.className === 'st-strategy-name')
  return heading!.closest('.st-strategy-card') as HTMLElement
}

/** Clicks a strategy card's own selection checkbox specifically — never `getByRole
 * ('checkbox')` singular, because 反彈's card also has its own "另外要求反彈漲幅" group
 * checkbox and a bare singular query would throw "found multiple elements" for it. */
function selectStrategy(name: string): void {
  const head = cardFor(name).querySelector('.st-strategy-card-head') as HTMLElement
  fireEvent.click(within(head).getByRole('checkbox'))
}


describe('StrategyTab', () => {
  let fetchMock: ReturnType<typeof vi.fn>
  let scanResponder: () => unknown
  let progressResponder: () => unknown
  let backfillResponder: () => { status: number; body: unknown }
  let stocksTotal: number
  let universeImportResponder: () => { status: number; body: unknown }
  let backtestResponder: () => { status: number; body: unknown }

  beforeEach(() => {
    scanResponder = () => boxScanResponse()
    progressResponder = () => progressResponse()
    backtestResponder = () => ({ status: 200, body: singleBacktestResponse() })
    backfillResponder = () => ({
      status: 202,
      body: { jobType: 'PRICE_BACKFILL', targetCount: 34, caughtUpCount: 0, startDate: '2026-01-01', endDate: '2026-08-30', mode: 'ALL' },
    })
    stocksTotal = 34
    universeImportResponder = () => ({
      status: 200,
      body: {
        fetchedCount: 1377,
        eligibleCount: 1085,
        skippedCount: 292,
        insertedCount: 1,
        updatedCount: 1050,
        totalActiveCount: 1051,
        industrySourceStatus: 'OK',
        industryCount: 35,
        industryLinkedStockCount: 1085,
        uncategorizedStockCount: 289,
      },
    })

    fetchMock = vi.fn().mockImplementation((url: string, init?: RequestInit) => {
      const u = String(url)
      const method = init?.method ?? 'GET'
      if (u.startsWith('/api/strategies/scan')) {
        return Promise.resolve(jsonResponse(200, scanResponder()))
      }
      if (u.startsWith('/api/strategies/backtest')) {
        const { status, body } = backtestResponder()
        return Promise.resolve(jsonResponse(status, body))
      }
      if (u.startsWith('/api/strategies')) {
        return Promise.resolve(jsonResponse(200, CATALOG))
      }
      if (u.startsWith('/api/stocks/sync/progress')) {
        return Promise.resolve(jsonResponse(200, progressResponder()))
      }
      if (u.startsWith('/api/stocks/sync/backfill')) {
        const { status, body } = backfillResponder()
        return Promise.resolve(jsonResponse(status, body))
      }
      if (u.startsWith('/api/stocks/universe/import') && method === 'POST') {
        const { status, body } = universeImportResponder()
        return Promise.resolve(jsonResponse(status, body))
      }
      if (u.startsWith('/api/stocks?')) {
        const params = new URL(u, 'http://x').searchParams
        if (params.get('size') === '1') {
          // 常駐「共 N 檔」— GET /api/stocks?page=1&size=1, only `total` matters.
          return Promise.resolve(jsonResponse(200, { page: 1, size: 1, total: stocksTotal, totalPages: stocksTotal, items: [] }))
        }
        return Promise.resolve(
          jsonResponse(200, {
            page: 1,
            size: 20,
            total: 1,
            totalPages: 1,
            items: [
              {
                stockId: '2317',
                stockName: '鴻海',
                market: 'TSE',
                isActive: true,
                latestTradeDate: '2026-08-28',
                latestClose: 253,
                previousClose: 252,
                changeAmount: 1,
                changePercent: 0.4,
                latestVolume: 100,
              },
            ],
          }),
        )
      }
      return Promise.resolve(jsonResponse(200, {}))
      void init
    })
    vi.stubGlobal('fetch', fetchMock)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
    vi.useRealTimers()
  })

  it('renders strategy names and preset descriptions from GET /api/strategies, not hard-coded', async () => {
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    expect(screen.getByText('底底高')).toBeInTheDocument()
    // STANDARD is the default preset for both cards — its description shows immediately.
    expect(screen.getByText('回看 20 根，箱高 < 8%，突破 1.5% 且量增 1.5 倍')).toBeInTheDocument()
    expect(screen.getByText('左右各 3 根，需 2 段遞增，每段高過 1%')).toBeInTheDocument()
  })

  it('disables the preset dropdown for an unchecked strategy card and enables it once checked', async () => {
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    const card = screen.getByText('箱型突破').closest('.st-strategy-card') as HTMLElement
    const select = within(card).getByRole('combobox') as HTMLSelectElement
    expect(select.disabled).toBe(true)

    fireEvent.click(within(card).getByRole('checkbox'))
    expect(select.disabled).toBe(false)
    expect(select.value).toBe('STANDARD')
  })

  it('disables 開始掃描 and shows a hint when no strategy is selected', async () => {
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    expect(screen.getByRole('button', { name: '開始掃描' })).toBeDisabled()
    expect(screen.getByText('請至少勾選一個策略')).toBeInTheDocument()
  })

  it('defaults the date range to one calendar month back through today', async () => {
    vi.setSystemTime(new Date('2026-08-30T00:00:00'))
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    const [startInput, endInput] = screen.getAllByDisplayValue(/\d{4}-\d{2}-\d{2}/) as HTMLInputElement[]
    expect(startInput.value).toBe('2026-07-30')
    expect(endInput.value).toBe('2026-08-30')
  })

  it('applies the 近三個月 shortcut and highlights it', async () => {
    vi.setSystemTime(new Date('2026-08-30T00:00:00'))
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    fireEvent.click(screen.getByRole('button', { name: '近三個月' }))
    const [startInput] = screen.getAllByDisplayValue(/\d{4}-\d{2}-\d{2}/) as HTMLInputElement[]
    expect(startInput.value).toBe('2026-05-30')
    expect(screen.getByRole('button', { name: '近三個月' }).className).toContain('st-shortcut-active')
  })

  it('applies each of the three date-range shortcuts to the exact expected calendar dates', async () => {
    // Pinned "today" so every expected date below is a literal, independently
    // verified constant — not a re-derivation of the component's own monthsAgo()
    // formula, which would pass even if that formula were wrong.
    vi.setSystemTime(new Date('2026-08-30T00:00:00'))
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())

    const dateInputs = () => screen.getAllByDisplayValue(/\d{4}-\d{2}-\d{2}/) as HTMLInputElement[]

    // 近一個月: 2026-08-30 minus 1 calendar month = 2026-07-30 (July has 30 days, no overflow).
    fireEvent.click(screen.getByRole('button', { name: '近一個月' }))
    let [startInput, endInput] = dateInputs()
    expect(startInput.value).toBe('2026-07-30')
    expect(endInput.value).toBe('2026-08-30')
    expect(screen.getByRole('button', { name: '近一個月' }).className).toContain('st-shortcut-active')

    // 近三個月: 2026-08-30 minus 3 calendar months = 2026-05-30 (May has 31 days, no overflow).
    fireEvent.click(screen.getByRole('button', { name: '近三個月' }))
    ;[startInput, endInput] = dateInputs()
    expect(startInput.value).toBe('2026-05-30')
    expect(endInput.value).toBe('2026-08-30')
    expect(screen.getByRole('button', { name: '近三個月' }).className).toContain('st-shortcut-active')

    // 近半年: 2026-08-30 minus 6 calendar months lands on "Feb 30", which doesn't
    // exist — 2026 is not a leap year, so February has 28 days. The calendar-correct
    // result clamps to the last day of February: 2026-02-28, not an overflow into March.
    fireEvent.click(screen.getByRole('button', { name: '近半年' }))
    ;[startInput, endInput] = dateInputs()
    expect(startInput.value).toBe('2026-02-28')
    expect(endInput.value).toBe('2026-08-30')
    expect(screen.getByRole('button', { name: '近半年' }).className).toContain('st-shortcut-active')
  })

  it('blocks the scan and shows an inline message when start date is after end date', async () => {
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    fireEvent.click(within(screen.getByText('箱型突破').closest('.st-strategy-card')!).getByRole('checkbox'))

    const [startInput, endInput] = screen.getAllByDisplayValue(/\d{4}-\d{2}-\d{2}/) as HTMLInputElement[]
    fireEvent.change(startInput, { target: { value: '2026-09-01' } })
    fireEvent.change(endInput, { target: { value: '2026-08-01' } })

    expect(screen.getByText('起日不可晚於迄日')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '開始掃描' })).toBeDisabled()

    const scanCallsBefore = fetchMock.mock.calls.filter((c) => String(c[0]).startsWith('/api/strategies/scan')).length
    expect(scanCallsBefore).toBe(0)
  })

  it('sends no stockIds for the default 全市場 scope', async () => {
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    fireEvent.click(within(screen.getByText('箱型突破').closest('.st-strategy-card')!).getByRole('checkbox'))
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))

    // 代號 and 名稱 render as sibling text nodes inside one <td> ("2330 台積電"), so
    // match the combined text rather than a bare "2330" substring (exact match by default).
    await waitFor(() => expect(screen.getByText('2330 台積電')).toBeInTheDocument())
    const scanCall = fetchMock.mock.calls.find((c) => String(c[0]).startsWith('/api/strategies/scan'))!
    const body = JSON.parse((scanCall[1] as RequestInit).body as string)
    expect(body.stockIds).toBeUndefined()
  })

  it('lets the user search and add a stock under 指定股票, shown as a removable tag', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())

    fireEvent.click(screen.getByRole('radio', { name: '指定股票' }))
    const input = screen.getByPlaceholderText('輸入代號或名稱搜尋加入')
    fireEvent.change(input, { target: { value: '2317' } })
    await vi.advanceTimersByTimeAsync(300)

    await vi.waitFor(() => expect(screen.getByText('2317 鴻海')).toBeInTheDocument())
    fireEvent.click(screen.getByText('2317 鴻海'))

    expect(screen.getByLabelText('移除 2317')).toBeInTheDocument()
    fireEvent.click(screen.getByLabelText('移除 2317'))
    expect(screen.queryByLabelText('移除 2317')).not.toBeInTheDocument()
    vi.useRealTimers()
  })

  it('disables the stock-search input and shows a hint once 200 stocks are selected', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    fetchMock.mockImplementation((url: string) => {
      const u = String(url)
      if (u.startsWith('/api/strategies/scan')) return Promise.resolve(jsonResponse(200, boxScanResponse()))
      if (u.startsWith('/api/strategies')) return Promise.resolve(jsonResponse(200, CATALOG))
      if (u.startsWith('/api/stocks/sync/progress')) return Promise.resolve(jsonResponse(200, progressResponse()))
      if (u.startsWith('/api/stocks?')) {
        const keyword = new URL(u, 'http://localhost').searchParams.get('keyword') ?? ''
        return Promise.resolve(
          jsonResponse(200, {
            page: 1,
            size: 20,
            total: 1,
            totalPages: 1,
            items: [
              {
                stockId: keyword,
                stockName: `股票${keyword}`,
                market: 'TSE',
                isActive: true,
                latestTradeDate: null,
                latestClose: null,
                previousClose: null,
                changeAmount: null,
                changePercent: null,
                latestVolume: null,
              },
            ],
          }),
        )
      }
      return Promise.resolve(jsonResponse(200, {}))
    })

    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    fireEvent.click(screen.getByRole('radio', { name: '指定股票' }))
    const input = screen.getByPlaceholderText('輸入代號或名稱搜尋加入')

    for (let i = 0; i < 200; i++) {
      const code = String(1000 + i)
      fireEvent.change(input, { target: { value: code } })
      await vi.advanceTimersByTimeAsync(300)
      await vi.waitFor(() => expect(screen.getByText(`${code} 股票${code}`)).toBeInTheDocument())
      fireEvent.click(screen.getByText(`${code} 股票${code}`))
    }

    expect(screen.getByText('最多 200 檔')).toBeInTheDocument()
    expect((screen.getByPlaceholderText('輸入代號或名稱搜尋加入') as HTMLInputElement).disabled).toBe(true)
    vi.useRealTimers()
  }, 20000)

  it('shows a single merged hit table titled 命中彙總 — 共 N 檔 even when only one strategy is checked, not a per-strategy block', async () => {
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    fireEvent.click(within(screen.getByText('箱型突破').closest('.st-strategy-card')!).getByRole('checkbox'))
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))

    await waitFor(() => expect(screen.getByText('命中彙總 — 共 1 檔')).toBeInTheDocument())
    expect(screen.getByText('2330 台積電')).toBeInTheDocument()
    // No per-strategy block titled with the old "策略（靈敏度）— 命中 N 檔" heading exists anymore.
    expect(screen.queryByText(/命中 1 檔/)).not.toBeInTheDocument()
  })

  it('shows one deduped merged table, sorted by each stock\'s newest signalDate desc then stockId asc, when two strategies are scanned', async () => {
    scanResponder = () => unionScanResponse()
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    fireEvent.click(within(screen.getByText('箱型突破').closest('.st-strategy-card')!).getByRole('checkbox'))
    fireEvent.click(within(screen.getByText('底底高').closest('.st-strategy-card')!).getByRole('checkbox'))
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))

    // 3 distinct stocks (2330 hit by both), not 2+2=4 (the sum of matchedCount)
    await waitFor(() => expect(screen.getByText('命中彙總 — 共 3 檔')).toBeInTheDocument())

    // Only one heading exists — no per-strategy block titles remain.
    const resultsList = screen.getByText('命中彙總 — 共 3 檔').closest('.st-results-list') as HTMLElement
    const blockTitles = within(resultsList).getAllByRole('heading', { level: 3 }).map((h) => h.textContent)
    expect(blockTitles).toEqual(['命中彙總 — 共 3 檔'])

    const unionTable = screen.getByText('命中彙總 — 共 3 檔').closest('.st-union-block')!.querySelector('table')!
    const dataRows = within(unionTable).getAllByRole('row').slice(1)
    // 2317 and 2330 tie on the newest signalDate (2026-08-28) -> stockId asc; 2454 (2026-08-20) last
    expect(dataRows.map((r) => within(r).getAllByRole('cell')[1].textContent)).toEqual([
      '2317 鴻海',
      '2330 台積電',
      '2454 聯發科',
    ])

    // 2330 is hit by both strategies — each strategy's own signalDate is listed, not merged
    const stock2330Row = within(unionTable).getByText('2330 台積電').closest('tr')!
    expect(within(stock2330Row).getByText('箱型突破')).toBeInTheDocument()
    expect(within(stock2330Row).getByText('2026-08-28')).toBeInTheDocument()
    expect(within(stock2330Row).getByText('底底高')).toBeInTheDocument()
    expect(within(stock2330Row).getByText('2026-08-25')).toBeInTheDocument()

    // insufficientData ('6669') / pendingConfirm ('1101') never appear in the merged table
    expect(within(unionTable).queryByText(/6669/)).not.toBeInTheDocument()
    expect(within(unionTable).queryByText(/1101/)).not.toBeInTheDocument()

    // ...but do appear, one line per strategy, below the merged table.
    expect(screen.getByText('箱型突破：另有 1 檔因區間前的歷史資料不足而未納入判定')).toBeInTheDocument()
    expect(screen.getByText('箱型突破：另有 1 檔已突破，但確認日尚未到')).toBeInTheDocument()
  })

  it('shows the 「本次採用參數」line under the title, one segment per strategy joined by ・, sourced from the response', async () => {
    scanResponder = () => unionScanResponse()
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    fireEvent.click(within(screen.getByText('箱型突破').closest('.st-strategy-card')!).getByRole('checkbox'))
    fireEvent.click(within(screen.getByText('底底高').closest('.st-strategy-card')!).getByRole('checkbox'))
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))

    await waitFor(() => expect(screen.getByText('箱型突破（標準）・底底高（嚴格）')).toBeInTheDocument())
  })

  it('keeps the 「本次採用參數」 line at the last-scanned values after editing an input without re-scanning', async () => {
    scanResponder = () => cumulativeRiseScanResponse()
    renderTab()
    await waitFor(() => expect(screen.getByText('累積上漲')).toBeInTheDocument())
    selectStrategy('累積上漲')
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() => expect(screen.getByText('累積上漲（20 日）')).toBeInTheDocument())

    const daysInput = within(cardFor('累積上漲')).getByLabelText('天數') as HTMLInputElement
    fireEvent.change(daysInput, { target: { value: '30' } })
    // No re-scan happened — the line must still read the last-scanned value (20), not 30.
    expect(screen.getByText('累積上漲（20 日）')).toBeInTheDocument()
  })

  it('formats the 反彈 params-line segment with all four parameters when requireRise is true, and only the drop-side when false', async () => {
    scanResponder = () => reboundScanResponse()
    renderTab()
    await waitFor(() => expect(screen.getByText('反彈')).toBeInTheDocument())
    selectStrategy('反彈')
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() => expect(screen.getByText('反彈（3 日跌 10% → 1 日反彈 5%）')).toBeInTheDocument())

    scanResponder = () => ({
      startDate: '2026-06-01',
      endDate: '2026-08-30',
      scannedStocks: 1,
      results: [
        {
          strategy: 'REBOUND',
          requireRise: false,
          dropDays: 3,
          dropPercent: 10,
          matchedCount: 1,
          items: [
            {
              stockId: '2603',
              stockName: '長榮',
              signalDate: '2026-08-25',
              detail: { peakDate: '2026-08-21', peakClose: 120.0, troughDate: '2026-08-25', troughClose: 100.0, dropPercent: 16.67 },
            },
          ],
          insufficientData: [],
          pendingConfirm: [],
        },
      ],
    })
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() => expect(screen.getByText('反彈（3 日跌 10%）')).toBeInTheDocument())
    expect(screen.queryByText(/反彈（3 日跌 10% →/)).not.toBeInTheDocument()
  })

  it('shows all five strategies\' params-line formats (靈敏度 for three, N 日 for 累積上漲, drop→rise for 反彈) in one line', async () => {
    scanResponder = () => allFiveStrategiesResponse()
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    for (const name of ['箱型突破', '底底高', '上漲支撐', '反彈', '累積上漲']) {
      selectStrategy(name)
    }
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))

    await waitFor(() =>
      expect(
        screen.getByText('箱型突破（標準）・底底高（嚴格）・上漲支撐（標準）・反彈（3 日跌 10% → 1 日反彈 5%）・累積上漲（20 日）'),
      ).toBeInTheDocument(),
    )
  })

  it('navigates to /stocks/{stockId}/daily when a merged-table row is clicked', async () => {
    scanResponder = () => unionScanResponse()
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    fireEvent.click(within(screen.getByText('箱型突破').closest('.st-strategy-card')!).getByRole('checkbox'))
    fireEvent.click(within(screen.getByText('底底高').closest('.st-strategy-card')!).getByRole('checkbox'))
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))

    await waitFor(() => expect(screen.getByText('命中彙總 — 共 3 檔')).toBeInTheDocument())
    const unionTable = screen.getByText('命中彙總 — 共 3 檔').closest('.st-union-block')!.querySelector('table')!
    fireEvent.click(within(unionTable).getByText('2454 聯發科'))
    await waitFor(() => expect(screen.getByText('daily page for the clicked row')).toBeInTheDocument())
  })

  it('shows the zero-hit message together with the last-synced time, not an empty table', async () => {
    scanResponder = () => zeroHitResponse()
    progressResponder = () => progressResponse({ lastSyncedAt: '2026-08-30T09:15:00' })
    renderTab()
    await waitFor(() => expect(screen.getByText('最後同步：2026-08-30 09:15')).toBeInTheDocument())

    fireEvent.click(within(screen.getByText('箱型突破').closest('.st-strategy-card')!).getByRole('checkbox'))
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))

    await waitFor(() => expect(screen.getByText('此區間內沒有命中的股票')).toBeInTheDocument())
    expect(within(screen.getByText('此區間內沒有命中的股票').closest('.st-result-zero')!).getByText(/最後同步/)).toBeInTheDocument()
  })

  it('shows 尚未同步 when the backend has never completed a sync', async () => {
    progressResponder = () => progressResponse({ lastSyncedAt: null })
    renderTab()
    await waitFor(() => expect(screen.getByText('最後同步：尚未同步')).toBeInTheDocument())
  })

  it('shows a running state with completed/total counts after clicking 同步日 K 至今日, without blocking scanning', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    let pollCount = 0
    progressResponder = () => {
      pollCount += 1
      // Call #1 is the on-mount fetch, before the user has clicked anything — it must
      // report idle, or the tab's "already running?" auto-detect would flip the button
      // to 同步中 before the click even happens.
      if (pollCount === 1) return progressResponse({ total: 34, pending: 0, running: 0, done: 34 })
      return pollCount === 2
        ? progressResponse({ total: 34, pending: 20, running: 1, done: 13 })
        : progressResponse({ total: 34, pending: 0, running: 0, done: 30, failed: 2, skipped: 2, lastSyncedAt: '2026-08-30T13:00:00' })
    }
    renderTab()
    await waitFor(() => expect(screen.getByRole('button', { name: '同步日 K 至今日' })).toBeInTheDocument())

    fireEvent.click(screen.getByRole('button', { name: '同步日 K 至今日' }))
    expect(screen.getByRole('button', { name: '同步中…' })).toBeDisabled()

    // sync running must not disable the scan button
    fireEvent.click(within(screen.getByText('箱型突破').closest('.st-strategy-card')!).getByRole('checkbox'))
    expect(screen.getByRole('button', { name: '開始掃描' })).not.toBeDisabled()

    await vi.waitFor(() => expect(screen.getByText('已完成 13 / 34 檔')).toBeInTheDocument())

    await vi.advanceTimersByTimeAsync(5000)
    await vi.waitFor(() => expect(screen.getByText('完成 30 檔上市普通股／失敗 2 檔／略過 2 檔')).toBeInTheDocument())
    expect(screen.getByText('最後同步：2026-08-30 13:00')).toBeInTheDocument()
    vi.useRealTimers()
  })

  it('treats a 409 JOB_ALREADY_RUNNING response as "already running", not an error', async () => {
    backfillResponder = () => ({ status: 409, body: { code: 'JOB_ALREADY_RUNNING' } })
    let progressCallCount = 0
    progressResponder = () => {
      progressCallCount += 1
      // Call #1 is the on-mount fetch — idle, so the click below is what drives the
      // running state, not a mount-time auto-detect racing ahead of it. Later polls
      // (post-click) report an in-flight job, matching the 409's "someone else is
      // already running it" semantics, and deliberately never report `failed > 0` so
      // the "not an error" assertion below can't collide with a completion summary.
      return progressCallCount === 1
        ? progressResponse({ pending: 0, running: 0, done: 34 })
        : progressResponse({ pending: 5, running: 1, done: 29 })
    }
    renderTab()
    await waitFor(() => expect(screen.getByRole('button', { name: '同步日 K 至今日' })).toBeInTheDocument())

    fireEvent.click(screen.getByRole('button', { name: '同步日 K 至今日' }))
    await waitFor(() => expect(screen.getByRole('button', { name: '同步中…' })).toBeInTheDocument())
    expect(screen.queryByText(/失敗/)).not.toBeInTheDocument()
  })

  it('shows lastSyncedAt exactly as returned with no timezone conversion (no 8-hour shift)', async () => {
    // `lastSyncedAt` already arrives as Asia/Taipei local time from the backend
    // (specs/backend/stock-price-ingestion.md 「時區」). A naive `new Date(str)` +
    // locale-aware formatting would reinterpret this as UTC and shift it by the local
    // runtime's offset — e.g. this exact value would render as `2026-08-31 07:15` under
    // an implementation that mistakenly re-converts it. The frontend must do a plain
    // string format only.
    progressResponder = () => progressResponse({ lastSyncedAt: '2026-08-30T23:15:00' })
    renderTab()
    await waitFor(() => expect(screen.getByText('最後同步：2026-08-30 23:15')).toBeInTheDocument())
    expect(screen.queryByText(/最後同步：2026-08-31/)).not.toBeInTheDocument()
  })

  it('shows 已是最新，無需更新（N 檔） — not 完成 N 檔 — when caughtUpCount equals targetCount', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    backfillResponder = () => ({
      status: 202,
      body: { jobType: 'PRICE_BACKFILL', targetCount: 34, caughtUpCount: 34, startDate: '2026-01-01', endDate: '2026-08-30', mode: 'ALL' },
    })
    let pollCount = 0
    progressResponder = () => {
      pollCount += 1
      // Call #1 is the on-mount fetch (idle). From call #2 (the poll right after the
      // click) onward the job is already finished — catchUp skipped every target, so
      // zero external requests were made and there is nothing to be "running" about.
      if (pollCount === 1) return progressResponse({ total: 34, pending: 0, running: 0, done: 34 })
      return progressResponse({
        total: 34,
        pending: 0,
        running: 0,
        done: 34,
        failed: 0,
        skipped: 0,
        lastSyncedAt: '2026-08-30T13:05:00',
      })
    }
    renderTab()
    await waitFor(() => expect(screen.getByRole('button', { name: '同步日 K 至今日' })).toBeInTheDocument())

    fireEvent.click(screen.getByRole('button', { name: '同步日 K 至今日' }))

    await vi.waitFor(() => expect(screen.getByText('已是最新，無需更新（34 檔上市普通股）')).toBeInTheDocument())
    expect(screen.queryByText(/^完成 /)).not.toBeInTheDocument()
    expect(screen.getByText('最後同步：2026-08-30 13:05')).toBeInTheDocument()
    vi.useRealTimers()
  })

  it('shows 完成／失敗／略過 counts plus an 另 N 檔已是最新 note when only some targets were already caught up', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    backfillResponder = () => ({
      status: 202,
      body: { jobType: 'PRICE_BACKFILL', targetCount: 34, caughtUpCount: 10, startDate: '2026-01-01', endDate: '2026-08-30', mode: 'ALL' },
    })
    let pollCount = 0
    progressResponder = () => {
      pollCount += 1
      if (pollCount === 1) return progressResponse({ total: 34, pending: 0, running: 0, done: 34 })
      if (pollCount === 2) return progressResponse({ total: 34, pending: 15, running: 1, done: 8, failed: 0, skipped: 0 })
      return progressResponse({
        total: 34,
        pending: 0,
        running: 0,
        done: 20,
        failed: 2,
        skipped: 2,
        lastSyncedAt: '2026-08-30T13:10:00',
      })
    }
    renderTab()
    await waitFor(() => expect(screen.getByRole('button', { name: '同步日 K 至今日' })).toBeInTheDocument())

    fireEvent.click(screen.getByRole('button', { name: '同步日 K 至今日' }))
    await vi.waitFor(() => expect(screen.getByText('已完成 8 / 34 檔')).toBeInTheDocument())

    await vi.advanceTimersByTimeAsync(5000)
    await vi.waitFor(() => expect(document.body.textContent).toContain('完成 20 檔上市普通股／失敗 2 檔／略過 2 檔'))
    expect(document.body.textContent).toContain('另 10 檔已是最新')
    vi.useRealTimers()
  })

  it('keeps the completion summary visible after a near-instant sync instead of reverting to an unchanged-looking screen', async () => {
    // Reproduces the reported bug directly: every target already caught up means the
    // whole job (accept -> catchUp-skip everything -> finish) can complete inside a
    // single poll cycle. The running state is never observably "in progress" for more
    // than an instant, so the completion summary is the only signal the user gets that
    // anything happened at all — it must not disappear once the running flash passes.
    vi.useFakeTimers({ shouldAdvanceTime: true })
    backfillResponder = () => ({
      status: 202,
      body: { jobType: 'PRICE_BACKFILL', targetCount: 34, caughtUpCount: 34, startDate: '2026-01-01', endDate: '2026-08-30', mode: 'ALL' },
    })
    let pollCount = 0
    progressResponder = () => {
      pollCount += 1
      if (pollCount === 1) return progressResponse({ total: 34, pending: 0, running: 0, done: 34 })
      return progressResponse({ total: 34, pending: 0, running: 0, done: 34, failed: 0, skipped: 0, lastSyncedAt: '2026-08-30T13:20:00' })
    }
    renderTab()
    await waitFor(() => expect(screen.getByRole('button', { name: '同步日 K 至今日' })).toBeInTheDocument())

    fireEvent.click(screen.getByRole('button', { name: '同步日 K 至今日' }))
    await vi.waitFor(() => expect(screen.getByText('已是最新，無需更新（34 檔上市普通股）')).toBeInTheDocument())

    // Nothing else happens afterward (no further polling once idle) — the summary must
    // still be there well after the fact, and the button must have reverted to its
    // normal label rather than staying stuck showing "同步中…".
    await vi.advanceTimersByTimeAsync(15000)
    expect(screen.getByText('已是最新，無需更新（34 檔上市普通股）')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '同步日 K 至今日' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '同步中…' })).not.toBeInTheDocument()
    vi.useRealTimers()
  })

  // ---------------- 同步日 K 至今日 follows the page-level commonStocksOnly setting ----------------

  it('sends commonStocksOnly matching the page-level setting when clicking 同步日 K 至今日', async () => {
    renderTab(true)
    await waitFor(() => expect(screen.getByRole('button', { name: '同步日 K 至今日' })).toBeInTheDocument())
    fireEvent.click(screen.getByRole('button', { name: '同步日 K 至今日' }))

    const backfillCall = await vi.waitFor(() =>
      fetchMock.mock.calls.find((c) => String(c[0]).startsWith('/api/stocks/sync/backfill')),
    )
    const body = JSON.parse((backfillCall![1] as RequestInit).body as string)
    expect(body.commonStocksOnly).toBe(true)
  })

  it('shows 完成 N 檔上市普通股 (not bare 完成 N) when commonStocksOnly is true, with targetCount ≤ 共 N 檔', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    stocksTotal = 1400 // 常駐「共 N 檔」comes from GET /api/stocks, independent of the sync population.
    backfillResponder = () => ({
      status: 202,
      body: {
        jobType: 'PRICE_BACKFILL',
        targetCount: 1051,
        caughtUpCount: 0,
        startDate: '2026-01-01',
        endDate: '2026-08-30',
        mode: 'ALL',
        commonStocksOnly: true,
      },
    })
    let pollCount = 0
    progressResponder = () => {
      pollCount += 1
      if (pollCount === 1) return progressResponse({ total: 1051, pending: 0, running: 0, done: 1051 })
      return progressResponse({
        total: 1051,
        pending: 0,
        running: 0,
        done: 1051,
        failed: 0,
        skipped: 0,
        lastSyncedAt: '2026-08-30T13:00:00',
      })
    }
    renderTab(true)
    await waitFor(() => expect(screen.getByText('股票清單：共 1400 檔')).toBeInTheDocument())

    fireEvent.click(screen.getByRole('button', { name: '同步日 K 至今日' }))
    await vi.waitFor(() => expect(screen.getByText(/完成 1051 檔上市普通股/)).toBeInTheDocument())
    // targetCount (1051) ≤ 共 N 檔 (1400) — the two numbers are deliberately different.
    expect(screen.getByText('股票清單：共 1400 檔')).toBeInTheDocument()
    vi.useRealTimers()
  })

  it('sends commonStocksOnly: false and shows plain 完成 N (targetCount equal to 共 N 檔) when the page-level checkbox is unchecked', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    stocksTotal = 1400
    backfillResponder = () => ({
      status: 202,
      body: {
        jobType: 'PRICE_BACKFILL',
        targetCount: 1400,
        caughtUpCount: 0,
        startDate: '2026-01-01',
        endDate: '2026-08-30',
        mode: 'ALL',
        commonStocksOnly: false,
      },
    })
    let pollCount = 0
    progressResponder = () => {
      pollCount += 1
      if (pollCount === 1) return progressResponse({ total: 1400, pending: 0, running: 0, done: 1400 })
      return progressResponse({
        total: 1400,
        pending: 0,
        running: 0,
        done: 1400,
        failed: 0,
        skipped: 0,
        lastSyncedAt: '2026-08-30T13:00:00',
      })
    }
    renderTab(false)
    await waitFor(() => expect(screen.getByText('股票清單：共 1400 檔')).toBeInTheDocument())

    fireEvent.click(screen.getByRole('button', { name: '同步日 K 至今日' }))
    const backfillCall = await vi.waitFor(() =>
      fetchMock.mock.calls.find((c) => String(c[0]).startsWith('/api/stocks/sync/backfill')),
    )
    const body = JSON.parse((backfillCall![1] as RequestInit).body as string)
    expect(body.commonStocksOnly).toBe(false)

    await vi.waitFor(() => expect(screen.getByText('完成 1400 檔／失敗 0 檔／略過 0 檔')).toBeInTheDocument())
    expect(screen.queryByText(/上市普通股/)).not.toBeInTheDocument()
    vi.useRealTimers()
  })

  it('shows 已是最新，無需更新（N 檔上市普通股） — not 完成 N 檔 — when caughtUpCount equals targetCount and commonStocksOnly is true', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    backfillResponder = () => ({
      status: 202,
      body: {
        jobType: 'PRICE_BACKFILL',
        targetCount: 1051,
        caughtUpCount: 1051,
        startDate: '2026-01-01',
        endDate: '2026-08-30',
        mode: 'ALL',
        commonStocksOnly: true,
      },
    })
    let pollCount = 0
    progressResponder = () => {
      pollCount += 1
      if (pollCount === 1) return progressResponse({ total: 1051, pending: 0, running: 0, done: 1051 })
      return progressResponse({
        total: 1051,
        pending: 0,
        running: 0,
        done: 1051,
        failed: 0,
        skipped: 0,
        lastSyncedAt: '2026-08-30T13:05:00',
      })
    }
    renderTab(true)
    await waitFor(() => expect(screen.getByRole('button', { name: '同步日 K 至今日' })).toBeInTheDocument())

    fireEvent.click(screen.getByRole('button', { name: '同步日 K 至今日' }))
    await vi.waitFor(() => expect(screen.getByText('已是最新，無需更新（1051 檔上市普通股）')).toBeInTheDocument())
    expect(screen.queryByText(/^完成 /)).not.toBeInTheDocument()
    vi.useRealTimers()
  })

  it('does not change 共 N 檔 when the sync population narrows (targetCount is a different number)', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    stocksTotal = 1400
    backfillResponder = () => ({
      status: 202,
      body: {
        jobType: 'PRICE_BACKFILL',
        targetCount: 1051,
        caughtUpCount: 1051,
        startDate: '2026-01-01',
        endDate: '2026-08-30',
        mode: 'ALL',
        commonStocksOnly: true,
      },
    })
    progressResponder = () => progressResponse({ total: 1051, pending: 0, running: 0, done: 1051 })
    renderTab(true)
    await waitFor(() => expect(screen.getByText('股票清單：共 1400 檔')).toBeInTheDocument())

    fireEvent.click(screen.getByRole('button', { name: '同步日 K 至今日' }))
    await vi.waitFor(() => expect(screen.getByText('已是最新，無需更新（1051 檔上市普通股）')).toBeInTheDocument())
    expect(screen.getByText('股票清單：共 1400 檔')).toBeInTheDocument()
    vi.useRealTimers()
  })

  it('shows an error message with a retry button on scan failure, never an empty table', async () => {
    scanResponder = () => {
      throw new Error('network down')
    }
    fetchMock.mockImplementation((url: string) => {
      const u = String(url)
      if (u.startsWith('/api/strategies/scan')) return Promise.reject(new Error('network down'))
      if (u.startsWith('/api/strategies')) return Promise.resolve(jsonResponse(200, CATALOG))
      if (u.startsWith('/api/stocks/sync/progress')) return Promise.resolve(jsonResponse(200, progressResponse()))
      return Promise.resolve(jsonResponse(200, {}))
    })
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    fireEvent.click(within(screen.getByText('箱型突破').closest('.st-strategy-card')!).getByRole('checkbox'))
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))

    await waitFor(() => expect(screen.getByText('掃描失敗，請稍後再試')).toBeInTheDocument())
    expect(screen.getByRole('button', { name: '重試' })).toBeInTheDocument()
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
  })

  it('never renders 建議/推薦/可進場 wording anywhere on the page', async () => {
    scanResponder = () => bothStrategiesResponse()
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    fireEvent.click(within(screen.getByText('箱型突破').closest('.st-strategy-card')!).getByRole('checkbox'))
    fireEvent.click(within(screen.getByText('底底高').closest('.st-strategy-card')!).getByRole('checkbox'))
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() => expect(screen.getByText('命中彙總 — 共 2 檔')).toBeInTheDocument())

    const text = document.body.textContent ?? ''
    expect(text).not.toMatch(/建議|推薦|可進場/)
  })

  // ---------- 更新股票清單 (POST /api/stocks/universe/import) ----------

  it('shows both sync-row buttons as secondary style, in 更新股票清單 → 同步日 K 至今日 order, with 開始掃描 as the page\'s only primary button', async () => {
    renderTab()
    await waitFor(() => expect(screen.getByRole('button', { name: '更新股票清單' })).toBeInTheDocument())
    const importBtn = screen.getByRole('button', { name: '更新股票清單' })
    const syncBtn = screen.getByRole('button', { name: '同步日 K 至今日' })
    const scanBtn = screen.getByRole('button', { name: '開始掃描' })

    expect(importBtn.className).not.toContain('sl-btn-primary')
    expect(syncBtn.className).not.toContain('sl-btn-primary')
    expect(scanBtn.className).toContain('sl-btn-primary')
    // 更新股票清單 precedes 同步日 K 至今日 in document order (left-to-right in the row)
    expect(importBtn.compareDocumentPosition(syncBtn) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })

  it('shows the persistent 股票清單「共 N 檔」 from GET /api/stocks?page=1&size=1 on entry', async () => {
    stocksTotal = 1234
    renderTab()
    await waitFor(() => expect(screen.getByText('股票清單：共 1234 檔')).toBeInTheDocument())
  })

  it('imports the stock universe: disabled running state, then a completion summary that updates the persistent count, without polling a progress endpoint or auto-triggering sync/scan', async () => {
    stocksTotal = 34
    let resolveImport!: (value: { status: number; body: unknown }) => void
    const importPromise = new Promise<{ status: number; body: unknown }>((resolve) => {
      resolveImport = resolve
    })
    fetchMock.mockImplementation((url: string, init?: RequestInit) => {
      const u = String(url)
      const method = init?.method ?? 'GET'
      if (u.startsWith('/api/stocks/universe/import') && method === 'POST') {
        return importPromise.then(({ status, body }) => jsonResponse(status, body))
      }
      if (u.startsWith('/api/strategies/scan')) return Promise.resolve(jsonResponse(200, scanResponder()))
      if (u.startsWith('/api/strategies')) return Promise.resolve(jsonResponse(200, CATALOG))
      if (u.startsWith('/api/stocks/sync/progress')) return Promise.resolve(jsonResponse(200, progressResponder()))
      if (u.startsWith('/api/stocks/sync/backfill')) {
        const { status, body } = backfillResponder()
        return Promise.resolve(jsonResponse(status, body))
      }
      if (u.startsWith('/api/stocks?')) {
        const params = new URL(u, 'http://x').searchParams
        if (params.get('size') === '1') {
          return Promise.resolve(jsonResponse(200, { page: 1, size: 1, total: stocksTotal, totalPages: stocksTotal, items: [] }))
        }
        return Promise.resolve(jsonResponse(200, { page: 1, size: 20, total: 0, totalPages: 0, items: [] }))
      }
      return Promise.resolve(jsonResponse(200, {}))
    })

    renderTab()
    await waitFor(() => expect(screen.getByText('股票清單：共 34 檔')).toBeInTheDocument())

    fireEvent.click(screen.getByRole('button', { name: '更新股票清單' }))
    expect(screen.getByRole('button', { name: '更新中…' })).toBeDisabled()

    const callsOf = (prefix: string) => fetchMock.mock.calls.filter((c: unknown[]) => String(c[0]).startsWith(prefix)).length
    const progressCallsBefore = callsOf('/api/stocks/sync/progress')
    const scanCallsBefore = callsOf('/api/strategies/scan')
    const backfillCallsBefore = callsOf('/api/stocks/sync/backfill')

    resolveImport({
      status: 200,
      body: {
        fetchedCount: 1377,
        eligibleCount: 1085,
        skippedCount: 292,
        insertedCount: 3,
        updatedCount: 1082,
        totalActiveCount: 1085,
        industrySourceStatus: 'OK',
        industryCount: 35,
        industryLinkedStockCount: 1085,
        uncategorizedStockCount: 289,
      },
    })

    await waitFor(() =>
      expect(
        screen.getByText('股票清單已更新：共 1085 檔（新增 3、更新 1082）・產業別 35 類，未分類 289 檔'),
      ).toBeInTheDocument(),
    )
    expect(screen.getByText('股票清單：共 1085 檔')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '更新股票清單' })).not.toBeDisabled()

    // completion never polled a progress endpoint and never auto-started a sync or a scan
    expect(callsOf('/api/stocks/sync/progress')).toBe(progressCallsBefore)
    expect(callsOf('/api/strategies/scan')).toBe(scanCallsBefore)
    expect(callsOf('/api/stocks/sync/backfill')).toBe(backfillCallsBefore)
  })

  it('keeps the 更新股票清單 completion summary visible across an unrelated 開始掃描, clearing only on the next 更新股票清單 click', async () => {
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())

    fireEvent.click(screen.getByRole('button', { name: '更新股票清單' }))
    await waitFor(() => expect(screen.getByText(/股票清單已更新/)).toBeInTheDocument())

    fireEvent.click(within(screen.getByText('箱型突破').closest('.st-strategy-card')!).getByRole('checkbox'))
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() => expect(screen.getByText('2330 台積電')).toBeInTheDocument())
    expect(screen.getByText(/股票清單已更新/)).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: '更新股票清單' }))
    expect(screen.queryByText(/股票清單已更新/)).not.toBeInTheDocument()
  })

  it('shows「交易所尚未發布今日清單，請稍後再試」on 502 UPSTREAM_EMPTY, leaving 共 N 檔 unchanged', async () => {
    stocksTotal = 34
    universeImportResponder = () => ({ status: 502, body: { code: 'UPSTREAM_EMPTY' } })
    renderTab()
    await waitFor(() => expect(screen.getByText('股票清單：共 34 檔')).toBeInTheDocument())

    fireEvent.click(screen.getByRole('button', { name: '更新股票清單' }))
    await waitFor(() => expect(screen.getByText('交易所尚未發布今日清單，請稍後再試')).toBeInTheDocument())
    expect(screen.getByText('股票清單：共 34 檔')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '更新股票清單' })).not.toBeDisabled()
  })

  it('shows「無法取得交易所股票清單，請稍後再試」on 502 UPSTREAM_UNAVAILABLE, leaving 共 N 檔 unchanged', async () => {
    stocksTotal = 34
    universeImportResponder = () => ({ status: 502, body: { code: 'UPSTREAM_UNAVAILABLE' } })
    renderTab()
    await waitFor(() => expect(screen.getByText('股票清單：共 34 檔')).toBeInTheDocument())

    fireEvent.click(screen.getByRole('button', { name: '更新股票清單' }))
    await waitFor(() => expect(screen.getByText('無法取得交易所股票清單，請稍後再試')).toBeInTheDocument())
    expect(screen.getByText('股票清單：共 34 檔')).toBeInTheDocument()
  })

  it('also shows「無法取得交易所股票清單，請稍後再試」on 502 UPSTREAM_MALFORMED', async () => {
    universeImportResponder = () => ({ status: 502, body: { code: 'UPSTREAM_MALFORMED' } })
    renderTab()
    await waitFor(() => expect(screen.getByRole('button', { name: '更新股票清單' })).toBeInTheDocument())

    fireEvent.click(screen.getByRole('button', { name: '更新股票清單' }))
    await waitFor(() => expect(screen.getByText('無法取得交易所股票清單，請稍後再試')).toBeInTheDocument())
  })

  it('lets 更新股票清單 and 同步日 K 至今日 run concurrently — neither disables the other', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    let progressCallCount = 0
    progressResponder = () => {
      progressCallCount += 1
      return progressCallCount === 1
        ? progressResponse({ pending: 0, running: 0, done: 34 })
        : progressResponse({ pending: 10, running: 1, done: 24 })
    }
    let resolveImport!: (value: { status: number; body: unknown }) => void
    const importPromise = new Promise<{ status: number; body: unknown }>((resolve) => {
      resolveImport = resolve
    })
    const baseImpl = fetchMock.getMockImplementation() as (url: string, init?: RequestInit) => Promise<unknown>
    fetchMock.mockImplementation((url: string, init?: RequestInit) => {
      const u = String(url)
      const method = init?.method ?? 'GET'
      if (u.startsWith('/api/stocks/universe/import') && method === 'POST') {
        return importPromise.then(({ status, body }) => jsonResponse(status, body))
      }
      return baseImpl(url, init)
    })

    renderTab()
    await waitFor(() => expect(screen.getByRole('button', { name: '同步日 K 至今日' })).toBeInTheDocument())

    // start the long-running sync first
    fireEvent.click(screen.getByRole('button', { name: '同步日 K 至今日' }))
    expect(screen.getByRole('button', { name: '同步中…' })).toBeDisabled()
    // 更新股票清單 is unaffected by a running sync
    expect(screen.getByRole('button', { name: '更新股票清單' })).not.toBeDisabled()

    // start 更新股票清單 while the sync is still running
    fireEvent.click(screen.getByRole('button', { name: '更新股票清單' }))
    expect(screen.getByRole('button', { name: '更新中…' })).toBeDisabled()
    // the still-running sync button is unaffected by the import starting
    expect(screen.getByRole('button', { name: '同步中…' })).toBeDisabled()

    resolveImport({
      status: 200,
      body: { fetchedCount: 1, eligibleCount: 1, skippedCount: 0, insertedCount: 0, updatedCount: 1, totalActiveCount: 34 },
    })
    await vi.waitFor(() => expect(screen.getByRole('button', { name: '更新股票清單' })).not.toBeDisabled())
    // the sync (unrelated to the import) is still running afterwards
    expect(screen.getByRole('button', { name: '同步中…' })).toBeDisabled()
    vi.useRealTimers()
  })

  it('shows 產業別 P 類，未分類 Q 檔 in the completion summary when industrySourceStatus is OK, with no warning text', async () => {
    universeImportResponder = () => ({
      status: 200,
      body: {
        fetchedCount: 1377,
        eligibleCount: 1085,
        skippedCount: 292,
        insertedCount: 1,
        updatedCount: 1084,
        totalActiveCount: 1374,
        industrySourceStatus: 'OK',
        industryCount: 35,
        industryLinkedStockCount: 1085,
        uncategorizedStockCount: 289,
      },
    })
    renderTab()
    await waitFor(() => expect(screen.getByRole('button', { name: '更新股票清單' })).toBeInTheDocument())

    fireEvent.click(screen.getByRole('button', { name: '更新股票清單' }))
    await waitFor(() =>
      expect(
        screen.getByText('股票清單已更新：共 1374 檔（新增 1、更新 1084）・產業別 35 類，未分類 289 檔'),
      ).toBeInTheDocument(),
    )
    expect(screen.queryByText('產業別未更新（來源暫時無法取得），股票清單已更新')).not.toBeInTheDocument()
  })

  it('appends a warning that 產業別 was not updated when industrySourceStatus is not OK, without treating the call as an error', async () => {
    universeImportResponder = () => ({
      status: 200,
      body: {
        fetchedCount: 1377,
        eligibleCount: 1085,
        skippedCount: 292,
        insertedCount: 0,
        updatedCount: 1085,
        totalActiveCount: 1374,
        industrySourceStatus: 'UNAVAILABLE',
        industryCount: 35,
        industryLinkedStockCount: 1085,
        uncategorizedStockCount: 289,
      },
    })
    renderTab()
    await waitFor(() => expect(screen.getByRole('button', { name: '更新股票清單' })).toBeInTheDocument())

    fireEvent.click(screen.getByRole('button', { name: '更新股票清單' }))
    // still the normal, non-error summary — the stock-list half genuinely succeeded
    await waitFor(() =>
      expect(
        screen.getByText('股票清單已更新：共 1374 檔（新增 0、更新 1085）・產業別 35 類，未分類 289 檔'),
      ).toBeInTheDocument(),
    )
    const warning = screen.getByText('產業別未更新（來源暫時無法取得），股票清單已更新')
    expect(warning).toBeInTheDocument()
    expect(warning.className).toContain('st-industry-warning')
    expect(screen.getByRole('button', { name: '更新股票清單' })).not.toBeDisabled()
    expect(screen.queryByText(/交易所尚未發布今日清單|無法取得交易所股票清單/)).not.toBeInTheDocument()
  })

  // ---------- 上漲支撐 RISING_SUPPORT ----------

  it('shows a third 上漲支撐 strategy card with name and preset descriptions sourced from GET /api/strategies', async () => {
    renderTab()
    await waitFor(() => expect(screen.getByText('上漲支撐')).toBeInTheDocument())
    // STANDARD is the default preset — its description shows immediately once checked.
    fireEvent.click(within(screen.getByText('上漲支撐').closest('.st-strategy-card')!).getByRole('checkbox'))
    expect(
      screen.getByText('收盤突破前 10 日收盤高點且單日漲幅 ≥ 3%，其後 2 日不跌破起漲收盤'),
    ).toBeInTheDocument()
  })

  it('shows the 上漲支撐-specific pendingConfirm wording, distinct from 箱型突破\'s', async () => {
    scanResponder = () => risingSupportScanResponse()
    renderTab()
    await waitFor(() => expect(screen.getByText('上漲支撐')).toBeInTheDocument())
    fireEvent.click(within(screen.getByText('上漲支撐').closest('.st-strategy-card')!).getByRole('checkbox'))
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))

    await waitFor(() =>
      expect(screen.getByText('上漲支撐：另有 1 檔已上漲，但後兩日的確認尚未完成')).toBeInTheDocument(),
    )
    expect(screen.queryByText(/另有 1 檔已突破，但確認日尚未到/)).not.toBeInTheDocument()
  })

  it('shows the shared insufficientData summary wording for 上漲支撐', async () => {
    scanResponder = () => risingSupportScanResponse()
    renderTab()
    await waitFor(() => expect(screen.getByText('上漲支撐')).toBeInTheDocument())
    fireEvent.click(within(screen.getByText('上漲支撐').closest('.st-strategy-card')!).getByRole('checkbox'))
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))

    await waitFor(() =>
      expect(screen.getByText('上漲支撐：另有 1 檔因區間前的歷史資料不足而未納入判定')).toBeInTheDocument(),
    )
  })

  it('includes 上漲支撐 hits in the union table as 上漲支撐 {signalDate}, excluding its pendingConfirm/insufficientData stocks', async () => {
    scanResponder = () => ({
      startDate: '2026-06-01',
      endDate: '2026-08-30',
      scannedStocks: 6,
      results: [
        { ...boxScanResponse().results[0] },
        { ...risingSupportScanResponse().results[0] },
      ],
    })
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    fireEvent.click(within(screen.getByText('箱型突破').closest('.st-strategy-card')!).getByRole('checkbox'))
    fireEvent.click(within(screen.getByText('上漲支撐').closest('.st-strategy-card')!).getByRole('checkbox'))
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))

    // 2 distinct hit stocks: 2330 (箱型突破) and 2454 (上漲支撐)
    await waitFor(() => expect(screen.getByText('命中彙總 — 共 2 檔')).toBeInTheDocument())
    const unionBlock = screen.getByText('命中彙總 — 共 2 檔').closest('.st-union-block') as HTMLElement
    const row = within(unionBlock).getByText('2454 聯發科').closest('tr') as HTMLElement
    expect(within(row).getByText('上漲支撐')).toBeInTheDocument()
    expect(within(row).getByText('2026-08-26')).toBeInTheDocument()

    // pendingConfirm (3008) and insufficientData (6669) never appear in the union table
    expect(within(unionBlock).queryByText(/3008/)).not.toBeInTheDocument()
    expect(within(unionBlock).queryByText(/6669/)).not.toBeInTheDocument()
  })

  it('shows the two params-driven strategy cards (反彈/累積上漲) with no sensitivity dropdown, each with its own strategy-level description', async () => {
    renderTab()
    await waitFor(() => expect(screen.getByText('反彈')).toBeInTheDocument())
    expect(screen.getByText('累積上漲')).toBeInTheDocument()
    // Both cards' descriptions come from the strategy-level `description`, never a preset's.
    expect(
      screen.getByText('先在回看窗口內自最高收盤跌幅達門檻築出谷底，其後指定天數內自谷底反彈幅度達門檻'),
    ).toBeInTheDocument()
    expect(screen.getByText('回看指定天數，自窗口內最低收盤累積漲幅達門檻的最高點')).toBeInTheDocument()
    // Neither card has a sensitivity dropdown any more.
    expect(within(cardFor('反彈')).queryByRole('combobox')).not.toBeInTheDocument()
    expect(within(cardFor('累積上漲')).queryByRole('combobox')).not.toBeInTheDocument()
    // 反彈 has four labeled inputs, driven entirely by `params`.
    expect(within(cardFor('反彈')).getByLabelText('下跌天數')).toBeInTheDocument()
    expect(within(cardFor('反彈')).getByLabelText('跌幅門檻')).toBeInTheDocument()
    expect(within(cardFor('反彈')).getByLabelText('反彈天數')).toBeInTheDocument()
    expect(within(cardFor('反彈')).getByLabelText('反彈幅度')).toBeInTheDocument()
    // Both day-count inputs (下跌天數/反彈天數) carry the trading-day help text.
    expect(within(cardFor('反彈')).getAllByText('回看的交易日數，不含週末與休市日')).toHaveLength(2)
    // 累積上漲 has a 天數 input with its help text instead.
    expect(within(cardFor('累積上漲')).getByLabelText('天數')).toBeInTheDocument()
    expect(within(cardFor('累積上漲')).getByText('回看的交易日數，不含週末與休市日')).toBeInTheDocument()
  })

  it("shows 反彈's optional group checkbox, labeled from paramGroups[0].name and checked by default", async () => {
    renderTab()
    await waitFor(() => expect(screen.getByText('反彈')).toBeInTheDocument())
    const groupCheckbox = within(cardFor('反彈')).getByLabelText('另外要求反彈漲幅') as HTMLInputElement
    expect(groupCheckbox.checked).toBe(true)
  })

  it("defaults 反彈's four inputs from params (3/10/1/5)", async () => {
    renderTab()
    await waitFor(() => expect(screen.getByText('反彈')).toBeInTheDocument())
    selectStrategy('反彈')
    const card = cardFor('反彈')

    expect((within(card).getByLabelText('下跌天數') as HTMLInputElement).value).toBe('3')
    expect((within(card).getByLabelText('跌幅門檻') as HTMLInputElement).value).toBe('10')
    expect((within(card).getByLabelText('反彈天數') as HTMLInputElement).value).toBe('1')
    expect((within(card).getByLabelText('反彈幅度') as HTMLInputElement).value).toBe('5')
  })

  it("unchecking 反彈's group checkbox disables 反彈天數/反彈幅度 and omits requireRise-guarded fields from the request", async () => {
    scanResponder = () => reboundScanResponse()
    renderTab()
    await waitFor(() => expect(screen.getByText('反彈')).toBeInTheDocument())
    selectStrategy('反彈')
    const card = cardFor('反彈')
    fireEvent.click(within(card).getByLabelText('另外要求反彈漲幅'))

    expect((within(card).getByLabelText('反彈天數') as HTMLInputElement).disabled).toBe(true)
    expect((within(card).getByLabelText('反彈幅度') as HTMLInputElement).disabled).toBe(true)
    // 下跌天數/跌幅門檻 remain enabled — only the grouped pair is affected.
    expect((within(card).getByLabelText('下跌天數') as HTMLInputElement).disabled).toBe(false)
    expect((within(card).getByLabelText('跌幅門檻') as HTMLInputElement).disabled).toBe(false)

    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    const scanCall = await vi.waitFor(() =>
      fetchMock.mock.calls.find((c) => String(c[0]).startsWith('/api/strategies/scan')),
    )
    const body = JSON.parse((scanCall![1] as RequestInit).body as string)
    expect(body.strategies).toEqual([{ code: 'REBOUND', dropDays: 3, dropPercent: 10, requireRise: false }])
  })

  it("sends requireRise/riseDays/risePercent when 反彈's group checkbox stays checked, and never sends preset", async () => {
    scanResponder = () => reboundScanResponse()
    renderTab()
    await waitFor(() => expect(screen.getByText('反彈')).toBeInTheDocument())
    selectStrategy('反彈')
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))

    const scanCall = await vi.waitFor(() =>
      fetchMock.mock.calls.find((c) => String(c[0]).startsWith('/api/strategies/scan')),
    )
    const body = JSON.parse((scanCall![1] as RequestInit).body as string)
    expect(body.strategies).toEqual([
      { code: 'REBOUND', dropDays: 3, dropPercent: 10, requireRise: true, riseDays: 1, risePercent: 5 },
    ])
  })

  it("sends 反彈's dropDays/dropPercent/requireRise (not preset) while other selected strategies still send preset", async () => {
    scanResponder = () => allFiveStrategiesResponse()
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    selectStrategy('箱型突破')
    selectStrategy('反彈')
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))

    const scanCall = await vi.waitFor(() =>
      fetchMock.mock.calls.find((c) => String(c[0]).startsWith('/api/strategies/scan')),
    )
    const body = JSON.parse((scanCall![1] as RequestInit).body as string)
    expect(body.strategies).toEqual([
      { code: 'BOX_BREAKOUT', preset: 'STANDARD', risePercent: 1.5 },
      { code: 'REBOUND', dropDays: 3, dropPercent: 10, requireRise: true, riseDays: 1, risePercent: 5 },
    ])
  })

  it('disables all four 反彈 inputs and the group checkbox when unchecked, and omits it from the request', async () => {
    renderTab()
    await waitFor(() => expect(screen.getByText('反彈')).toBeInTheDocument())
    const card = cardFor('反彈')
    expect((within(card).getByLabelText('下跌天數') as HTMLInputElement).disabled).toBe(true)
    expect((within(card).getByLabelText('跌幅門檻') as HTMLInputElement).disabled).toBe(true)
    expect((within(card).getByLabelText('反彈天數') as HTMLInputElement).disabled).toBe(true)
    expect((within(card).getByLabelText('反彈幅度') as HTMLInputElement).disabled).toBe(true)
    expect((within(card).getByLabelText('另外要求反彈漲幅') as HTMLInputElement).disabled).toBe(true)

    selectStrategy('箱型突破')
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    const scanCall = await vi.waitFor(() =>
      fetchMock.mock.calls.find((c) => String(c[0]).startsWith('/api/strategies/scan')),
    )
    const body = JSON.parse((scanCall![1] as RequestInit).body as string)
    expect(body.strategies.some((s: { code: string }) => s.code === 'REBOUND')).toBe(false)
  })

  it.each([
    ['下跌天數', '0'],
    ['下跌天數', '91'],
    ['下跌天數', '3.5'],
    ['反彈天數', '0'],
    ['反彈天數', '91'],
    ['反彈天數', '1.5'],
  ])('blocks the scan and shows <參數>需介於 1 ~ 90 的整數 for an invalid %s (%s), without sending a request', async (label, bad) => {
    renderTab()
    await waitFor(() => expect(screen.getByText('反彈')).toBeInTheDocument())
    const card = cardFor('反彈')
    selectStrategy('反彈')
    const input = within(card).getByLabelText(label) as HTMLInputElement
    fireEvent.change(input, { target: { value: bad } })

    expect(within(card).getByText(`${label}需介於 1 ~ 90 的整數`)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '開始掃描' })).toBeDisabled()

    const scanCalls = fetchMock.mock.calls.filter((c) => String(c[0]).startsWith('/api/strategies/scan')).length
    expect(scanCalls).toBe(0)
  })

  it.each([
    ['跌幅門檻', '-1'],
    ['跌幅門檻', '50.1'],
    ['跌幅門檻', '10.55'],
    ['反彈幅度', '-1'],
    ['反彈幅度', '50.1'],
    ['反彈幅度', '10.55'],
  ])('blocks the scan and shows <參數>需介於 0 ~ 50 for an invalid %s (%s), without sending a request', async (label, bad) => {
    renderTab()
    await waitFor(() => expect(screen.getByText('反彈')).toBeInTheDocument())
    const card = cardFor('反彈')
    selectStrategy('反彈')
    const input = within(card).getByLabelText(label) as HTMLInputElement
    fireEvent.change(input, { target: { value: bad } })

    expect(within(card).getByText(`${label}需介於 0 ~ 50`)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '開始掃描' })).toBeDisabled()

    const scanCalls = fetchMock.mock.calls.filter((c) => String(c[0]).startsWith('/api/strategies/scan')).length
    expect(scanCalls).toBe(0)
  })

  it('shows the 下跌天數為 1 hint (not blocking 開始掃描 or the request) only when 下跌天數=1 and 跌幅門檻 > 0', async () => {
    scanResponder = () => reboundScanResponse()
    renderTab()
    await waitFor(() => expect(screen.getByText('反彈')).toBeInTheDocument())
    const card = cardFor('反彈')
    selectStrategy('反彈')
    fireEvent.change(within(card).getByLabelText('下跌天數'), { target: { value: '1' } })

    expect(within(card).getByText('下跌天數為 1 時窗口只有當天，跌幅恆為 0%，不會有命中')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '開始掃描' })).not.toBeDisabled()

    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    const scanCall = await vi.waitFor(() =>
      fetchMock.mock.calls.find((c) => String(c[0]).startsWith('/api/strategies/scan')),
    )
    const body = JSON.parse((scanCall![1] as RequestInit).body as string)
    expect(body.strategies).toEqual([
      { code: 'REBOUND', dropDays: 1, dropPercent: 10, requireRise: true, riseDays: 1, risePercent: 5 },
    ])
  })

  it.each(['INVALID_DROP_DAYS', 'INVALID_RISE_DAYS', 'INVALID_DROP_PERCENT', 'INVALID_RISE_PERCENT', 'PARAM_NOT_APPLICABLE'])(
    'shows the backend %s error under the 反彈 card, not as a page-wide error',
    async (code) => {
      fetchMock.mockImplementation((url: string) => {
        const u = String(url)
        if (u.startsWith('/api/strategies/scan')) {
          return Promise.resolve(jsonResponse(400, { code, strategy: 'REBOUND', param: 'dropDays' }))
        }
        if (u.startsWith('/api/strategies')) return Promise.resolve(jsonResponse(200, CATALOG))
        if (u.startsWith('/api/stocks/sync/progress')) return Promise.resolve(jsonResponse(200, progressResponse()))
        return Promise.resolve(jsonResponse(200, {}))
      })
      renderTab()
      await waitFor(() => expect(screen.getByText('反彈')).toBeInTheDocument())
      const card = cardFor('反彈')
      selectStrategy('反彈')
      fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))

      // Some inline-error text must appear inside this card specifically...
      await waitFor(() => expect(card.querySelectorAll('.st-inline-error').length).toBeGreaterThan(0))
      // ...and never as the page-wide generic failure message.
      expect(screen.queryByText('掃描失敗，請稍後再試')).not.toBeInTheDocument()
    },
  )

  it('defaults 累積上漲’s 天數/漲幅門檻 inputs from params (20/15), no re-fill on any interaction', async () => {
    renderTab()
    await waitFor(() => expect(screen.getByText('累積上漲')).toBeInTheDocument())
    selectStrategy('累積上漲')

    const daysInput = within(cardFor('累積上漲')).getByLabelText('天數') as HTMLInputElement
    const riseInput = within(cardFor('累積上漲')).getByLabelText('漲幅門檻') as HTMLInputElement
    expect(daysInput.value).toBe('20')
    expect(riseInput.value).toBe('15')

    fireEvent.change(riseInput, { target: { value: '50' } })
    expect(within(cardFor('累積上漲')).queryByText('漲幅門檻需介於 0 ~ 50')).not.toBeInTheDocument()
    fireEvent.change(riseInput, { target: { value: '50.1' } })
    expect(within(cardFor('累積上漲')).getByText('漲幅門檻需介於 0 ~ 50')).toBeInTheDocument()
  })

  it.each(['0', '91', '20.5'])(
    'blocks the scan and shows 天數需介於 1 ~ 90 的整數 for an invalid 天數 (%s), without sending a request',
    async (bad) => {
      renderTab()
      await waitFor(() => expect(screen.getByText('累積上漲')).toBeInTheDocument())
      const card = cardFor('累積上漲')
      fireEvent.click(within(card).getByRole('checkbox'))
      const daysInput = within(card).getByLabelText('天數') as HTMLInputElement
      fireEvent.change(daysInput, { target: { value: bad } })

      expect(within(card).getByText('天數需介於 1 ~ 90 的整數')).toBeInTheDocument()
      expect(screen.getByRole('button', { name: '開始掃描' })).toBeDisabled()

      const scanCalls = fetchMock.mock.calls.filter((c) => String(c[0]).startsWith('/api/strategies/scan')).length
      expect(scanCalls).toBe(0)
    },
  )

  it('shows the 天數為 1 hint (not blocking 開始掃描 or the request) only when 天數=1 and 漲幅門檻 > 0', async () => {
    renderTab()
    await waitFor(() => expect(screen.getByText('累積上漲')).toBeInTheDocument())
    const card = cardFor('累積上漲')
    fireEvent.click(within(card).getByRole('checkbox'))
    const daysInput = within(card).getByLabelText('天數') as HTMLInputElement
    fireEvent.change(daysInput, { target: { value: '1' } })

    expect(within(card).getByText('天數為 1 時窗口只有當天，累積漲幅恆為 0%，不會有命中')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '開始掃描' })).not.toBeDisabled()

    scanResponder = () => cumulativeRiseScanResponse()
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    const scanCall = await vi.waitFor(() =>
      fetchMock.mock.calls.find((c) => String(c[0]).startsWith('/api/strategies/scan')),
    )
    const body = JSON.parse((scanCall![1] as RequestInit).body as string)
    expect(body.strategies).toEqual([{ code: 'CUMULATIVE_RISE', days: 1, risePercent: 15 }])
  })

  it("sends 累積上漲's days/risePercent (not preset) while other selected strategies still send preset", async () => {
    scanResponder = () => allFiveStrategiesResponse()
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    selectStrategy('箱型突破')
    selectStrategy('累積上漲')
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))

    const scanCall = await vi.waitFor(() =>
      fetchMock.mock.calls.find((c) => String(c[0]).startsWith('/api/strategies/scan')),
    )
    const body = JSON.parse((scanCall![1] as RequestInit).body as string)
    expect(body.strategies).toEqual([
      { code: 'BOX_BREAKOUT', preset: 'STANDARD', risePercent: 1.5 },
      { code: 'CUMULATIVE_RISE', days: 20, risePercent: 15 },
    ])
  })

  it('disables both 累積上漲 inputs when unchecked, and omits it from the request', async () => {
    renderTab()
    await waitFor(() => expect(screen.getByText('累積上漲')).toBeInTheDocument())
    const card = cardFor('累積上漲')
    const daysInput = within(card).getByLabelText('天數') as HTMLInputElement
    const riseInput = within(card).getByLabelText('漲幅門檻') as HTMLInputElement
    expect(daysInput.disabled).toBe(true)
    expect(riseInput.disabled).toBe(true)

    selectStrategy('箱型突破')
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    const scanCall = await vi.waitFor(() =>
      fetchMock.mock.calls.find((c) => String(c[0]).startsWith('/api/strategies/scan')),
    )
    const body = JSON.parse((scanCall![1] as RequestInit).body as string)
    expect(body.strategies.some((s: { code: string }) => s.code === 'CUMULATIVE_RISE')).toBe(false)
  })

  it('shows the backend INVALID_DAYS error under the 累積上漲 card, not as a page-wide error', async () => {
    fetchMock.mockImplementation((url: string) => {
      const u = String(url)
      if (u.startsWith('/api/strategies/scan')) {
        return Promise.resolve(jsonResponse(400, { code: 'INVALID_DAYS', strategy: 'CUMULATIVE_RISE' }))
      }
      if (u.startsWith('/api/strategies')) return Promise.resolve(jsonResponse(200, CATALOG))
      if (u.startsWith('/api/stocks/sync/progress')) return Promise.resolve(jsonResponse(200, progressResponse()))
      return Promise.resolve(jsonResponse(200, {}))
    })
    renderTab()
    await waitFor(() => expect(screen.getByText('累積上漲')).toBeInTheDocument())
    const card = cardFor('累積上漲')
    fireEvent.click(within(card).getByRole('checkbox'))
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))

    await waitFor(() => expect(within(card).getByText('天數需介於 1 ~ 90 的整數')).toBeInTheDocument())
    expect(screen.queryByText('掃描失敗，請稍後再試')).not.toBeInTheDocument()
  })


  it.each(['PRESET_NOT_APPLICABLE', 'DAYS_NOT_APPLICABLE'])(
    'shows a generic error under the named card for backend %s when the response carries no `param`, without a page-wide error',
    async (code) => {
      fetchMock.mockImplementation((url: string) => {
        const u = String(url)
        if (u.startsWith('/api/strategies/scan')) {
          return Promise.resolve(jsonResponse(400, { code, strategy: 'REBOUND' }))
        }
        if (u.startsWith('/api/strategies')) return Promise.resolve(jsonResponse(200, CATALOG))
        if (u.startsWith('/api/stocks/sync/progress')) return Promise.resolve(jsonResponse(200, progressResponse()))
        return Promise.resolve(jsonResponse(200, {}))
      })
      renderTab()
      await waitFor(() => expect(screen.getByText('反彈')).toBeInTheDocument())
      const card = cardFor('反彈')
      selectStrategy('反彈')
      fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))

      // A generic message renders inside the named card...
      await waitFor(() => expect(within(card).getByText('此策略不支援這次請求帶入的參數組合')).toBeInTheDocument())
      // ...never the page-wide "掃描失敗" fallback, and never the "帶入了不適用的參數"
      // wording with a made-up field name (the response carries no `param`).
      expect(screen.queryByText('掃描失敗，請稍後再試')).not.toBeInTheDocument()
      expect(within(card).queryByText(/帶入了不適用的參數/)).not.toBeInTheDocument()
    },
  )

  it('shows 帶入了不適用的參數：{param} under a sensitivity-driven card when PRESET_NOT_APPLICABLE names it, with `param` echoed verbatim from the response', async () => {
    fetchMock.mockImplementation((url: string) => {
      const u = String(url)
      if (u.startsWith('/api/strategies/scan')) {
        return Promise.resolve(jsonResponse(400, { code: 'DAYS_NOT_APPLICABLE', strategy: 'BOX_BREAKOUT', param: 'days' }))
      }
      if (u.startsWith('/api/strategies')) return Promise.resolve(jsonResponse(200, CATALOG))
      if (u.startsWith('/api/stocks/sync/progress')) return Promise.resolve(jsonResponse(200, progressResponse()))
      return Promise.resolve(jsonResponse(200, {}))
    })
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    const card = cardFor('箱型突破')
    selectStrategy('箱型突破')
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))

    await waitFor(() => expect(within(card).getByText('帶入了不適用的參數：days')).toBeInTheDocument())
    expect(screen.queryByText('掃描失敗，請稍後再試')).not.toBeInTheDocument()
  })

  it.each(['PRESET_NOT_APPLICABLE', 'DAYS_NOT_APPLICABLE', 'PARAM_NOT_APPLICABLE'])(
    'does not clear existing scan results or disable 開始掃描 when a subsequent scan fails with %s',
    async (code) => {
      scanResponder = () => reboundScanResponse()
      renderTab()
      await waitFor(() => expect(screen.getByText('反彈')).toBeInTheDocument())
      selectStrategy('反彈')
      fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
      await waitFor(() => expect(screen.getByText('2454 聯發科')).toBeInTheDocument())

      fetchMock.mockImplementation((url: string) => {
        const u = String(url)
        if (u.startsWith('/api/strategies/scan')) {
          return Promise.resolve(jsonResponse(400, { code, strategy: 'REBOUND', param: 'dropDays' }))
        }
        if (u.startsWith('/api/strategies')) return Promise.resolve(jsonResponse(200, CATALOG))
        if (u.startsWith('/api/stocks/sync/progress')) return Promise.resolve(jsonResponse(200, progressResponse()))
        return Promise.resolve(jsonResponse(200, {}))
      })
      fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))

      await waitFor(() => expect(cardFor('反彈').querySelectorAll('.st-inline-error').length).toBeGreaterThan(0))
      // The prior result is still on screen...
      expect(screen.getByText('2454 聯發科')).toBeInTheDocument()
      // ...and 開始掃描 remains clickable, not locked into a failed/disabled state.
      expect(screen.getByRole('button', { name: '開始掃描' })).not.toBeDisabled()
      expect(screen.queryByText('掃描失敗，請稍後再試')).not.toBeInTheDocument()
    },
  )

  it('includes 反彈/累積上漲 hits in the union table', async () => {
    scanResponder = () => ({
      startDate: '2026-06-01',
      endDate: '2026-08-30',
      scannedStocks: 6,
      results: [{ ...reboundScanResponse().results[0] }, { ...cumulativeRiseScanResponse().results[0] }],
    })
    renderTab()
    await waitFor(() => expect(screen.getByText('反彈')).toBeInTheDocument())
    selectStrategy('反彈')
    selectStrategy('累積上漲')
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))

    await waitFor(() => expect(screen.getByText('命中彙總 — 共 2 檔')).toBeInTheDocument())
    const unionBlock = screen.getByText('命中彙總 — 共 2 檔').closest('.st-union-block') as HTMLElement
    const row1 = within(unionBlock).getByText('2454 聯發科').closest('tr') as HTMLElement
    expect(within(row1).getByText('反彈')).toBeInTheDocument()
    expect(within(row1).getByText('2026-08-26')).toBeInTheDocument()
    const row2 = within(unionBlock).getByText('2317 鴻海').closest('tr') as HTMLElement
    expect(within(row2).getByText('累積上漲')).toBeInTheDocument()
    expect(within(row2).getByText('2026-08-28')).toBeInTheDocument()
  })

  // ---------------- 漲幅門檻 (per-card override) ----------------

  it('shows an independent 漲幅門檻 input per original card, defaulting to the parsed preset value', async () => {
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    selectStrategy('箱型突破')
    selectStrategy('底底高')
    selectStrategy('上漲支撐')

    const boxInput = within(cardFor('箱型突破')).getByLabelText('漲幅門檻') as HTMLInputElement
    const higherLowsInput = within(cardFor('底底高')).getByLabelText('漲幅門檻') as HTMLInputElement
    const risingSupportInput = within(cardFor('上漲支撐')).getByLabelText('漲幅門檻') as HTMLInputElement

    // STANDARD defaults, parsed from each card's own description text
    // (突破 1.5% / 每段高過 1% / 單日漲幅 ≥ 3%).
    expect(boxInput.value).toBe('1.5')
    expect(higherLowsInput.value).toBe('1')
    expect(risingSupportInput.value).toBe('3')

    fireEvent.change(boxInput, { target: { value: '5' } })
    expect(higherLowsInput.value).toBe('1')
    expect(risingSupportInput.value).toBe('3')
  })

  it("re-fills a card's rise input with the new preset's value when switching sensitivity, overwriting the user's edit", async () => {
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    const card = cardFor('箱型突破')
    fireEvent.click(within(card).getByRole('checkbox'))
    const input = within(card).getByLabelText('漲幅門檻') as HTMLInputElement
    expect(input.value).toBe('1.5')

    fireEvent.change(input, { target: { value: '9.9' } })
    expect(input.value).toBe('9.9')

    fireEvent.change(within(card).getByRole('combobox'), { target: { value: 'STRICT' } })
    expect(input.value).toBe('2')
  })

  it('disables the rise input for an unchecked card, and enables it once checked', async () => {
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    const card = cardFor('箱型突破')
    const input = within(card).getByLabelText('漲幅門檻') as HTMLInputElement
    expect(input.disabled).toBe(true)
    fireEvent.click(within(card).getByRole('checkbox'))
    expect(input.disabled).toBe(false)
  })

  it.each(['-1', '20.5', '2.55'])(
    'blocks the scan and shows 漲幅門檻需介於 0 ~ 20 for an invalid input (%s), without sending a request',
    async (bad) => {
      renderTab()
      await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
      const card = cardFor('箱型突破')
      fireEvent.click(within(card).getByRole('checkbox'))
      const input = within(card).getByLabelText('漲幅門檻') as HTMLInputElement
      fireEvent.change(input, { target: { value: bad } })

      expect(within(card).getByText('漲幅門檻需介於 0 ~ 20')).toBeInTheDocument()
      expect(screen.getByRole('button', { name: '開始掃描' })).toBeDisabled()

      const scanCalls = fetchMock.mock.calls.filter((c) => String(c[0]).startsWith('/api/strategies/scan')).length
      expect(scanCalls).toBe(0)
    },
  )

  it("sends risePercent for every selected strategy, matching each card's current input, even when unchanged from the preset default", async () => {
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    const boxCard = cardFor('箱型突破')
    const higherLowsCard = cardFor('底底高')
    fireEvent.click(within(boxCard).getByRole('checkbox'))
    fireEvent.click(within(higherLowsCard).getByRole('checkbox'))
    fireEvent.change(within(boxCard).getByLabelText('漲幅門檻'), { target: { value: '2.5' } })

    scanResponder = () => bothStrategiesResponse()
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))

    await waitFor(() => expect(screen.getAllByText('2330 台積電').length).toBeGreaterThan(0))
    const scanCall = fetchMock.mock.calls.find((c) => String(c[0]).startsWith('/api/strategies/scan'))!
    const body = JSON.parse((scanCall[1] as RequestInit).body as string)
    expect(body.strategies).toEqual([
      { code: 'BOX_BREAKOUT', preset: 'STANDARD', risePercent: 2.5 },
      { code: 'HIGHER_LOWS', preset: 'STANDARD', risePercent: 1 },
    ])
  })

  it('shows the backend INVALID_RISE_PERCENT error under the named card, not as a page-wide error', async () => {
    fetchMock.mockImplementation((url: string) => {
      const u = String(url)
      if (u.startsWith('/api/strategies/scan')) {
        return Promise.resolve(jsonResponse(400, { code: 'INVALID_RISE_PERCENT', strategy: 'RISING_SUPPORT' }))
      }
      if (u.startsWith('/api/strategies')) return Promise.resolve(jsonResponse(200, CATALOG))
      if (u.startsWith('/api/stocks/sync/progress')) return Promise.resolve(jsonResponse(200, progressResponse()))
      return Promise.resolve(jsonResponse(200, {}))
    })
    renderTab()
    await waitFor(() => expect(screen.getByText('上漲支撐')).toBeInTheDocument())
    const card = cardFor('上漲支撐')
    fireEvent.click(within(card).getByRole('checkbox'))
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))

    await waitFor(() => expect(within(card).getByText('漲幅門檻需介於 0 ~ 20')).toBeInTheDocument())
    expect(screen.queryByText('掃描失敗，請稍後再試')).not.toBeInTheDocument()
  })

  // ---------------- commonStocksOnly (page-level setting) ----------------

  it('sends commonStocksOnly matching the page-level setting for 全市場, and omits it for 指定股票', async () => {
    renderTab(true)
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    selectStrategy('箱型突破')
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() => expect(screen.getByText('2330 台積電')).toBeInTheDocument())

    let scanCall = fetchMock.mock.calls.filter((c) => String(c[0]).startsWith('/api/strategies/scan')).at(-1)!
    let body = JSON.parse((scanCall[1] as RequestInit).body as string)
    expect(body.commonStocksOnly).toBe(true)

    vi.useFakeTimers({ shouldAdvanceTime: true })
    fireEvent.click(screen.getByRole('radio', { name: '指定股票' }))
    const input = screen.getByPlaceholderText('輸入代號或名稱搜尋加入')
    fireEvent.change(input, { target: { value: '2317' } })
    await vi.advanceTimersByTimeAsync(300)
    await vi.waitFor(() => expect(screen.getByText('2317 鴻海')).toBeInTheDocument())
    fireEvent.click(screen.getByText('2317 鴻海'))
    vi.useRealTimers()

    const scanCallsBefore = fetchMock.mock.calls.filter((c) => String(c[0]).startsWith('/api/strategies/scan')).length
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() =>
      expect(
        fetchMock.mock.calls.filter((c) => String(c[0]).startsWith('/api/strategies/scan')).length,
      ).toBeGreaterThan(scanCallsBefore),
    )
    scanCall = fetchMock.mock.calls.filter((c) => String(c[0]).startsWith('/api/strategies/scan')).at(-1)!
    body = JSON.parse((scanCall[1] as RequestInit).body as string)
    expect(body.commonStocksOnly).toBeUndefined()
  })

  it('keeps existing scan results after the page-level commonStocksOnly setting changes, without re-scanning', async () => {
    const { rerender } = renderTab(true)
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    selectStrategy('箱型突破')
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() => expect(screen.getByText('2330 台積電')).toBeInTheDocument())

    const scanCallsBefore = fetchMock.mock.calls.filter((c) => String(c[0]).startsWith('/api/strategies/scan')).length

    rerender(
      <MemoryRouter initialEntries={['/stocks?tab=strategy']}>
        <Routes>
          <Route path="/stocks" element={<StrategyTab commonStocksOnly={false} />} />
          <Route path="/stocks/:stockId/daily" element={<div>daily page for the clicked row</div>} />
        </Routes>
      </MemoryRouter>,
    )

    // Existing result stays exactly as it was — no re-scan triggered by the prop change alone.
    expect(screen.getByText('2330 台積電')).toBeInTheDocument()
    const scanCallsAfter = fetchMock.mock.calls.filter((c) => String(c[0]).startsWith('/api/strategies/scan')).length
    expect(scanCallsAfter).toBe(scanCallsBefore)
  })
  // ---------------- 回測 (POST /api/strategies/backtest) ----------------

  it('shows the merged zero-hit message (not a union table) when 2+ strategies are scanned but none has any hits', async () => {
    scanResponder = () => zeroHitBothResponse()
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    fireEvent.click(within(screen.getByText('箱型突破').closest('.st-strategy-card')!).getByRole('checkbox'))
    fireEvent.click(within(screen.getByText('底底高').closest('.st-strategy-card')!).getByRole('checkbox'))
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))

    await waitFor(() => expect(screen.getByText('命中彙總 — 共 0 檔')).toBeInTheDocument())
    expect(screen.getByText('此區間內沒有命中的股票')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '回測' })).toBeDisabled()
  })

  it('keeps 回測 disabled until scanning succeeds with at least one hit', async () => {
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    expect(screen.getByRole('button', { name: '回測' })).toBeDisabled()

    selectStrategy('箱型突破')
    expect(screen.getByRole('button', { name: '回測' })).toBeDisabled()

    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    // Disabled while the scan itself is in flight.
    expect(screen.getByRole('button', { name: '回測' })).toBeDisabled()

    await waitFor(() => expect(screen.getByRole('button', { name: '回測' })).not.toBeDisabled())
  })

  it('keeps 回測 disabled after a zero-hit scan, and after a failed scan', async () => {
    scanResponder = () => zeroHitResponse()
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    selectStrategy('箱型突破')
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() => expect(screen.getByText('此區間內沒有命中的股票')).toBeInTheDocument())
    expect(screen.getByRole('button', { name: '回測' })).toBeDisabled()

    fetchMock.mockImplementation((url: string) => {
      const u = String(url)
      if (u.startsWith('/api/strategies/scan')) return Promise.resolve(jsonResponse(500, {}))
      if (u.startsWith('/api/strategies')) return Promise.resolve(jsonResponse(200, CATALOG))
      if (u.startsWith('/api/stocks/sync/progress')) return Promise.resolve(jsonResponse(200, progressResponse()))
      return Promise.resolve(jsonResponse(200, {}))
    })
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() => expect(screen.getByText('掃描失敗，請稍後再試')).toBeInTheDocument())
    expect(screen.getByRole('button', { name: '回測' })).toBeDisabled()
  })

  it('shows 回測中… while the request is in flight, disables only 回測 (not 開始掃描), and clears once the response arrives', async () => {
    scanResponder = () => boxScanResponse()
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    selectStrategy('箱型突破')
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() => expect(screen.getByRole('button', { name: '回測' })).not.toBeDisabled())

    let resolveBacktest: (v: unknown) => void = () => {}
    fetchMock.mockImplementation((url: string) => {
      const u = String(url)
      if (u.startsWith('/api/strategies/backtest')) {
        return new Promise((resolve) => {
          resolveBacktest = () => resolve(jsonResponse(200, singleBacktestResponse()))
        })
      }
      if (u.startsWith('/api/strategies/scan')) return Promise.resolve(jsonResponse(200, scanResponder()))
      if (u.startsWith('/api/strategies')) return Promise.resolve(jsonResponse(200, CATALOG))
      if (u.startsWith('/api/stocks/sync/progress')) return Promise.resolve(jsonResponse(200, progressResponse()))
      return Promise.resolve(jsonResponse(200, {}))
    })

    fireEvent.click(screen.getByRole('button', { name: '回測' }))
    expect(screen.getByRole('button', { name: '回測中…' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '開始掃描' })).not.toBeDisabled()

    resolveBacktest(undefined)
    await waitFor(() => expect(screen.getByText('賣出日')).toBeInTheDocument())
    expect(screen.queryByRole('button', { name: '回測中…' })).not.toBeInTheDocument()
  })

  it('shows an error message and leaves the table in its un-backtested shape when 回測 fails', async () => {
    scanResponder = () => boxScanResponse()
    backtestResponder = () => ({ status: 500, body: {} })
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    selectStrategy('箱型突破')
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() => expect(screen.getByRole('button', { name: '回測' })).not.toBeDisabled())

    fireEvent.click(screen.getByRole('button', { name: '回測' }))
    await waitFor(() => expect(screen.getByText('回測失敗，請稍後再試')).toBeInTheDocument())
    expect(screen.getByRole('button', { name: '回測' })).not.toBeDisabled()
    expect(screen.queryByText('賣出日')).not.toBeInTheDocument()
    expect(screen.queryByText('總報酬率')).not.toBeInTheDocument()
  })

  it('sends every hit stock with its latest signalDate, unaffected by checkbox state — and shows the three new columns after success', async () => {
    scanResponder = () => unionScanResponse()
    backtestResponder = () => ({ status: 200, body: unionBacktestResponse() })
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    fireEvent.click(within(screen.getByText('箱型突破').closest('.st-strategy-card')!).getByRole('checkbox'))
    fireEvent.click(within(screen.getByText('底底高').closest('.st-strategy-card')!).getByRole('checkbox'))
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() => expect(screen.getByText('命中彙總 — 共 3 檔')).toBeInTheDocument())

    // Uncheck a row before backtesting — must not change what gets sent.
    fireEvent.click(screen.getByLabelText('納入 2454 計算'))

    fireEvent.click(screen.getByRole('button', { name: '回測' }))
    await waitFor(() => expect(screen.getByText('賣出日')).toBeInTheDocument())

    const backtestCall = fetchMock.mock.calls.find((c) => String(c[0]).startsWith('/api/strategies/backtest'))!
    const body = JSON.parse((backtestCall[1] as RequestInit).body as string)
    expect(body.items).toEqual([
      { stockId: '2317', signalDate: '2026-08-28' },
      { stockId: '2330', signalDate: '2026-08-28' },
      { stockId: '2454', signalDate: '2026-08-20' },
    ])

    const headers = screen.getAllByRole('columnheader').map((h) => h.textContent)
    expect(headers).toEqual(['', '代號 / 名稱', '命中策略與訊號日', '賣出日', '報酬率', '收益（每檔 1 張）'])

    // Row order is unchanged by the backtest — still newest-signalDate desc / stockId asc.
    const table = screen.getByText('命中彙總 — 共 3 檔').closest('.st-union-block')!.querySelector('table')!
    const dataRows = within(table).getAllByRole('row').slice(1)
    expect(dataRows.map((r) => within(r).getAllByRole('cell')[1].textContent)).toEqual([
      '2317 鴻海',
      '2330 台積電',
      '2454 聯發科',
    ])

    // 2454 has no sellable trading day — stays on the table with weak-colored dashes.
    const row2454 = screen.getByText('2454 聯發科').closest('tr') as HTMLElement
    const dashCells = within(row2454).getAllByText('—')
    expect(dashCells.length).toBeGreaterThanOrEqual(3)
    dashCells.forEach((el) => expect(el.className).toContain('sl-muted'))
  })

  it('toggling a row checkbox issues zero network requests (asserted by request count), and updates only that row', async () => {
    scanResponder = () => boxScanResponse()
    backtestResponder = () => ({ status: 200, body: singleBacktestResponse() })
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    selectStrategy('箱型突破')
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() => expect(screen.getByRole('button', { name: '回測' })).not.toBeDisabled())
    fireEvent.click(screen.getByRole('button', { name: '回測' }))
    await waitFor(() => expect(screen.getByText('賣出日')).toBeInTheDocument())

    const callsBefore = fetchMock.mock.calls.length
    const checkbox = screen.getByLabelText('納入 2330 計算') as HTMLInputElement
    expect(checkbox.checked).toBe(true)
    const row = checkbox.closest('tr') as HTMLElement
    expect(row.className).not.toContain('st-row-unchecked')

    fireEvent.click(checkbox)
    expect(fetchMock.mock.calls.length).toBe(callsBefore)
    expect(checkbox.checked).toBe(false)
    expect(row.className).toContain('st-row-unchecked')
    // The row keeps showing its own numbers, not cleared or hidden.
    expect(within(row).getByText('2026-09-01')).toBeInTheDocument()
    const returnCell = within(row).getByText('1.24%')
    expect(returnCell.className).toContain('sl-up')

    fireEvent.click(checkbox)
    expect(fetchMock.mock.calls.length).toBe(callsBefore)
    expect(checkbox.checked).toBe(true)
    expect(row.className).not.toContain('st-row-unchecked')
  })

  it('still navigates to /stocks/{stockId}/daily when an unchecked row is clicked', async () => {
    scanResponder = () => boxScanResponse()
    backtestResponder = () => ({ status: 200, body: singleBacktestResponse() })
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    selectStrategy('箱型突破')
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() => expect(screen.getByRole('button', { name: '回測' })).not.toBeDisabled())
    fireEvent.click(screen.getByRole('button', { name: '回測' }))
    await waitFor(() => expect(screen.getByText('賣出日')).toBeInTheDocument())

    fireEvent.click(screen.getByLabelText('納入 2330 計算'))
    const row = screen.getByText('2330 台積電').closest('tr') as HTMLElement
    fireEvent.click(row)
    await waitFor(() => expect(screen.getByText('daily page for the clicked row')).toBeInTheDocument())
  })

  it("with everything checked, the displayed 總報酬率／總收益 equal the response's totalReturnPercent/totalProfit exactly", async () => {
    scanResponder = () => unionScanResponse()
    backtestResponder = () => ({ status: 200, body: unionBacktestResponse() })
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    fireEvent.click(within(screen.getByText('箱型突破').closest('.st-strategy-card')!).getByRole('checkbox'))
    fireEvent.click(within(screen.getByText('底底高').closest('.st-strategy-card')!).getByRole('checkbox'))
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() => expect(screen.getByText('命中彙總 — 共 3 檔')).toBeInTheDocument())

    fireEvent.click(screen.getByRole('button', { name: '回測' }))
    await waitFor(() => expect(screen.getByText('總報酬率')).toBeInTheDocument())

    const totalValues = Array.from(document.querySelectorAll('.st-total-value')).map((el) => el.textContent)
    expect(totalValues).toEqual(['-3.60%', '-90,000'])
    // 2454 has no sellable day — uncounted for that reason, never "未勾選".
    expect(screen.getByText('另 1 檔尚無可賣出交易日，未計入')).toBeInTheDocument()
    expect(screen.queryByText(/未勾選，未計入/)).not.toBeInTheDocument()
  })

  it('computes the total cost from the response lotSize, not a hard-coded 1000', async () => {
    scanResponder = () => boxScanResponse()
    backtestResponder = () => ({
      status: 200,
      body: {
        asOfDate: '2026-09-10',
        lotSize: 500,
        totalCost: 1210000,
        totalProfit: 15000,
        totalReturnPercent: 1.24,
        backtestedCount: 1,
        items: [
          { stockId: '2330', signalDate: '2026-08-27', buyPrice: 2420, sellDate: '2026-09-01', sellPrice: 2450, returnPercent: 1.24, profit: 15000 },
        ],
      },
    })
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    selectStrategy('箱型突破')
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() => expect(screen.getByRole('button', { name: '回測' })).not.toBeDisabled())
    fireEvent.click(screen.getByRole('button', { name: '回測' }))

    // If the frontend hard-coded lotSize=1000, totalCost would be double (2,420,000) and
    // totalReturnPercent would come out 0.62%, not the correct (lotSize=500) 1.24%.
    // (Both the row's own cell and the total label read 1.24% here, hence getAllByText.)
    await waitFor(() => expect(screen.getAllByText('1.24%').length).toBeGreaterThan(0))
    const totalValues = Array.from(document.querySelectorAll('.st-total-value')).map((el) => el.textContent)
    expect(totalValues).toEqual(['1.24%', '15,000'])
  })

  it('unchecking a row updates the totals to exclude it, and rechecking restores the original totals', async () => {
    scanResponder = () => unionScanResponse()
    backtestResponder = () => ({ status: 200, body: unionBacktestResponse() })
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    fireEvent.click(within(screen.getByText('箱型突破').closest('.st-strategy-card')!).getByRole('checkbox'))
    fireEvent.click(within(screen.getByText('底底高').closest('.st-strategy-card')!).getByRole('checkbox'))
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() => expect(screen.getByText('命中彙總 — 共 3 檔')).toBeInTheDocument())
    fireEvent.click(screen.getByRole('button', { name: '回測' }))
    await waitFor(() => expect(screen.getByText('總報酬率')).toBeInTheDocument())
    expect(Array.from(document.querySelectorAll('.st-total-value')).map((el) => el.textContent)).toEqual([
      '-3.60%',
      '-90,000',
    ])

    // 2330 (profit -100,000) must keep showing its own value throughout.
    const row2330 = screen.getByText('2330 台積電').closest('tr') as HTMLElement
    expect(within(row2330).getByText('-100,000')).toBeInTheDocument()

    fireEvent.click(screen.getByLabelText('納入 2317 計算'))
    await waitFor(() =>
      expect(Array.from(document.querySelectorAll('.st-total-value')).map((el) => el.textContent)).toEqual([
        '-4.17%',
        '-100,000',
      ]),
    )
    expect(within(row2330).getByText('-100,000')).toBeInTheDocument() // unaffected by toggling a different row
    expect(screen.getByText('另 1 檔尚無可賣出交易日，未計入')).toBeInTheDocument()
    expect(screen.getByText('另 1 檔未勾選，未計入')).toBeInTheDocument()

    fireEvent.click(screen.getByLabelText('納入 2317 計算'))
    await waitFor(() =>
      expect(Array.from(document.querySelectorAll('.st-total-value')).map((el) => el.textContent)).toEqual([
        '-3.60%',
        '-90,000',
      ]),
    )
    expect(screen.queryByText('另 1 檔未勾選，未計入')).not.toBeInTheDocument()
  })

  it('shows 沒有可回測的標的 (not 0%) when every hit stock is unbacktestable', async () => {
    scanResponder = () => boxScanResponse()
    backtestResponder = () => ({ status: 200, body: allUnbacktestableResponse() })
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    selectStrategy('箱型突破')
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() => expect(screen.getByRole('button', { name: '回測' })).not.toBeDisabled())
    fireEvent.click(screen.getByRole('button', { name: '回測' }))

    await waitFor(() => expect(screen.getByText('沒有可回測的標的')).toBeInTheDocument())
    const totalValues = Array.from(document.querySelectorAll('.st-total-value')).map((el) => el.textContent)
    expect(totalValues).toEqual(['—', '—'])
    expect(screen.queryByText('0%')).not.toBeInTheDocument()
    expect(screen.queryByText('0.00%')).not.toBeInTheDocument()
  })

  it('shows 未勾選任何標的 (distinct from 沒有可回測的標的) when a backtestable stock exists but every row is unchecked', async () => {
    scanResponder = () => boxScanResponse()
    backtestResponder = () => ({ status: 200, body: singleBacktestResponse() })
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    selectStrategy('箱型突破')
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() => expect(screen.getByRole('button', { name: '回測' })).not.toBeDisabled())
    fireEvent.click(screen.getByRole('button', { name: '回測' }))
    await waitFor(() => expect(screen.getByText('賣出日')).toBeInTheDocument())

    fireEvent.click(screen.getByLabelText('納入 2330 計算'))
    await waitFor(() => expect(screen.getByText('未勾選任何標的')).toBeInTheDocument())
    const totalValues = Array.from(document.querySelectorAll('.st-total-value')).map((el) => el.textContent)
    expect(totalValues).toEqual(['—', '—'])
    expect(screen.queryByText('沒有可回測的標的')).not.toBeInTheDocument()
  })

  it('counts a row that is both unbacktestable and unchecked only under 尚無可賣出交易日, never doubly under 未勾選', async () => {
    scanResponder = () => unionScanResponse()
    backtestResponder = () => ({ status: 200, body: unionBacktestResponse() })
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    fireEvent.click(within(screen.getByText('箱型突破').closest('.st-strategy-card')!).getByRole('checkbox'))
    fireEvent.click(within(screen.getByText('底底高').closest('.st-strategy-card')!).getByRole('checkbox'))
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() => expect(screen.getByText('命中彙總 — 共 3 檔')).toBeInTheDocument())
    fireEvent.click(screen.getByRole('button', { name: '回測' }))
    await waitFor(() => expect(screen.getByText('-3.60%')).toBeInTheDocument())

    // 2454 is already uncounted for lacking a sellable day — unchecking it too must not
    // add a second "未勾選" line or change the totals.
    fireEvent.click(screen.getByLabelText('納入 2454 計算'))
    expect(screen.getByText('另 1 檔尚無可賣出交易日，未計入')).toBeInTheDocument()
    expect(screen.queryByText(/未勾選，未計入/)).not.toBeInTheDocument()
    expect(screen.getByText('-3.60%')).toBeInTheDocument()
    expect(screen.getByText('-90,000')).toBeInTheDocument()
  })

  it('colors 報酬率／收益 and the two totals up-red for positive, down-green for negative, neutral for zero', async () => {
    scanResponder = () => boxScanResponse()
    backtestResponder = () => ({
      status: 200,
      body: {
        asOfDate: '2026-09-10',
        lotSize: 1000,
        totalCost: 2420000,
        totalProfit: 0,
        totalReturnPercent: 0,
        backtestedCount: 1,
        items: [
          { stockId: '2330', signalDate: '2026-08-27', buyPrice: 2420, sellDate: '2026-09-01', sellPrice: 2420, returnPercent: 0, profit: 0 },
        ],
      },
    })
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    selectStrategy('箱型突破')
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() => expect(screen.getByRole('button', { name: '回測' })).not.toBeDisabled())
    fireEvent.click(screen.getByRole('button', { name: '回測' }))

    await waitFor(() => expect(screen.getAllByText('0.00%').length).toBeGreaterThan(0))
    screen.getAllByText('0.00%').forEach((el) => expect(el.className).toContain('sl-neutral'))
    const totalValues = Array.from(document.querySelectorAll('.st-total-value'))
    expect(totalValues.map((el) => el.textContent)).toEqual(['0.00%', '0'])
    totalValues.forEach((el) => expect(el.className).toContain('sl-neutral'))
  })

  it('clears any backtest result and returns 回測 to its un-backtested state the moment 開始掃描 is pressed again', async () => {
    scanResponder = () => boxScanResponse()
    backtestResponder = () => ({ status: 200, body: singleBacktestResponse() })
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    selectStrategy('箱型突破')
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() => expect(screen.getByRole('button', { name: '回測' })).not.toBeDisabled())
    fireEvent.click(screen.getByRole('button', { name: '回測' }))
    await waitFor(() => expect(screen.getByText('賣出日')).toBeInTheDocument())

    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    // Cleared synchronously — before the new scan response even arrives.
    expect(screen.queryByText('賣出日')).not.toBeInTheDocument()
    expect(screen.queryByText('總報酬率')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '回測' })).toBeInTheDocument()

    await waitFor(() => expect(screen.getByText('2330 台積電')).toBeInTheDocument())
  })

  it('resets every row checkbox back to checked when 開始掃描 is pressed again', async () => {
    scanResponder = () => boxScanResponse()
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    selectStrategy('箱型突破')
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() => expect(screen.getByText('2330 台積電')).toBeInTheDocument())

    const checkbox = screen.getByLabelText('納入 2330 計算') as HTMLInputElement
    expect(checkbox.checked).toBe(true)
    fireEvent.click(checkbox)
    expect(checkbox.checked).toBe(false)

    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() => expect((screen.getByLabelText('納入 2330 計算') as HTMLInputElement).checked).toBe(true))
  })
})
