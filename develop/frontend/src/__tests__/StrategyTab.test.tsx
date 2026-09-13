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
            buyDate: '2026-08-27',
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
            buyDate: '2026-08-25',
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
            // Existing (pre-buyDate-increment) fixture, kept at buyDate === signalDate —
            // the dedicated D+2-differs scenario lives in its own fixtures further below
            // (見「以進場日買進（上漲支撐 D+2）」一節新增的 fixtures)。
            buyDate: '2026-08-26',
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
            buyDate: '2026-08-26',
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
            buyDate: '2026-08-28',
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
            buyDate: '2026-08-28',
            detail: { boxHigh: 2380.0, boxLow: 2250.0, breakoutClose: 2420.0, breakoutPercent: 1.68, volumeRatio: 1.82 },
          },
          {
            stockId: '2454',
            stockName: '聯發科',
            signalDate: '2026-08-20',
            buyDate: '2026-08-20',
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
            buyDate: '2026-08-25',
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
            buyDate: '2026-08-28',
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

// Matches boxScanResponse()'s single hit (2330, buyDate 2026-08-27) — the default
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
      { stockId: '2330', buyDate: '2026-08-27', buyPrice: 2420, sellDate: '2026-09-01', sellPrice: 2450, returnPercent: 1.24, profit: 30000 },
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
    items: [{ stockId: '2330', buyDate: '2026-08-27', buyPrice: 2420, sellDate: null, sellPrice: null, returnPercent: null, profit: null }],
  }
}

// Matches unionScanResponse()'s three merged rows (2317, 2330, 2454 — in that sorted
// order): one profitable, one losing, one unbacktestable — exercises totals, colors, and
// both "未計入" reasons together.
// 2330 has TWO distinct signal dates in `unionScanResponse` (BOX_BREAKOUT on 2026-08-28,
// HIGHER_LOWS on 2026-08-25) — the new contract sends one backtest item per distinct date,
// so this fixture carries two entries for 2330 (making it a multi-signal-date / expandable
// row), matching 2317's and 2454's one entry each (single signal date, no expand caret).
//
// Combined totals (everything checked): totalCost = 100,000 (2317) + 2,400,000 + 2,350,000
// (2330's two positions) = 4,850,000; totalProfit = 10,000 + (-100,000) + 50,000 = -40,000;
// totalReturnPercent = -40,000 / 4,850,000 * 100 ≈ -0.82%. 2330's own collapsed-row
// aggregate (both children checked): cost 4,750,000, profit -50,000, return ≈ -1.05%,
// avgBuyPrice = 4,750,000 / (1000 * 2) = 2,375.00.
function unionBacktestResponse() {
  return {
    asOfDate: '2026-09-10',
    lotSize: 1000,
    totalCost: 4850000,
    totalProfit: -40000,
    totalReturnPercent: -0.82,
    backtestedCount: 3,
    items: [
      { stockId: '2317', buyDate: '2026-08-28', buyPrice: 100, sellDate: '2026-09-01', sellPrice: 110, returnPercent: 10, profit: 10000 },
      { stockId: '2330', buyDate: '2026-08-28', buyPrice: 2400, sellDate: '2026-09-02', sellPrice: 2300, returnPercent: -4.17, profit: -100000 },
      { stockId: '2330', buyDate: '2026-08-25', buyPrice: 2350, sellDate: '2026-08-29', sellPrice: 2400, returnPercent: 2.13, profit: 50000 },
      { stockId: '2454', buyDate: '2026-08-20', buyPrice: 900, sellDate: null, sellPrice: null, returnPercent: null, profit: null },
    ],
  }
}

// 「一檔多訊號日的展開列」/「父列的合計」fixtures — 2330 hit by two different strategies on
// two different days, deliberately with wildly different buy prices so a cost-weighted
// parent aggregate is distinguishable from a naive arithmetic mean of its children's own
// return percentages.
function twoDateSingleStockScanResponse() {
  return {
    startDate: '2026-06-01',
    endDate: '2026-08-30',
    scannedStocks: 1,
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
            buyDate: '2026-08-27',
            detail: { boxHigh: 2380.0, boxLow: 2250.0, breakoutClose: 2420.0, breakoutPercent: 1.68, volumeRatio: 1.82 },
          },
        ],
        insufficientData: [],
        pendingConfirm: [],
      },
      {
        strategy: 'HIGHER_LOWS',
        preset: 'STRICT',
        matchedCount: 1,
        items: [
          {
            stockId: '2330',
            stockName: '台積電',
            signalDate: '2026-08-20',
            buyDate: '2026-08-20',
            detail: {
              lows: [
                { tradeDate: '2026-07-01', ma5: 200.0, low: 200.0 },
                { tradeDate: '2026-08-20', ma5: 220.0, low: 220.0 },
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

// Child A (2026-08-27): buyPrice 100 -> sellPrice 110, +10%, profit +10,000.
// Child B (2026-08-20): buyPrice 10,000 -> sellPrice 9,000, -10%, profit -1,000,000.
// Arithmetic mean of the two children's own return% is (10 + -10) / 2 = 0.00% — but the
// cost-weighted aggregate (-990,000 / 10,100,000 * 100 ≈ -9.80%) is what the parent row and
// the header totals must show instead. avgBuyPrice = 10,100,000 / (1000 * 2) = 5,050.00.
function twoDateSingleStockBacktestResponse() {
  return {
    asOfDate: '2026-09-10',
    lotSize: 1000,
    totalCost: 10100000,
    totalProfit: -990000,
    totalReturnPercent: -9.8,
    backtestedCount: 2,
    items: [
      { stockId: '2330', buyDate: '2026-08-27', buyPrice: 100, sellDate: '2026-08-28', sellPrice: 110, returnPercent: 10, profit: 10000 },
      { stockId: '2330', buyDate: '2026-08-20', buyPrice: 10000, sellDate: '2026-08-25', sellPrice: 9000, returnPercent: -10, profit: -1000000 },
    ],
  }
}

// 「訊號日以日期去重，不以策略去重」— 3231 hits two different strategies on the SAME day
// (2026-08-25) plus a third strategy on a different day (2026-08-20): exactly 2 distinct
// signal dates (not 3), and the same-day group carries both strategy tags.
function sameDaySignalScanResponse() {
  return {
    startDate: '2026-06-01',
    endDate: '2026-08-30',
    scannedStocks: 1,
    results: [
      {
        strategy: 'BOX_BREAKOUT',
        preset: 'STANDARD',
        matchedCount: 1,
        items: [
          {
            stockId: '3231',
            stockName: '緯創',
            signalDate: '2026-08-25',
            buyDate: '2026-08-25',
            detail: { boxHigh: 100.0, boxLow: 90.0, breakoutClose: 101.0, breakoutPercent: 1.0, volumeRatio: 1.5 },
          },
        ],
        insufficientData: [],
        pendingConfirm: [],
      },
      {
        strategy: 'HIGHER_LOWS',
        preset: 'STRICT',
        matchedCount: 1,
        items: [
          {
            stockId: '3231',
            stockName: '緯創',
            signalDate: '2026-08-25',
            buyDate: '2026-08-25',
            detail: {
              lows: [
                { tradeDate: '2026-07-01', ma5: 90.0, low: 90.0 },
                { tradeDate: '2026-08-25', ma5: 95.0, low: 95.0 },
              ],
            },
          },
        ],
        insufficientData: [],
        pendingConfirm: [],
      },
      {
        strategy: 'RISING_SUPPORT',
        preset: 'STANDARD',
        matchedCount: 1,
        items: [
          {
            stockId: '3231',
            stockName: '緯創',
            signalDate: '2026-08-20',
            buyDate: '2026-08-20',
            detail: { supportClose: 88.0, riseClose: 92.0, risePercent: 4.5, priorHighClose: 91.0, confirmCloses: [] },
          },
        ],
        insufficientData: [],
        pendingConfirm: [],
      },
    ],
  }
}

function sameDaySignalBacktestResponse() {
  return {
    asOfDate: '2026-09-10',
    lotSize: 1000,
    totalCost: 191000,
    totalProfit: 5000,
    totalReturnPercent: 2.62,
    backtestedCount: 2,
    items: [
      { stockId: '3231', buyDate: '2026-08-25', buyPrice: 101, sellDate: '2026-08-29', sellPrice: 104, returnPercent: 2.97, profit: 3000 },
      { stockId: '3231', buyDate: '2026-08-20', buyPrice: 90, sellDate: '2026-08-26', sellPrice: 92, returnPercent: 2.22, profit: 2000 },
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

  it('has no day-level date input anywhere on the page — the range is week-only', async () => {
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    expect(document.querySelectorAll('input[type="date"]')).toHaveLength(0)
    expect(document.querySelectorAll('input[type="week"]')).toHaveLength(0)
  })

  it('defaults the date range to the 近一個月 week range (起始週 the week one calendar month back, 結束週 this week)', async () => {
    // 2026-08-30 is a Sunday, so it's the last day of ISO week 35 (2026-08-24–08-30).
    // One calendar month back is 2026-07-30 (Thursday), whose ISO week is week 31
    // (2026-07-27–08-02) — the exact example the spec itself uses.
    vi.setSystemTime(new Date('2026-08-30T00:00:00'))
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    expect(screen.getByText('2026 第 31 週（07/27–08/02）')).toBeInTheDocument()
    expect(screen.getByText('2026 第 35 週（08/24–08/30）')).toBeInTheDocument()
    expect(screen.getByText('實際區間 2026-07-27 ~ 2026-08-30')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '近一個月' }).className).toContain('st-shortcut-active')
  })

  it('applies the 近三個月 shortcut, highlights it, and updates 實際區間', async () => {
    vi.setSystemTime(new Date('2026-08-30T00:00:00'))
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    fireEvent.click(screen.getByRole('button', { name: '近三個月' }))
    // 2026-08-30 minus 3 calendar months = 2026-05-30 (Saturday); its ISO week is
    // week 22 (2026-05-25–05-31).
    expect(screen.getByText('2026 第 22 週（05/25–05/31）')).toBeInTheDocument()
    expect(screen.getByText('實際區間 2026-05-25 ~ 2026-08-30')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '近三個月' }).className).toContain('st-shortcut-active')
    expect(screen.getByRole('button', { name: '近一個月' }).className).not.toContain('st-shortcut-active')
  })

  it('applies each of the three date-range shortcuts to the exact expected ISO weeks', async () => {
    // Pinned "today" so every expected week/label below is a literal, independently
    // verified constant — not a re-derivation of the component's own formulas, which
    // would pass even if those formulas were wrong.
    vi.setSystemTime(new Date('2026-08-30T00:00:00'))
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())

    // 近一個月: 2026-08-30 minus 1 calendar month = 2026-07-30 → ISO week 31 (07/27–08/02).
    fireEvent.click(screen.getByRole('button', { name: '近一個月' }))
    expect(screen.getByText('2026 第 31 週（07/27–08/02）')).toBeInTheDocument()
    expect(screen.getByText('實際區間 2026-07-27 ~ 2026-08-30')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '近一個月' }).className).toContain('st-shortcut-active')

    // 近三個月: 2026-08-30 minus 3 calendar months = 2026-05-30 → ISO week 22 (05/25–05/31).
    fireEvent.click(screen.getByRole('button', { name: '近三個月' }))
    expect(screen.getByText('2026 第 22 週（05/25–05/31）')).toBeInTheDocument()
    expect(screen.getByText('實際區間 2026-05-25 ~ 2026-08-30')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '近三個月' }).className).toContain('st-shortcut-active')

    // 近半年: 2026-08-30 minus 6 calendar months lands on the calendar-correct
    // 2026-02-28 (clamped, not overflowed into March — see monthsAgo's own doc
    // comment) → ISO week 9 (02/23–03/01).
    fireEvent.click(screen.getByRole('button', { name: '近半年' }))
    expect(screen.getByText('2026 第 9 週（02/23–03/01）')).toBeInTheDocument()
    expect(screen.getByText('實際區間 2026-02-23 ~ 2026-08-30')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '近半年' }).className).toContain('st-shortcut-active')
  })

  it('resolves a cross-year ISO week correctly: 2027-01-01 belongs to 2026 week 53 (12/28–01/03)', async () => {
    // The 結束週 selector always shows 本週 — pinning "today" at 2027-01-01 exercises the
    // cross-year-boundary case directly instead of trusting the same-year cases above to
    // generalize.
    vi.setSystemTime(new Date('2027-01-01T00:00:00'))
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    expect(screen.getByText('2026 第 53 週（12/28–01/03）')).toBeInTheDocument()
  })

  it('sends endDate = today (not 結束週的週日) when 結束週 is still in progress, and never a future date', async () => {
    // 2026-09-02 is a Wednesday — the middle of ISO week 36 (2026-08-31–09-06). If the
    // component wrongly sent the week's Sunday, endDate would be 2026-09-06, four days
    // in the future relative to "today".
    vi.setSystemTime(new Date('2026-09-02T00:00:00'))
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    expect(screen.getByText('2026 第 36 週（08/31–09/06）')).toBeInTheDocument()
    expect(screen.getByText('實際區間 2026-08-05 ~ 2026-09-02')).toBeInTheDocument()

    fireEvent.click(within(screen.getByText('箱型突破').closest('.st-strategy-card')!).getByRole('checkbox'))
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() => expect(screen.getByText('2330 台積電')).toBeInTheDocument())
    const scanCall = fetchMock.mock.calls.find((c) => String(c[0]).startsWith('/api/strategies/scan'))!
    const body = JSON.parse((scanCall[1] as RequestInit).body as string)
    expect(body.startDate).toBe('2026-08-05')
    expect(body.endDate).toBe('2026-09-02')
  })

  it('cannot step 結束週 past 本週 — the next-week button on the end selector is disabled', async () => {
    vi.setSystemTime(new Date('2026-08-30T00:00:00'))
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    const nextEndWeekButton = screen.getByRole('button', { name: '結束週往後一週' })
    expect(nextEndWeekButton).toBeDisabled()
    fireEvent.click(nextEndWeekButton)
    // Still 本週 — clicking a disabled button is a no-op, not silently clamped after the fact.
    expect(screen.getByText('2026 第 35 週（08/24–08/30）')).toBeInTheDocument()
  })

  it('un-highlights every shortcut once the weeks are manually stepped away from all three results', async () => {
    vi.setSystemTime(new Date('2026-08-30T00:00:00'))
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    fireEvent.click(screen.getByRole('button', { name: '起始週往前一週' }))
    expect(screen.getByRole('button', { name: '近一個月' }).className).not.toContain('st-shortcut-active')
    expect(screen.getByRole('button', { name: '近三個月' }).className).not.toContain('st-shortcut-active')
    expect(screen.getByRole('button', { name: '近半年' }).className).not.toContain('st-shortcut-active')
  })

  it('blocks the scan and shows an inline message when 起始週 is after 結束週', async () => {
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    fireEvent.click(within(screen.getByText('箱型突破').closest('.st-strategy-card')!).getByRole('checkbox'))

    // Step 結束週 back by 10 weeks (70 days) — comfortably more than the ~4-5 week gap
    // any 近一個月-sized default range can have, regardless of what "today" actually is
    // when this test runs, so 結束週 ends up before 起始週 either way.
    const prevEndWeekButton = screen.getByRole('button', { name: '結束週往前一週' })
    for (let i = 0; i < 10; i++) fireEvent.click(prevEndWeekButton)

    expect(screen.getByText('起始週不可晚於結束週')).toBeInTheDocument()
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
    // 代號/名稱 cell is matched by content (not a fixed index) since the checkbox column's
    // presence/absence (掃描成功且命中即自動觸發回測, so it may already exist by now) shifts
    // every column index by one. No child rows appear (default collapsed).
    // 2317 and 2330 tie on the newest signalDate (2026-08-28) -> stockId asc; 2454 (2026-08-20) last
    expect(dataRows.map((r) => within(r).getByText(/^\d{4} /).textContent)).toEqual([
      '2317 鴻海',
      '2330 台積電',
      '2454 聯發科',
    ])

    // 2330 is hit by both strategies — each strategy's own signalDate is listed, not merged.
    // Scoped to the 命中策略與訊號日 cell — the auto-triggered 回測 also adds a 買進日 column
    // that repeats one of these same dates, which would otherwise match twice.
    const stock2330Row = within(unionTable).getByText('2330 台積電').closest('tr')!
    const stock2330HitsCell = stock2330Row.querySelector('.st-union-hits') as HTMLElement
    expect(within(stock2330HitsCell).getByText('箱型突破')).toBeInTheDocument()
    expect(within(stock2330HitsCell).getByText('2026-08-28')).toBeInTheDocument()
    expect(within(stock2330HitsCell).getByText('底底高')).toBeInTheDocument()
    expect(within(stock2330HitsCell).getByText('2026-08-25')).toBeInTheDocument()

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
              buyDate: '2026-08-25',
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

  it('does not navigate, and toggles nothing, when a merged-table row is clicked before 回測 (無勾選框欄時點列本體不發生任何事)', async () => {
    scanResponder = () => unionScanResponse()
    // Delay the auto-triggered 回測 indefinitely so the pre-回測 shape (no checkbox column)
    // can actually be inspected — it would otherwise resolve synchronously and already be
    // in its post-回測 shape by the time the assertions below run.
    fetchMock.mockImplementation((url: string) => {
      const u = String(url)
      if (u.startsWith('/api/strategies/backtest')) return new Promise(() => {})
      if (u.startsWith('/api/strategies/scan')) return Promise.resolve(jsonResponse(200, scanResponder()))
      if (u.startsWith('/api/strategies')) return Promise.resolve(jsonResponse(200, CATALOG))
      if (u.startsWith('/api/stocks/sync/progress')) return Promise.resolve(jsonResponse(200, progressResponse()))
      return Promise.resolve(jsonResponse(200, {}))
    })
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    fireEvent.click(within(screen.getByText('箱型突破').closest('.st-strategy-card')!).getByRole('checkbox'))
    fireEvent.click(within(screen.getByText('底底高').closest('.st-strategy-card')!).getByRole('checkbox'))
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))

    await waitFor(() => expect(screen.getByText('命中彙總 — 共 3 檔')).toBeInTheDocument())
    const unionTable = screen.getByText('命中彙總 — 共 3 檔').closest('.st-union-block')!.querySelector('table')!
    // 回測前沒有勾選框欄 — nothing to toggle either way.
    expect(within(unionTable).queryAllByRole('checkbox')).toHaveLength(0)
    const row = within(unionTable).getByText('2454 聯發科').closest('tr')!
    fireEvent.click(within(unionTable).getByText('2454 聯發科'))
    expect(screen.queryByText('daily page for the clicked row')).not.toBeInTheDocument()
    expect(screen.getByText('命中彙總 — 共 3 檔')).toBeInTheDocument()
    // 滑鼠游標維持預設箭頭；hover 背景 #1D2A38 保留 (only asserted via the class/CSS
    // presence here — the actual rendered `cursor` value is verified in a dedicated
    // computed-style test alongside the other real-Chromium color checks).
    expect(row.className).toContain('sl-row')
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
    // Scoped to the 命中策略與訊號日 cell specifically — the auto-triggered 回測 (default
    // fixture only covers 2330, not 2454) still adds a 買進日 column to every row, which
    // also renders this same signalDate text, so a bare `within(row).getByText(...)` would
    // now match twice.
    const hitsCell = row.querySelector('.st-union-hits') as HTMLElement
    expect(within(hitsCell).getByText('上漲支撐')).toBeInTheDocument()
    expect(within(hitsCell).getByText('2026-08-26')).toBeInTheDocument()

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
    // Scoped to the 命中策略與訊號日 cell — the auto-triggered 回測 (default fixture only
    // covers 2330) still adds a 買進日 column showing this same signalDate to every row.
    const row1 = within(unionBlock).getByText('2454 聯發科').closest('tr') as HTMLElement
    const hitsCell1 = row1.querySelector('.st-union-hits') as HTMLElement
    expect(within(hitsCell1).getByText('反彈')).toBeInTheDocument()
    expect(within(hitsCell1).getByText('2026-08-26')).toBeInTheDocument()
    const row2 = within(unionBlock).getByText('2317 鴻海').closest('tr') as HTMLElement
    const hitsCell2 = row2.querySelector('.st-union-hits') as HTMLElement
    expect(within(hitsCell2).getByText('累積上漲')).toBeInTheDocument()
    expect(within(hitsCell2).getByText('2026-08-28')).toBeInTheDocument()
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
  // ---------------- 掃描後自動回測 (POST /api/strategies/backtest, no 回測 button) ----------------

  it('never renders a 回測 button in any state — 開始掃描 is the page\'s only primary button', async () => {
    scanResponder = () => boxScanResponse()
    backtestResponder = () => ({ status: 200, body: singleBacktestResponse() })
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    expect(screen.queryByRole('button', { name: '回測' })).not.toBeInTheDocument()

    selectStrategy('箱型突破')
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    expect(screen.queryByRole('button', { name: '回測' })).not.toBeInTheDocument()

    await waitFor(() => expect(screen.getByText('賣出日')).toBeInTheDocument())
    expect(screen.queryByRole('button', { name: '回測' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '開始掃描' }).className).toContain('sl-btn-primary')
  })

  it('sends no backtest request when the scan fails or comes back with zero hits (request counting)', async () => {
    // Zero hits across 2+ scanned strategies (not just one) — the merged table's own
    // zero-hit message, not a per-strategy one.
    scanResponder = () => zeroHitBothResponse()
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    fireEvent.click(within(screen.getByText('箱型突破').closest('.st-strategy-card')!).getByRole('checkbox'))
    fireEvent.click(within(screen.getByText('底底高').closest('.st-strategy-card')!).getByRole('checkbox'))

    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() => expect(screen.getByText('命中彙總 — 共 0 檔')).toBeInTheDocument())
    expect(screen.getByText('此區間內沒有命中的股票')).toBeInTheDocument()
    expect(fetchMock.mock.calls.filter((c) => String(c[0]).startsWith('/api/strategies/backtest'))).toHaveLength(0)

    fetchMock.mockImplementation((url: string) => {
      const u = String(url)
      if (u.startsWith('/api/strategies/scan')) return Promise.resolve(jsonResponse(500, {}))
      if (u.startsWith('/api/strategies')) return Promise.resolve(jsonResponse(200, CATALOG))
      if (u.startsWith('/api/stocks/sync/progress')) return Promise.resolve(jsonResponse(200, progressResponse()))
      return Promise.resolve(jsonResponse(200, {}))
    })
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() => expect(screen.getByText('掃描失敗，請稍後再試')).toBeInTheDocument())
    expect(fetchMock.mock.calls.filter((c) => String(c[0]).startsWith('/api/strategies/backtest'))).toHaveLength(0)
  })

  it('automatically sends POST /api/strategies/backtest the moment a scan succeeds with ≥1 hit, deduped to one item per distinct (stockId, buyDate) — no click needed', async () => {
    scanResponder = () => unionScanResponse()
    backtestResponder = () => ({ status: 200, body: unionBacktestResponse() })
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    fireEvent.click(within(screen.getByText('箱型突破').closest('.st-strategy-card')!).getByRole('checkbox'))
    fireEvent.click(within(screen.getByText('底底高').closest('.st-strategy-card')!).getByRole('checkbox'))

    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() => expect(screen.getByText('命中彙總 — 共 3 檔')).toBeInTheDocument())
    await waitFor(() => expect(screen.getByText('賣出日')).toBeInTheDocument())

    const backtestCall = fetchMock.mock.calls.find((c) => String(c[0]).startsWith('/api/strategies/backtest'))!
    const body = JSON.parse((backtestCall[1] as RequestInit).body as string)
    // 2330 hit two strategies on two different days -> two distinct items; 2317/2454 (one
    // signal date each) send exactly one. 4 items total, not 3 (the stock count).
    expect(body.items).toEqual([
      { stockId: '2317', buyDate: '2026-08-28' },
      { stockId: '2330', buyDate: '2026-08-28' },
      { stockId: '2330', buyDate: '2026-08-25' },
      { stockId: '2454', buyDate: '2026-08-20' },
    ])

    // Checkbox column appears at the same moment as the six backtest columns, all checked.
    expect(screen.getByLabelText('納入 2454 計算')).toBeInTheDocument()
    const unionTableEl = screen.getByText('命中彙總 — 共 3 檔').closest('.st-union-block')!.querySelector('table')!
    ;(within(unionTableEl).getAllByRole('checkbox') as HTMLInputElement[]).forEach((cb) => expect(cb.checked).toBe(true))

    const headers = screen.getAllByRole('columnheader').map((h) => h.textContent)
    expect(headers).toEqual([
      '',
      '',
      '代號 / 名稱',
      '命中策略與訊號日',
      '買進日',
      '買進價',
      '賣出日',
      '賣出價',
      '報酬率',
      '收益（每筆 1 張）',
    ])

    // Row order is unchanged by the backtest — still newest-signalDate desc / stockId asc.
    const dataRows = within(unionTableEl).getAllByRole('row').slice(1)
    expect(dataRows.map((r) => within(r).getAllByRole('cell')[2].textContent)).toEqual([
      '2317 鴻海',
      '2330 台積電',
      '2454 聯發科',
    ])

    // 2454 (single signal date, no sellable trading day) stays on the table with
    // weak-colored dashes for sellDate/sellPrice/returnPercent/profit.
    const row2454 = screen.getByText('2454 聯發科').closest('tr') as HTMLElement
    const dashCells = within(row2454).getAllByText('—')
    expect(dashCells.length).toBeGreaterThanOrEqual(4)
    dashCells.forEach((el) => expect(el.className).toContain('sl-muted'))
  })

  it('shows the merged table with no checkbox column while the auto-triggered 回測 is in flight, shows 回測中… beside 開始掃描, and keeps 開始掃描 clickable', async () => {
    scanResponder = () => boxScanResponse()
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
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    selectStrategy('箱型突破')
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))

    await waitFor(() => expect(screen.getByText('2330 台積電')).toBeInTheDocument())
    expect(screen.getByText('回測中…')).toBeInTheDocument()
    expect(screen.queryByLabelText('納入 2330 計算')).not.toBeInTheDocument()
    expect(screen.getAllByRole('columnheader').map((h) => h.textContent)).toEqual(['', '代號 / 名稱', '命中策略與訊號日'])
    expect(screen.getByRole('button', { name: '開始掃描' })).not.toBeDisabled()

    resolveBacktest(undefined)
    await waitFor(() => expect(screen.getByText('賣出日')).toBeInTheDocument())
    expect(screen.queryByText('回測中…')).not.toBeInTheDocument()
  })

  it('clears the backtest columns, totals, 取消全選, error message and 重試回測 the instant 開始掃描 is pressed again', async () => {
    scanResponder = () => boxScanResponse()
    backtestResponder = () => ({ status: 200, body: singleBacktestResponse() })
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    selectStrategy('箱型突破')
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() => expect(screen.getByText('賣出日')).toBeInTheDocument())

    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    expect(screen.queryByText('賣出日')).not.toBeInTheDocument()
    expect(screen.queryByText('總報酬率')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('取消全選')).not.toBeInTheDocument()
    expect(screen.queryByText('回測失敗，請稍後再試')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '重試回測' })).not.toBeInTheDocument()

    await waitFor(() => expect(screen.getByText('2330 台積電')).toBeInTheDocument())
  })

  it('shows 回測失敗，請稍後再試 with a 重試回測 button when the auto-triggered 回測 fails; the table stays in its un-backtested shape', async () => {
    scanResponder = () => boxScanResponse()
    backtestResponder = () => ({ status: 500, body: {} })
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    selectStrategy('箱型突破')
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))

    await waitFor(() => expect(screen.getByText('回測失敗，請稍後再試')).toBeInTheDocument())
    expect(screen.getByRole('button', { name: '重試回測' })).not.toBeDisabled()
    expect(screen.queryByText('賣出日')).not.toBeInTheDocument()
    expect(screen.queryByText('總報酬率')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('取消全選')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('納入 2330 計算')).not.toBeInTheDocument()
  })

  it('toggling a row checkbox issues zero network requests (asserted by request count), and updates only that row', async () => {
    scanResponder = () => boxScanResponse()
    backtestResponder = () => ({ status: 200, body: singleBacktestResponse() })
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    selectStrategy('箱型突破')
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
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

  it('clicking a greyed-out (unchecked) row body checks it back, without navigating', async () => {
    scanResponder = () => boxScanResponse()
    backtestResponder = () => ({ status: 200, body: singleBacktestResponse() })
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    selectStrategy('箱型突破')
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() => expect(screen.getByText('賣出日')).toBeInTheDocument())

    const checkbox = screen.getByLabelText('納入 2330 計算') as HTMLInputElement
    fireEvent.click(checkbox)
    const row = screen.getByText('2330 台積電').closest('tr') as HTMLElement
    expect(row.className).toContain('st-row-unchecked')

    // 點反灰（未勾選）列的列本體會把它勾回，整列恢復一般文字色 — the row body itself (not
    // its checkbox) does the checking-back, and still never navigates.
    fireEvent.click(row)
    expect(screen.queryByText('daily page for the clicked row')).not.toBeInTheDocument()
    expect(checkbox.checked).toBe(true)
    expect(row.className).not.toContain('st-row-unchecked')
  })

  // ---------------- 點選列＝點選該列勾選框 ----------------

  it('clicking a single-signal row body toggles its own checkbox — same result as clicking the checkbox itself', async () => {
    scanResponder = () => boxScanResponse()
    backtestResponder = () => ({ status: 200, body: singleBacktestResponse() })
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    selectStrategy('箱型突破')
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() => expect(screen.getByText('賣出日')).toBeInTheDocument())

    const checkbox = screen.getByLabelText('納入 2330 計算') as HTMLInputElement
    const row = checkbox.closest('tr') as HTMLElement
    expect(checkbox.checked).toBe(true)

    fireEvent.click(row)
    expect(checkbox.checked).toBe(false)
    expect(row.className).toContain('st-row-unchecked')

    fireEvent.click(row)
    expect(checkbox.checked).toBe(true)
    expect(row.className).not.toContain('st-row-unchecked')
  })

  it('clicking a fully-checked parent row body unchecks every child; clicking it again while fully unchecked checks every child', async () => {
    scanResponder = () => twoDateSingleStockScanResponse()
    backtestResponder = () => ({ status: 200, body: twoDateSingleStockBacktestResponse() })
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    fireEvent.click(within(screen.getByText('箱型突破').closest('.st-strategy-card')!).getByRole('checkbox'))
    fireEvent.click(within(screen.getByText('底底高').closest('.st-strategy-card')!).getByRole('checkbox'))
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() => expect(screen.getByText('命中彙總 — 共 1 檔')).toBeInTheDocument())
    await waitFor(() => expect(screen.getByText('賣出日')).toBeInTheDocument())
    fireEvent.click(screen.getByRole('button', { name: '展開 2330 的訊號日明細' }))

    const parentCheckbox = screen.getByLabelText('納入 2330 全部計算') as HTMLInputElement
    const parentRow = parentCheckbox.closest('tr') as HTMLElement
    const childA = screen.getByLabelText('納入 2330 2026-08-27 計算') as HTMLInputElement
    const childB = screen.getByLabelText('納入 2330 2026-08-20 計算') as HTMLInputElement
    expect(parentCheckbox.checked).toBe(true)

    fireEvent.click(parentRow)
    expect(parentCheckbox.checked).toBe(false)
    expect(childA.checked).toBe(false)
    expect(childB.checked).toBe(false)

    fireEvent.click(parentRow)
    expect(parentCheckbox.checked).toBe(true)
    expect(childA.checked).toBe(true)
    expect(childB.checked).toBe(true)
  })

  it('clicking an indeterminate parent row body checks every child, never unchecks them', async () => {
    scanResponder = () => unionScanResponse()
    backtestResponder = () => ({ status: 200, body: unionBacktestResponse() })
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    fireEvent.click(within(screen.getByText('箱型突破').closest('.st-strategy-card')!).getByRole('checkbox'))
    fireEvent.click(within(screen.getByText('底底高').closest('.st-strategy-card')!).getByRole('checkbox'))
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() => expect(screen.getByText('命中彙總 — 共 3 檔')).toBeInTheDocument())
    await waitFor(() => expect(screen.getByText('賣出日')).toBeInTheDocument())
    fireEvent.click(screen.getByRole('button', { name: '展開 2330 的訊號日明細' }))

    fireEvent.click(screen.getByLabelText('納入 2330 2026-08-25 計算')) // -> parent indeterminate
    const parentCheckbox = screen.getByLabelText('納入 2330 全部計算') as HTMLInputElement
    expect(parentCheckbox.indeterminate).toBe(true)
    const parentRow = parentCheckbox.closest('tr') as HTMLElement

    fireEvent.click(parentRow)
    expect(parentCheckbox.checked).toBe(true)
    expect(parentCheckbox.indeterminate).toBe(false)
    expect((screen.getByLabelText('納入 2330 2026-08-25 計算') as HTMLInputElement).checked).toBe(true)
    expect((screen.getByLabelText('納入 2330 2026-08-28 計算') as HTMLInputElement).checked).toBe(true)
  })

  it('clicking a child row body toggles only that child, leaving its sibling and other stocks untouched', async () => {
    scanResponder = () => unionScanResponse()
    backtestResponder = () => ({ status: 200, body: unionBacktestResponse() })
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    fireEvent.click(within(screen.getByText('箱型突破').closest('.st-strategy-card')!).getByRole('checkbox'))
    fireEvent.click(within(screen.getByText('底底高').closest('.st-strategy-card')!).getByRole('checkbox'))
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() => expect(screen.getByText('命中彙總 — 共 3 檔')).toBeInTheDocument())
    await waitFor(() => expect(screen.getByText('賣出日')).toBeInTheDocument())
    fireEvent.click(screen.getByRole('button', { name: '展開 2330 的訊號日明細' }))

    const childA = screen.getByLabelText('納入 2330 2026-08-28 計算') as HTMLInputElement
    const childARow = childA.closest('tr') as HTMLElement
    const childB = screen.getByLabelText('納入 2330 2026-08-25 計算') as HTMLInputElement

    fireEvent.click(childARow)
    expect(childA.checked).toBe(false)
    expect(childB.checked).toBe(true) // sibling untouched
    expect((screen.getByLabelText('納入 2317 計算') as HTMLInputElement).checked).toBe(true)
    expect((screen.getByLabelText('納入 2454 計算') as HTMLInputElement).checked).toBe(true)
    expect(screen.queryByText('daily page for the clicked row')).not.toBeInTheDocument()
  })

  it('clicking directly on the checkbox toggles the row exactly once (not cancelled back out by the row body handler)', async () => {
    scanResponder = () => boxScanResponse()
    backtestResponder = () => ({ status: 200, body: singleBacktestResponse() })
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    selectStrategy('箱型突破')
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() => expect(screen.getByText('賣出日')).toBeInTheDocument())

    const checkbox = screen.getByLabelText('納入 2330 計算') as HTMLInputElement
    expect(checkbox.checked).toBe(true)

    fireEvent.click(checkbox)
    // If the row's own onClick also fired for this same click (i.e. it wasn't stopped from
    // bubbling), the toggle would fire twice and land back on `true` — a bug this asserts
    // against directly, not by timing.
    expect(checkbox.checked).toBe(false)
  })

  it('clicking the expand caret only expands/collapses — it never changes any checkbox on the row or its children', async () => {
    scanResponder = () => twoDateSingleStockScanResponse()
    backtestResponder = () => ({ status: 200, body: twoDateSingleStockBacktestResponse() })
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    fireEvent.click(within(screen.getByText('箱型突破').closest('.st-strategy-card')!).getByRole('checkbox'))
    fireEvent.click(within(screen.getByText('底底高').closest('.st-strategy-card')!).getByRole('checkbox'))
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() => expect(screen.getByText('命中彙總 — 共 1 檔')).toBeInTheDocument())
    await waitFor(() => expect(screen.getByText('賣出日')).toBeInTheDocument())

    const parentCheckbox = screen.getByLabelText('納入 2330 全部計算') as HTMLInputElement
    expect(parentCheckbox.checked).toBe(true)

    fireEvent.click(screen.getByRole('button', { name: '展開 2330 的訊號日明細' }))
    expect(parentCheckbox.checked).toBe(true)
    expect((screen.getByLabelText('納入 2330 2026-08-27 計算') as HTMLInputElement).checked).toBe(true)
    expect((screen.getByLabelText('納入 2330 2026-08-20 計算') as HTMLInputElement).checked).toBe(true)

    fireEvent.click(screen.getByRole('button', { name: '收合 2330 的訊號日明細' }))
    expect(parentCheckbox.checked).toBe(true)
  })

  it('a row-body click recomputes the parent aggregate, both totals and 「取消全選」 exactly like a checkbox click, issuing zero network requests', async () => {
    scanResponder = () => unionScanResponse()
    backtestResponder = () => ({ status: 200, body: unionBacktestResponse() })
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    fireEvent.click(within(screen.getByText('箱型突破').closest('.st-strategy-card')!).getByRole('checkbox'))
    fireEvent.click(within(screen.getByText('底底高').closest('.st-strategy-card')!).getByRole('checkbox'))
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() => expect(screen.getByText('命中彙總 — 共 3 檔')).toBeInTheDocument())
    await waitFor(() => expect(screen.getByText('總報酬率')).toBeInTheDocument())

    const callsBefore = fetchMock.mock.calls.length
    // 2317 alone: cost 100*1000=100,000, profit 10,000 -> excluding it from the combined
    // totals (totalCost 4,850,000 / totalProfit -40,000) leaves cost 4,750,000, profit
    // -50,000, return -50,000/4,750,000*100 ≈ -1.05%.
    const row2317 = (screen.getByLabelText('納入 2317 計算') as HTMLInputElement).closest('tr') as HTMLElement
    fireEvent.click(row2317)
    expect(fetchMock.mock.calls.length).toBe(callsBefore)

    const totalValues = Array.from(document.querySelectorAll('.st-total-value')).map((el) => el.textContent)
    expect(totalValues).toEqual(['-1.05%', '-50,000'])
    expect((screen.getByLabelText('取消全選') as HTMLInputElement).checked).toBe(false)

    // Unchecking every remaining position (2330's two children, then 2454 — itself
    // unbacktestable but still a tracked/checked position) via row clicks drives 取消全選
    // to auto-check, matching the same derivation a checkbox click gets.
    fireEvent.click(screen.getByLabelText('納入 2330 全部計算').closest('tr') as HTMLElement)
    fireEvent.click(screen.getByLabelText('納入 2454 計算').closest('tr') as HTMLElement)
    const totalsAfterAllExcluded = Array.from(document.querySelectorAll('.st-total-value')).map((el) => el.textContent)
    expect(totalsAfterAllExcluded).toEqual(['—', '—'])
    expect(screen.getByText('未勾選任何標的')).toBeInTheDocument()
    expect((screen.getByLabelText('取消全選') as HTMLInputElement).checked).toBe(true)
    expect(fetchMock.mock.calls.length).toBe(callsBefore)
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

    await waitFor(() => expect(screen.getByText('總報酬率')).toBeInTheDocument())

    const totalValues = Array.from(document.querySelectorAll('.st-total-value')).map((el) => el.textContent)
    expect(totalValues).toEqual(['-0.82%', '-40,000'])
    // 2454 has no sellable day — uncounted for that reason, never "未勾選". Counts 筆
    // (positions), not 檔 — only 1 position (2454's) lacks a sellable day.
    expect(screen.getByText('另 1 筆尚無可賣出交易日，未計入')).toBeInTheDocument()
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
          { stockId: '2330', buyDate: '2026-08-27', buyPrice: 2420, sellDate: '2026-09-01', sellPrice: 2450, returnPercent: 1.24, profit: 15000 },
        ],
      },
    })
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    selectStrategy('箱型突破')
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))

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
    await waitFor(() => expect(screen.getByText('總報酬率')).toBeInTheDocument())
    expect(Array.from(document.querySelectorAll('.st-total-value')).map((el) => el.textContent)).toEqual([
      '-0.82%',
      '-40,000',
    ])

    // 2330 is a multi-signal-date parent (2 children, both checked by default) — its
    // collapsed-row aggregate profit (-100,000 + 50,000 = -50,000) must keep showing
    // throughout, unaffected by toggling a different stock's row.
    const row2330 = screen.getByText('2330 台積電').closest('tr') as HTMLElement
    expect(within(row2330).getByText('-50,000')).toBeInTheDocument()

    fireEvent.click(screen.getByLabelText('納入 2317 計算'))
    await waitFor(() =>
      expect(Array.from(document.querySelectorAll('.st-total-value')).map((el) => el.textContent)).toEqual([
        '-1.05%',
        '-50,000',
      ]),
    )
    expect(within(row2330).getByText('-50,000')).toBeInTheDocument() // unaffected by toggling a different row
    expect(screen.getByText('另 1 筆尚無可賣出交易日，未計入')).toBeInTheDocument()
    expect(screen.getByText('另 1 筆未勾選，未計入')).toBeInTheDocument()

    fireEvent.click(screen.getByLabelText('納入 2317 計算'))
    await waitFor(() =>
      expect(Array.from(document.querySelectorAll('.st-total-value')).map((el) => el.textContent)).toEqual([
        '-0.82%',
        '-40,000',
      ]),
    )
    expect(screen.queryByText('另 1 筆未勾選，未計入')).not.toBeInTheDocument()
  })

  it('shows 沒有可回測的標的 (not 0%) when every hit stock is unbacktestable', async () => {
    scanResponder = () => boxScanResponse()
    backtestResponder = () => ({ status: 200, body: allUnbacktestableResponse() })
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    selectStrategy('箱型突破')
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))

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
    await waitFor(() => expect(screen.getByText('-0.82%')).toBeInTheDocument())

    // 2454 is already uncounted for lacking a sellable day — unchecking it too must not
    // add a second "未勾選" line or change the totals.
    fireEvent.click(screen.getByLabelText('納入 2454 計算'))
    expect(screen.getByText('另 1 筆尚無可賣出交易日，未計入')).toBeInTheDocument()
    expect(screen.queryByText(/未勾選，未計入/)).not.toBeInTheDocument()
    expect(screen.getByText('-0.82%')).toBeInTheDocument()
    expect(screen.getByText('-40,000')).toBeInTheDocument()
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
          { stockId: '2330', buyDate: '2026-08-27', buyPrice: 2420, sellDate: '2026-09-01', sellPrice: 2420, returnPercent: 0, profit: 0 },
        ],
      },
    })
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    selectStrategy('箱型突破')
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))

    await waitFor(() => expect(screen.getAllByText('0.00%').length).toBeGreaterThan(0))
    screen.getAllByText('0.00%').forEach((el) => expect(el.className).toContain('sl-neutral'))
    const totalValues = Array.from(document.querySelectorAll('.st-total-value'))
    expect(totalValues.map((el) => el.textContent)).toEqual(['0.00%', '0'])
    totalValues.forEach((el) => expect(el.className).toContain('sl-neutral'))
  })

  it('clears any backtest result and returns the table to its un-backtested state the moment 開始掃描 is pressed again', async () => {
    scanResponder = () => boxScanResponse()
    backtestResponder = () => ({ status: 200, body: singleBacktestResponse() })
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    selectStrategy('箱型突破')
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() => expect(screen.getByText('賣出日')).toBeInTheDocument())

    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    // Cleared synchronously — before the new scan response even arrives.
    expect(screen.queryByText('賣出日')).not.toBeInTheDocument()
    expect(screen.queryByText('總報酬率')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('納入 2330 計算')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '回測' })).not.toBeInTheDocument()

    await waitFor(() => expect(screen.getByText('2330 台積電')).toBeInTheDocument())
  })

  // ---------------- 勾選框改為回測後才出現 ----------------

  it('shows no checkbox column right after a scan, and none while the auto-triggered 回測 is in flight', async () => {
    scanResponder = () => boxScanResponse()
    let resolveBacktest: (v: unknown) => void = () => {}
    fetchMock.mockImplementation((url: string) => {
      const u = String(url)
      if (u.startsWith('/api/strategies/backtest')) {
        return new Promise((resolve) => {
          resolveBacktest = () => resolve(jsonResponse(200, singleBacktestResponse()))
        })
      }
      if (u.startsWith('/api/strategies/scan')) return Promise.resolve(jsonResponse(200, boxScanResponse()))
      if (u.startsWith('/api/strategies')) return Promise.resolve(jsonResponse(200, CATALOG))
      if (u.startsWith('/api/stocks/sync/progress')) return Promise.resolve(jsonResponse(200, progressResponse()))
      return Promise.resolve(jsonResponse(200, {}))
    })
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    selectStrategy('箱型突破')
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() => expect(screen.getByText('2330 台積電')).toBeInTheDocument())

    expect(screen.queryByLabelText('納入 2330 計算')).not.toBeInTheDocument()
    expect(screen.getAllByRole('columnheader').map((h) => h.textContent)).toEqual(['', '代號 / 名稱', '命中策略與訊號日'])
    expect(screen.getByText('回測中…')).toBeInTheDocument()

    resolveBacktest(undefined)
    await waitFor(() => expect(screen.getByLabelText('納入 2330 計算')).toBeInTheDocument())
    expect(screen.queryByText('回測中…')).not.toBeInTheDocument()
  })

  it('does not show a checkbox column when the auto-triggered 回測 fails', async () => {
    scanResponder = () => boxScanResponse()
    backtestResponder = () => ({ status: 500, body: {} })
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    selectStrategy('箱型突破')
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))

    await waitFor(() => expect(screen.getByText('回測失敗，請稍後再試')).toBeInTheDocument())
    expect(screen.queryByLabelText('納入 2330 計算')).not.toBeInTheDocument()
    expect(screen.getAllByRole('columnheader').map((h) => h.textContent)).toEqual(['', '代號 / 名稱', '命中策略與訊號日'])
  })

  it('removes the checkbox column together with the backtest columns on 開始掃描, and shows it all-checked again (never carrying over the old selection) on the next auto-triggered 回測', async () => {
    scanResponder = () => boxScanResponse()
    backtestResponder = () => ({ status: 200, body: singleBacktestResponse() })
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    selectStrategy('箱型突破')
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() => expect(screen.getByLabelText('納入 2330 計算')).toBeInTheDocument())

    fireEvent.click(screen.getByLabelText('納入 2330 計算'))
    expect((screen.getByLabelText('納入 2330 計算') as HTMLInputElement).checked).toBe(false)

    // 重新掃描: checkbox column, three columns and two totals all disappear together.
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    expect(screen.queryByLabelText('納入 2330 計算')).not.toBeInTheDocument()
    expect(screen.queryByText('賣出日')).not.toBeInTheDocument()
    expect(screen.queryByText('總報酬率')).not.toBeInTheDocument()

    await waitFor(() => expect((screen.getByLabelText('納入 2330 計算') as HTMLInputElement).checked).toBe(true))
  })

  it('sends every hit stock to the auto-triggered 回測 regardless of checkbox state (there is none yet at the moment it fires)', async () => {
    scanResponder = () => unionScanResponse()
    backtestResponder = () => ({ status: 200, body: unionBacktestResponse() })
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    fireEvent.click(within(screen.getByText('箱型突破').closest('.st-strategy-card')!).getByRole('checkbox'))
    fireEvent.click(within(screen.getByText('底底高').closest('.st-strategy-card')!).getByRole('checkbox'))
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() => expect(screen.getByText('賣出日')).toBeInTheDocument())

    const backtestCall = fetchMock.mock.calls.find((c) => String(c[0]).startsWith('/api/strategies/backtest'))!
    const body = JSON.parse((backtestCall[1] as RequestInit).body as string)
    expect(body.items.map((i: { stockId: string }) => i.stockId).sort()).toEqual(['2317', '2330', '2330', '2454'])
  })

  it('leaves no control on screen that could re-trigger a backtest against the same successful scan result', async () => {
    scanResponder = () => boxScanResponse()
    backtestResponder = () => ({ status: 200, body: singleBacktestResponse() })
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    selectStrategy('箱型突破')
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() => expect(screen.getByText('賣出日')).toBeInTheDocument())

    expect(screen.queryByRole('button', { name: '回測' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '重試回測' })).not.toBeInTheDocument()
  })

  it('sends the exact same items[] on the auto-triggered 回測 and on 重試回測 after it fails once', async () => {
    scanResponder = () => unionScanResponse()
    let firstBacktestCall = true
    backtestResponder = () => {
      if (firstBacktestCall) {
        firstBacktestCall = false
        return { status: 500, body: {} }
      }
      return { status: 200, body: unionBacktestResponse() }
    }
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    fireEvent.click(within(screen.getByText('箱型突破').closest('.st-strategy-card')!).getByRole('checkbox'))
    fireEvent.click(within(screen.getByText('底底高').closest('.st-strategy-card')!).getByRole('checkbox'))
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() => expect(screen.getByText('回測失敗，請稍後再試')).toBeInTheDocument())

    fireEvent.click(screen.getByRole('button', { name: '重試回測' }))
    await waitFor(() => expect(screen.getByText('賣出日')).toBeInTheDocument())

    const backtestCalls = fetchMock.mock.calls.filter((c) => String(c[0]).startsWith('/api/strategies/backtest'))
    expect(backtestCalls).toHaveLength(2)
    const [firstBody, secondBody] = backtestCalls.map((c) => JSON.parse((c[1] as RequestInit).body as string))
    expect(firstBody.items).toEqual(secondBody.items)
    expect(firstBody.items).toEqual([
      { stockId: '2317', buyDate: '2026-08-28' },
      { stockId: '2330', buyDate: '2026-08-28' },
      { stockId: '2330', buyDate: '2026-08-25' },
      { stockId: '2454', buyDate: '2026-08-20' },
    ])
  })

  // ---------------- 買進／賣出四欄 ----------------

  it('shows all six 買進日/買進價/賣出日/賣出價/報酬率/收益 columns for a single-signal-date row, and the numbers cross-check', async () => {
    scanResponder = () => boxScanResponse()
    backtestResponder = () => ({ status: 200, body: singleBacktestResponse() })
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    selectStrategy('箱型突破')
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() => expect(screen.getByText('賣出日')).toBeInTheDocument())

    expect(screen.getByText('買進日')).toBeInTheDocument()
    expect(screen.getByText('買進價')).toBeInTheDocument()
    expect(screen.getByText('賣出價')).toBeInTheDocument()
    expect(screen.getByText('收益（每筆 1 張）')).toBeInTheDocument()
    expect(screen.queryByText('收益（每檔 1 張）')).not.toBeInTheDocument()

    const row = screen.getByLabelText('納入 2330 計算').closest('tr') as HTMLElement
    // Cell order: 展開鈕, 勾選框, 代號/名稱, 命中策略與訊號日, 買進日, 買進價, 賣出日,
    // 賣出價, 報酬率, 收益 — 買進日 is the signal date itself (singleBacktestResponse's own
    // item), not derived from any separate response field.
    const cells = within(row).getAllByRole('cell').map((c) => c.textContent)
    expect(cells[4]).toBe('2026-08-27')
    expect(cells[5]).toBe('2420.00')
    expect(cells[6]).toBe('2026-09-01')
    expect(cells[7]).toBe('2450.00')
    // (2450 - 2420) / 2420 * 100 ≈ 1.24%; (2450 - 2420) * 1000 = 30,000 — both match the
    // fixture's own returnPercent/profit, cross-checking the display against the formula.
    expect(cells[8]).toBe('1.24%')
    expect(cells[9]).toBe('30,000')
  })

  it('shows a dash for 賣出價 (and the other three backtest cells) when a row is unbacktestable, while 買進日 still shows the signal date', async () => {
    scanResponder = () => boxScanResponse()
    backtestResponder = () => ({ status: 200, body: allUnbacktestableResponse() })
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    selectStrategy('箱型突破')
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() => expect(screen.getByText('賣出日')).toBeInTheDocument())

    const row = screen.getByLabelText('納入 2330 計算').closest('tr') as HTMLElement
    const cells = within(row).getAllByRole('cell').map((c) => c.textContent)
    expect(cells[4]).toBe('2026-08-27') // 買進日 unaffected
    expect(cells[5]).toBe('2420.00') // 買進價 unaffected (non-null)
    const dashCells = within(row).getAllByText('—')
    expect(dashCells.length).toBe(4) // 賣出日/賣出價/報酬率/收益
    dashCells.forEach((el) => expect(el.className).toContain('sl-muted'))
  })

  // ---------------- 一檔多訊號日的展開列 ----------------

  it('shows an expand caret only for stocks with ≥ 2 distinct signal dates, defaulting to collapsed', async () => {
    scanResponder = () => unionScanResponse()
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    fireEvent.click(within(screen.getByText('箱型突破').closest('.st-strategy-card')!).getByRole('checkbox'))
    fireEvent.click(within(screen.getByText('底底高').closest('.st-strategy-card')!).getByRole('checkbox'))
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() => expect(screen.getByText('命中彙總 — 共 3 檔')).toBeInTheDocument())

    // 2330 has 2 distinct signal dates (箱型突破 2026-08-28, 底底高 2026-08-25) -> caret.
    expect(screen.getByRole('button', { name: '展開 2330 的訊號日明細' })).toBeInTheDocument()
    // 2317 and 2454 each have exactly 1 signal date -> no caret at all.
    expect(screen.queryByRole('button', { name: /展開 2317/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /展開 2454/ })).not.toBeInTheDocument()

    // Default collapsed: exactly 3 rows (no child rows yet).
    const unionTable = screen.getByText('命中彙總 — 共 3 檔').closest('.st-union-block')!.querySelector('table')!
    expect(within(unionTable).getAllByRole('row')).toHaveLength(4) // header + 3 rows
  })

  it('expands to one child row per distinct signal date, newest first; neither the caret nor the row body navigates', async () => {
    scanResponder = () => unionScanResponse()
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    fireEvent.click(within(screen.getByText('箱型突破').closest('.st-strategy-card')!).getByRole('checkbox'))
    fireEvent.click(within(screen.getByText('底底高').closest('.st-strategy-card')!).getByRole('checkbox'))
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() => expect(screen.getByText('命中彙總 — 共 3 檔')).toBeInTheDocument())

    fireEvent.click(screen.getByRole('button', { name: '展開 2330 的訊號日明細' }))
    // Caret click must not navigate.
    expect(screen.queryByText('daily page for the clicked row')).not.toBeInTheDocument()

    const unionTable = screen.getByText('命中彙總 — 共 3 檔').closest('.st-union-block')!.querySelector('table')!
    const rows = within(unionTable).getAllByRole('row').slice(1) // drop header
    // 2317 (parent, no children), 2330 (parent), 2330's 2 children (newest date first), 2454.
    expect(rows).toHaveLength(5)
    const childRows = rows.filter((r) => r.className.includes('st-child-row'))
    expect(childRows).toHaveLength(2)
    // Scoped to the 命中策略與訊號日 cell — the auto-triggered 回測 (default fixture only
    // covers a different signalDate) still adds a 買進日 column showing each child's own
    // signalDate, which would otherwise duplicate this same text.
    expect(within(childRows[0].querySelector('.st-union-hits') as HTMLElement).getByText('2026-08-28')).toBeInTheDocument() // newest first
    expect(within(childRows[1].querySelector('.st-union-hits') as HTMLElement).getByText('2026-08-25')).toBeInTheDocument()

    // Clicking the child row body does not navigate either — 子列同樣不可點擊.
    fireEvent.click(childRows[0])
    expect(screen.queryByText('daily page for the clicked row')).not.toBeInTheDocument()
  })

  it('collapses a same-day multi-strategy hit into exactly one child row carrying both strategy tags, and sends one backtest item for that day', async () => {
    scanResponder = () => sameDaySignalScanResponse()
    backtestResponder = () => ({ status: 200, body: sameDaySignalBacktestResponse() })
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    for (const name of ['箱型突破', '底底高', '上漲支撐']) selectStrategy(name)
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() => expect(screen.getByText('命中彙總 — 共 1 檔')).toBeInTheDocument())

    fireEvent.click(screen.getByRole('button', { name: '展開 3231 的訊號日明細' }))
    const unionTable = screen.getByText('命中彙總 — 共 1 檔').closest('.st-union-block')!.querySelector('table')!
    const childRows = within(unionTable).getAllByRole('row').filter((r) => r.className.includes('st-child-row'))
    // 2 distinct dates (08-25 combined, 08-20), not 3 (the number of strategy hits).
    expect(childRows).toHaveLength(2)
    const sameDayChild = childRows.find((r) => r.textContent?.includes('2026-08-25'))!
    expect(within(sameDayChild).getByText('箱型突破')).toBeInTheDocument()
    expect(within(sameDayChild).getByText('底底高')).toBeInTheDocument()

    await waitFor(() => expect(screen.getByText('總報酬率')).toBeInTheDocument())
    const backtestCall = fetchMock.mock.calls.find((c) => String(c[0]).startsWith('/api/strategies/backtest'))!
    const body = JSON.parse((backtestCall[1] as RequestInit).body as string)
    expect(body.items).toEqual([
      { stockId: '3231', buyDate: '2026-08-25' },
      { stockId: '3231', buyDate: '2026-08-20' },
    ])
  })

  it('does not change 共 N 檔 or row order when expanding/collapsing', async () => {
    scanResponder = () => unionScanResponse()
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    fireEvent.click(within(screen.getByText('箱型突破').closest('.st-strategy-card')!).getByRole('checkbox'))
    fireEvent.click(within(screen.getByText('底底高').closest('.st-strategy-card')!).getByRole('checkbox'))
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() => expect(screen.getByText('命中彙總 — 共 3 檔')).toBeInTheDocument())

    fireEvent.click(screen.getByRole('button', { name: '展開 2330 的訊號日明細' }))
    expect(screen.getByText('命中彙總 — 共 3 檔')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '收合 2330 的訊號日明細' }))
    expect(screen.getByText('命中彙總 — 共 3 檔')).toBeInTheDocument()
  })

  // ---------------- 父列的合計 ----------------

  it("shows the parent row's cost-weighted return%/profit/avgBuyPrice — distinct from a naive arithmetic mean of its children's own return%", async () => {
    scanResponder = () => twoDateSingleStockScanResponse()
    backtestResponder = () => ({ status: 200, body: twoDateSingleStockBacktestResponse() })
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    fireEvent.click(within(screen.getByText('箱型突破').closest('.st-strategy-card')!).getByRole('checkbox'))
    fireEvent.click(within(screen.getByText('底底高').closest('.st-strategy-card')!).getByRole('checkbox'))
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() => expect(screen.getByText('命中彙總 — 共 1 檔')).toBeInTheDocument())
    await waitFor(() => expect(screen.getByText('賣出日')).toBeInTheDocument())

    const parentRow = screen.getByLabelText('納入 2330 全部計算').closest('tr') as HTMLElement
    // Arithmetic mean of the children's own return% (+10%, -10%) would be 0.00% — the
    // cost-weighted aggregate must show -9.80% instead.
    expect(within(parentRow).getByText('-9.80%')).toBeInTheDocument()
    expect(within(parentRow).queryByText('0.00%')).not.toBeInTheDocument()
    expect(within(parentRow).getByText('-990,000')).toBeInTheDocument()
    expect(within(parentRow).getByText('5050.00')).toBeInTheDocument() // cost-weighted avg buy price
    // 父列不再顯示「{N} 筆」— replaced by the per-position date lines (見「父列逐筆日期」).
    expect(within(parentRow).queryByText(/^\d+ 筆$/)).not.toBeInTheDocument()
    // 賣出價 is always the muted dash on a multi-signal-date parent row (multiple positions,
    // no single aggregate sell price), right-aligned like every other row's 賣出價.
    const sellPriceCell = within(parentRow).getAllByRole('cell')[7]
    expect(within(sellPriceCell).getByText('—').className).toContain('sl-muted')
    expect(sellPriceCell.className).toContain('sl-r')
  })

  it('recomputes the parent aggregate (and 「{N} 筆」) the instant a child is unchecked, issuing zero network requests', async () => {
    scanResponder = () => twoDateSingleStockScanResponse()
    backtestResponder = () => ({ status: 200, body: twoDateSingleStockBacktestResponse() })
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    fireEvent.click(within(screen.getByText('箱型突破').closest('.st-strategy-card')!).getByRole('checkbox'))
    fireEvent.click(within(screen.getByText('底底高').closest('.st-strategy-card')!).getByRole('checkbox'))
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() => expect(screen.getByText('命中彙總 — 共 1 檔')).toBeInTheDocument())
    await waitFor(() => expect(screen.getByText('賣出日')).toBeInTheDocument())

    fireEvent.click(screen.getByRole('button', { name: '展開 2330 的訊號日明細' }))
    const callsBefore = fetchMock.mock.calls.length
    // Uncheck the 2026-08-20 child (buyPrice 10,000, profit -1,000,000) — only the
    // 2026-08-27 child (profit +10,000) remains counted.
    fireEvent.click(screen.getByLabelText('納入 2330 2026-08-20 計算'))
    expect(fetchMock.mock.calls.length).toBe(callsBefore)

    const parentRow = screen.getByLabelText('納入 2330 全部計算').closest('tr') as HTMLElement
    // 父列不再顯示「{N} 筆」— replaced by the per-position date lines (見「父列逐筆日期」).
    expect(within(parentRow).queryByText(/^\d+ 筆$/)).not.toBeInTheDocument()
    expect(within(parentRow).getByText('10,000')).toBeInTheDocument()
    expect(within(parentRow).getByText('10.00%')).toBeInTheDocument()
    expect(within(parentRow).getByText('100.00')).toBeInTheDocument() // avg buy price = the one remaining child's own buyPrice
    // The unchecked child's own date lines turn muted immediately.
    const cells = within(parentRow).getAllByRole('cell')
    expect(Array.from(cells[4].querySelectorAll('div'))[1].className).toContain('sl-muted')
    expect(Array.from(cells[6].querySelectorAll('div'))[1].className).toContain('sl-muted')
  })

  it('shows dashes (not 0%/0) for 買進價／報酬率／收益 and 「0 筆」 for 賣出日 when every child of a stock is unchecked', async () => {
    scanResponder = () => twoDateSingleStockScanResponse()
    backtestResponder = () => ({ status: 200, body: twoDateSingleStockBacktestResponse() })
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    fireEvent.click(within(screen.getByText('箱型突破').closest('.st-strategy-card')!).getByRole('checkbox'))
    fireEvent.click(within(screen.getByText('底底高').closest('.st-strategy-card')!).getByRole('checkbox'))
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() => expect(screen.getByText('命中彙總 — 共 1 檔')).toBeInTheDocument())
    await waitFor(() => expect(screen.getByText('賣出日')).toBeInTheDocument())

    // Uncheck the whole stock via its (fully-checked) parent checkbox.
    fireEvent.click(screen.getByLabelText('納入 2330 全部計算'))

    const parentRow = screen.getByLabelText('納入 2330 全部計算').closest('tr') as HTMLElement
    // 父列不再顯示「{N} 筆」— replaced by the per-position date lines (見「父列逐筆日期」).
    expect(within(parentRow).queryByText(/^\d+ 筆$/)).not.toBeInTheDocument()
    expect(within(parentRow).queryByText('0%')).not.toBeInTheDocument()
    expect(within(parentRow).queryByText('0.00%')).not.toBeInTheDocument()
    // 買進價/賣出價/報酬率/收益 — 4 muted dashes when the whole stock is unchecked (買進日／
    // 賣出日 still show the actual dates, just muted, per「父列逐筆日期」— dates are never
    // replaced by a dash).
    const dashes = within(parentRow).getAllByText('—')
    expect(dashes.length).toBe(4)
    dashes.forEach((el) => expect(el.className).toContain('sl-muted'))
    // Every date line (both columns, both positions) is muted.
    const cells = within(parentRow).getAllByRole('cell')
    Array.from(cells[4].querySelectorAll('div')).forEach((el) => expect(el.className).toContain('sl-muted'))
    Array.from(cells[6].querySelectorAll('div')).forEach((el) => expect(el.className).toContain('sl-muted'))
    expect(parentRow.className).toContain('st-row-unchecked')
  })

  // ---------------- 父子勾選框連動 ----------------

  it('shows the parent checkbox fully checked by default, and indeterminate the moment exactly one of its children is unchecked', async () => {
    scanResponder = () => unionScanResponse()
    backtestResponder = () => ({ status: 200, body: unionBacktestResponse() })
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    fireEvent.click(within(screen.getByText('箱型突破').closest('.st-strategy-card')!).getByRole('checkbox'))
    fireEvent.click(within(screen.getByText('底底高').closest('.st-strategy-card')!).getByRole('checkbox'))
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() => expect(screen.getByText('命中彙總 — 共 3 檔')).toBeInTheDocument())
    await waitFor(() => expect(screen.getByText('賣出日')).toBeInTheDocument())

    const parentCheckbox = screen.getByLabelText('納入 2330 全部計算') as HTMLInputElement
    expect(parentCheckbox.checked).toBe(true)
    expect(parentCheckbox.indeterminate).toBe(false)

    fireEvent.click(screen.getByRole('button', { name: '展開 2330 的訊號日明細' }))
    fireEvent.click(screen.getByLabelText('納入 2330 2026-08-25 計算'))

    expect(parentCheckbox.checked).toBe(false)
    expect(parentCheckbox.indeterminate).toBe(true)
    // Half-checked must not trigger the「整列反灰」treatment — it still counts toward totals.
    expect((parentCheckbox.closest('tr') as HTMLElement).className).not.toContain('st-row-unchecked')
  })

  it('clicking an indeterminate parent checkbox checks every child (never unchecks them)', async () => {
    scanResponder = () => unionScanResponse()
    backtestResponder = () => ({ status: 200, body: unionBacktestResponse() })
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    fireEvent.click(within(screen.getByText('箱型突破').closest('.st-strategy-card')!).getByRole('checkbox'))
    fireEvent.click(within(screen.getByText('底底高').closest('.st-strategy-card')!).getByRole('checkbox'))
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() => expect(screen.getByText('命中彙總 — 共 3 檔')).toBeInTheDocument())
    await waitFor(() => expect(screen.getByText('賣出日')).toBeInTheDocument())
    fireEvent.click(screen.getByRole('button', { name: '展開 2330 的訊號日明細' }))

    fireEvent.click(screen.getByLabelText('納入 2330 2026-08-25 計算')) // -> indeterminate
    const parentCheckbox = screen.getByLabelText('納入 2330 全部計算') as HTMLInputElement
    expect(parentCheckbox.indeterminate).toBe(true)

    fireEvent.click(parentCheckbox)
    expect(parentCheckbox.checked).toBe(true)
    expect(parentCheckbox.indeterminate).toBe(false)
    expect((screen.getByLabelText('納入 2330 2026-08-25 計算') as HTMLInputElement).checked).toBe(true)
    expect((screen.getByLabelText('納入 2330 2026-08-28 計算') as HTMLInputElement).checked).toBe(true)
  })

  it("unchecking the parent's checkbox unchecks every child, and toggling one child never affects a sibling child or another stock's row", async () => {
    scanResponder = () => unionScanResponse()
    backtestResponder = () => ({ status: 200, body: unionBacktestResponse() })
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    fireEvent.click(within(screen.getByText('箱型突破').closest('.st-strategy-card')!).getByRole('checkbox'))
    fireEvent.click(within(screen.getByText('底底高').closest('.st-strategy-card')!).getByRole('checkbox'))
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() => expect(screen.getByText('命中彙總 — 共 3 檔')).toBeInTheDocument())
    await waitFor(() => expect(screen.getByText('賣出日')).toBeInTheDocument())
    fireEvent.click(screen.getByRole('button', { name: '展開 2330 的訊號日明細' }))

    fireEvent.click(screen.getByLabelText('納入 2330 全部計算')) // fully checked -> unchecks all
    expect((screen.getByLabelText('納入 2330 2026-08-25 計算') as HTMLInputElement).checked).toBe(false)
    expect((screen.getByLabelText('納入 2330 2026-08-28 計算') as HTMLInputElement).checked).toBe(false)
    // 2317/2454 (unrelated single-signal-date rows) are untouched.
    expect((screen.getByLabelText('納入 2317 計算') as HTMLInputElement).checked).toBe(true)
    expect((screen.getByLabelText('納入 2454 計算') as HTMLInputElement).checked).toBe(true)

    fireEvent.click(screen.getByLabelText('納入 2330 2026-08-28 計算'))
    expect((screen.getByLabelText('納入 2330 2026-08-28 計算') as HTMLInputElement).checked).toBe(true)
    expect((screen.getByLabelText('納入 2330 2026-08-25 計算') as HTMLInputElement).checked).toBe(false) // sibling unaffected
  })

  it('never shows indeterminate on a single-signal-date row\'s checkbox', async () => {
    scanResponder = () => boxScanResponse()
    backtestResponder = () => ({ status: 200, body: singleBacktestResponse() })
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    selectStrategy('箱型突破')
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() => expect(screen.getByText('賣出日')).toBeInTheDocument())

    const checkbox = screen.getByLabelText('納入 2330 計算') as HTMLInputElement
    expect(checkbox.indeterminate).toBe(false)
    fireEvent.click(checkbox)
    expect(checkbox.indeterminate).toBe(false)
  })

  it('keeps the two header totals identical whether a multi-signal-date row is expanded or collapsed', async () => {
    scanResponder = () => unionScanResponse()
    backtestResponder = () => ({ status: 200, body: unionBacktestResponse() })
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    fireEvent.click(within(screen.getByText('箱型突破').closest('.st-strategy-card')!).getByRole('checkbox'))
    fireEvent.click(within(screen.getByText('底底高').closest('.st-strategy-card')!).getByRole('checkbox'))
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() => expect(screen.getByText('命中彙總 — 共 3 檔')).toBeInTheDocument())
    await waitFor(() => expect(screen.getByText('總報酬率')).toBeInTheDocument())

    const collapsedTotals = Array.from(document.querySelectorAll('.st-total-value')).map((el) => el.textContent)
    fireEvent.click(screen.getByRole('button', { name: '展開 2330 的訊號日明細' }))
    const expandedTotals = Array.from(document.querySelectorAll('.st-total-value')).map((el) => el.textContent)
    expect(expandedTotals).toEqual(collapsedTotals)
  })

  // ---------------- 「取消全選」勾選框 ----------------

  it('places 「取消全選」immediately left of 總報酬率 (order: 取消全選, 總報酬率, 總收益), existing only after a successful 回測', async () => {
    scanResponder = () => boxScanResponse()
    backtestResponder = () => ({ status: 200, body: singleBacktestResponse() })
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    selectStrategy('箱型突破')

    // Not present before scanning.
    expect(screen.queryByLabelText('取消全選')).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    // Not present after a successful scan but before 回測.
    expect(screen.queryByLabelText('取消全選')).not.toBeInTheDocument()

    // Not present while 回測 is running.
    expect(screen.queryByLabelText('取消全選')).not.toBeInTheDocument()

    await waitFor(() => expect(screen.getByText('賣出日')).toBeInTheDocument())
    const container = screen.getByText('總報酬率').closest('.st-backtest-totals') as HTMLElement
    const labels = within(container).getAllByText(/^取消全選$|^總報酬率$|^總收益（每筆 1 張）$/)
    expect(labels.map((el) => el.textContent)).toEqual(['取消全選', '總報酬率', '總收益（每筆 1 張）'])
    // 回測剛完成（全部預設勾選）時「取消全選」呈未勾選.
    expect((screen.getByLabelText('取消全選') as HTMLInputElement).checked).toBe(false)
  })

  it('does not exist when 回測 fails, and is removed together with the checkbox column on 重新掃描', async () => {
    scanResponder = () => boxScanResponse()
    backtestResponder = () => ({ status: 500, body: {} })
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    selectStrategy('箱型突破')
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))

    await waitFor(() => expect(screen.getByText('回測失敗，請稍後再試')).toBeInTheDocument())
    expect(screen.queryByLabelText('取消全選')).not.toBeInTheDocument()

    backtestResponder = () => ({ status: 200, body: singleBacktestResponse() })
    fireEvent.click(screen.getByRole('button', { name: '重試回測' }))
    await waitFor(() => expect(screen.getByLabelText('取消全選')).toBeInTheDocument())

    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    expect(screen.queryByLabelText('取消全選')).not.toBeInTheDocument()
    expect(screen.queryByText('賣出日')).not.toBeInTheDocument()
  })

  it('checking 「取消全選」unchecks every position (single row, both children of a multi-signal-date stock, and an unbacktestable row), and shows 未勾選任何標的', async () => {
    scanResponder = () => unionScanResponse()
    backtestResponder = () => ({ status: 200, body: unionBacktestResponse() })
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    fireEvent.click(within(screen.getByText('箱型突破').closest('.st-strategy-card')!).getByRole('checkbox'))
    fireEvent.click(within(screen.getByText('底底高').closest('.st-strategy-card')!).getByRole('checkbox'))
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() => expect(screen.getByText('命中彙總 — 共 3 檔')).toBeInTheDocument())
    await waitFor(() => expect(screen.getByText('-0.82%')).toBeInTheDocument())
    fireEvent.click(screen.getByRole('button', { name: '展開 2330 的訊號日明細' }))

    const cancelAll = screen.getByLabelText('取消全選') as HTMLInputElement
    fireEvent.click(cancelAll)

    expect((screen.getByLabelText('納入 2317 計算') as HTMLInputElement).checked).toBe(false)
    expect((screen.getByLabelText('納入 2454 計算') as HTMLInputElement).checked).toBe(false)
    expect((screen.getByLabelText('納入 2330 2026-08-25 計算') as HTMLInputElement).checked).toBe(false)
    expect((screen.getByLabelText('納入 2330 2026-08-28 計算') as HTMLInputElement).checked).toBe(false)
    const parentCheckbox = screen.getByLabelText('納入 2330 全部計算') as HTMLInputElement
    expect(parentCheckbox.checked).toBe(false)
    expect(parentCheckbox.indeterminate).toBe(false)

    await waitFor(() => expect(screen.getByText('未勾選任何標的')).toBeInTheDocument())
    expect(Array.from(document.querySelectorAll('.st-total-value')).map((el) => el.textContent)).toEqual(['—', '—'])
  })

  it('unchecking 「取消全選」checks every position back, restoring the totals to the response\'s own totalReturnPercent/totalProfit', async () => {
    scanResponder = () => unionScanResponse()
    backtestResponder = () => ({ status: 200, body: unionBacktestResponse() })
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    fireEvent.click(within(screen.getByText('箱型突破').closest('.st-strategy-card')!).getByRole('checkbox'))
    fireEvent.click(within(screen.getByText('底底高').closest('.st-strategy-card')!).getByRole('checkbox'))
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() => expect(screen.getByText('命中彙總 — 共 3 檔')).toBeInTheDocument())
    await waitFor(() => expect(screen.getByText('-0.82%')).toBeInTheDocument())

    const cancelAll = screen.getByLabelText('取消全選') as HTMLInputElement
    fireEvent.click(cancelAll) // all off
    await waitFor(() => expect(screen.getByText('未勾選任何標的')).toBeInTheDocument())
    fireEvent.click(cancelAll) // all back on
    expect(cancelAll.checked).toBe(false)
    await waitFor(() =>
      expect(Array.from(document.querySelectorAll('.st-total-value')).map((el) => el.textContent)).toEqual([
        '-0.82%',
        '-40,000',
      ]),
    )
  })

  it('shows 「取消全選」unchecked (never indeterminate) while only some positions are checked, and clicking it in that state still unchecks everything', async () => {
    scanResponder = () => unionScanResponse()
    backtestResponder = () => ({ status: 200, body: unionBacktestResponse() })
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    fireEvent.click(within(screen.getByText('箱型突破').closest('.st-strategy-card')!).getByRole('checkbox'))
    fireEvent.click(within(screen.getByText('底底高').closest('.st-strategy-card')!).getByRole('checkbox'))
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() => expect(screen.getByText('命中彙總 — 共 3 檔')).toBeInTheDocument())
    await waitFor(() => expect(screen.getByText('-0.82%')).toBeInTheDocument())

    fireEvent.click(screen.getByLabelText('納入 2317 計算'))
    const cancelAll = screen.getByLabelText('取消全選') as HTMLInputElement
    expect(cancelAll.checked).toBe(false)
    expect(cancelAll.indeterminate).toBe(false)

    fireEvent.click(cancelAll)
    expect((screen.getByLabelText('納入 2317 計算') as HTMLInputElement).checked).toBe(false)
    expect((screen.getByLabelText('納入 2454 計算') as HTMLInputElement).checked).toBe(false)
    expect((screen.getByLabelText('納入 2330 全部計算') as HTMLInputElement).checked).toBe(false)
  })

  it('自動 derives to checked once every row is manually unchecked, and back to unchecked the moment any one row is checked again', async () => {
    scanResponder = () => unionScanResponse()
    backtestResponder = () => ({ status: 200, body: unionBacktestResponse() })
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    fireEvent.click(within(screen.getByText('箱型突破').closest('.st-strategy-card')!).getByRole('checkbox'))
    fireEvent.click(within(screen.getByText('底底高').closest('.st-strategy-card')!).getByRole('checkbox'))
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() => expect(screen.getByText('命中彙總 — 共 3 檔')).toBeInTheDocument())
    await waitFor(() => expect(screen.getByText('-0.82%')).toBeInTheDocument())

    const cancelAll = screen.getByLabelText('取消全選') as HTMLInputElement
    expect(cancelAll.checked).toBe(false)

    fireEvent.click(screen.getByLabelText('納入 2317 計算'))
    fireEvent.click(screen.getByLabelText('納入 2454 計算'))
    fireEvent.click(screen.getByLabelText('納入 2330 全部計算')) // fully-checked parent -> unchecks both children
    expect(cancelAll.checked).toBe(true)

    fireEvent.click(screen.getByLabelText('納入 2317 計算')) // check exactly one back
    expect(cancelAll.checked).toBe(false)
  })

  it('shows 沒有可回測的標的 (not 未勾選任何標的) when 「取消全選」is used on a scan where every hit is unbacktestable', async () => {
    scanResponder = () => boxScanResponse()
    backtestResponder = () => ({ status: 200, body: allUnbacktestableResponse() })
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    selectStrategy('箱型突破')
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() => expect(screen.getByText('沒有可回測的標的')).toBeInTheDocument())

    fireEvent.click(screen.getByLabelText('取消全選'))
    expect(screen.getByText('沒有可回測的標的')).toBeInTheDocument()
    expect(screen.queryByText('未勾選任何標的')).not.toBeInTheDocument()
  })

  it('toggling 「取消全選」issues zero network requests', async () => {
    scanResponder = () => unionScanResponse()
    backtestResponder = () => ({ status: 200, body: unionBacktestResponse() })
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    fireEvent.click(within(screen.getByText('箱型突破').closest('.st-strategy-card')!).getByRole('checkbox'))
    fireEvent.click(within(screen.getByText('底底高').closest('.st-strategy-card')!).getByRole('checkbox'))
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() => expect(screen.getByText('命中彙總 — 共 3 檔')).toBeInTheDocument())
    await waitFor(() => expect(screen.getByText('-0.82%')).toBeInTheDocument())

    const cancelAll = screen.getByLabelText('取消全選') as HTMLInputElement
    const callsBefore = fetchMock.mock.calls.length
    fireEvent.click(cancelAll)
    fireEvent.click(cancelAll)
    expect(fetchMock.mock.calls.length).toBe(callsBefore)
  })

  it('resets 「取消全選」and every row back to checked after a successful 重試回測 — same as the first auto-backtest success', async () => {
    scanResponder = () => boxScanResponse()
    backtestResponder = () => ({ status: 500, body: {} })
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    selectStrategy('箱型突破')
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() => expect(screen.getByRole('button', { name: '重試回測' })).toBeInTheDocument())
    // No checkbox column exists yet in the failed state — nothing to preserve across the
    // retry (there's nothing to uncheck before it succeeds).
    expect(screen.queryByLabelText('取消全選')).not.toBeInTheDocument()

    backtestResponder = () => ({ status: 200, body: singleBacktestResponse() })
    fireEvent.click(screen.getByRole('button', { name: '重試回測' }))
    await waitFor(() => expect(screen.getByText('賣出日')).toBeInTheDocument())

    expect((screen.getByLabelText('納入 2330 計算') as HTMLInputElement).checked).toBe(true)
    expect((screen.getByLabelText('取消全選') as HTMLInputElement).checked).toBe(false)
  })

  // ---------------- 重試回測與過期回應 ----------------

  it('重試回測 only exists after a failed auto-backtest, and re-sends only the backtest — not the scan', async () => {
    scanResponder = () => boxScanResponse()
    backtestResponder = () => ({ status: 500, body: {} })
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    selectStrategy('箱型突破')

    expect(screen.queryByRole('button', { name: '重試回測' })).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    expect(screen.queryByRole('button', { name: '重試回測' })).not.toBeInTheDocument()
    await waitFor(() => expect(screen.getByRole('button', { name: '重試回測' })).toBeInTheDocument())

    const scanCallsBefore = fetchMock.mock.calls.filter((c) => String(c[0]).startsWith('/api/strategies/scan')).length
    backtestResponder = () => ({ status: 200, body: singleBacktestResponse() })
    fireEvent.click(screen.getByRole('button', { name: '重試回測' }))
    await waitFor(() => expect(screen.getByText('賣出日')).toBeInTheDocument())

    const scanCallsAfter = fetchMock.mock.calls.filter((c) => String(c[0]).startsWith('/api/strategies/scan')).length
    expect(scanCallsAfter).toBe(scanCallsBefore)
    // The merged table's own hit list is unchanged — still the same one stock.
    expect(screen.getByText('2330 台積電')).toBeInTheDocument()
  })

  it('disables 重試回測 and shows 回測中… while retrying (keeping the error message on screen); success removes both and shows the checkbox column, six columns, two totals and 取消全選 together', async () => {
    scanResponder = () => boxScanResponse()
    backtestResponder = () => ({ status: 500, body: {} })
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    selectStrategy('箱型突破')
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() => expect(screen.getByRole('button', { name: '重試回測' })).toBeInTheDocument())

    let resolveRetry: (v: unknown) => void = () => {}
    fetchMock.mockImplementation((url: string) => {
      const u = String(url)
      if (u.startsWith('/api/strategies/backtest')) {
        return new Promise((resolve) => {
          resolveRetry = () => resolve(jsonResponse(200, singleBacktestResponse()))
        })
      }
      if (u.startsWith('/api/strategies/scan')) return Promise.resolve(jsonResponse(200, scanResponder()))
      if (u.startsWith('/api/strategies')) return Promise.resolve(jsonResponse(200, CATALOG))
      if (u.startsWith('/api/stocks/sync/progress')) return Promise.resolve(jsonResponse(200, progressResponse()))
      return Promise.resolve(jsonResponse(200, {}))
    })

    fireEvent.click(screen.getByRole('button', { name: '重試回測' }))
    expect(screen.getByRole('button', { name: '回測中…' })).toBeDisabled()
    // 錯誤訊息保留至重試有結果為止
    expect(screen.getByText('回測失敗，請稍後再試')).toBeInTheDocument()

    resolveRetry(undefined)
    await waitFor(() => expect(screen.queryByText('回測失敗，請稍後再試')).not.toBeInTheDocument())
    expect(screen.queryByRole('button', { name: '重試回測' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '回測中…' })).not.toBeInTheDocument()
    expect(screen.getByText('賣出日')).toBeInTheDocument()
    expect(screen.getByText('總報酬率')).toBeInTheDocument()
    expect(screen.getByLabelText('取消全選')).toBeInTheDocument()
    expect(screen.getByLabelText('納入 2330 計算')).toBeInTheDocument()
  })

  it('keeps the error message and 重試回測 displayed, and re-enables the button, when a retry fails again', async () => {
    scanResponder = () => boxScanResponse()
    backtestResponder = () => ({ status: 500, body: {} })
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    selectStrategy('箱型突破')
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() => expect(screen.getByRole('button', { name: '重試回測' })).toBeInTheDocument())

    fireEvent.click(screen.getByRole('button', { name: '重試回測' }))
    await waitFor(() => expect(screen.getByRole('button', { name: '重試回測' })).not.toBeDisabled())
    expect(screen.getByText('回測失敗，請稍後再試')).toBeInTheDocument()
  })

  it('discards a stale backtest response that resolves after a newer scan has already replaced the hit list', async () => {
    let backtestCallCount = 0
    const backtestResolvers: Array<() => void> = []
    scanResponder = () => boxScanResponse()
    fetchMock.mockImplementation((url: string) => {
      const u = String(url)
      if (u.startsWith('/api/strategies/backtest')) {
        backtestCallCount += 1
        const isFirstCall = backtestCallCount === 1
        return new Promise((resolve) => {
          backtestResolvers.push(() =>
            resolve(
              jsonResponse(
                200,
                isFirstCall
                  ? {
                      asOfDate: '2026-09-11',
                      lotSize: 1000,
                      totalCost: 2420000,
                      totalProfit: -999000,
                      totalReturnPercent: -41.28,
                      backtestedCount: 1,
                      items: [
                        {
                          stockId: '2330',
                          buyDate: '2026-08-27',
                          buyPrice: 2420,
                          sellDate: '2026-09-01',
                          sellPrice: 1421,
                          returnPercent: -41.28,
                          profit: -999000,
                        },
                      ],
                    }
                  : unionBacktestResponse(),
              ),
            ),
          )
        })
      }
      if (u.startsWith('/api/strategies/scan')) return Promise.resolve(jsonResponse(200, scanResponder()))
      if (u.startsWith('/api/strategies')) return Promise.resolve(jsonResponse(200, CATALOG))
      if (u.startsWith('/api/stocks/sync/progress')) return Promise.resolve(jsonResponse(200, progressResponse()))
      return Promise.resolve(jsonResponse(200, {}))
    })
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    selectStrategy('箱型突破')
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() => expect(screen.getByText('2330 台積電')).toBeInTheDocument())
    await waitFor(() => expect(backtestResolvers).toHaveLength(1))

    // A second, different scan starts before the first backtest has resolved.
    scanResponder = () => unionScanResponse()
    fireEvent.click(within(screen.getByText('底底高').closest('.st-strategy-card')!).getByRole('checkbox'))
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() => expect(screen.getByText('命中彙總 — 共 3 檔')).toBeInTheDocument())
    await waitFor(() => expect(backtestResolvers).toHaveLength(2))

    // Resolve the SECOND (newer) backtest first, then the stale first one.
    backtestResolvers[1]()
    await waitFor(() => expect(screen.getByText('賣出日')).toBeInTheDocument())
    backtestResolvers[0]()
    await new Promise((resolve) => setTimeout(resolve, 0))

    // The stale response's numbers must never appear, and the newer hit list stays intact.
    expect(screen.queryByText('-41.28%')).not.toBeInTheDocument()
    expect(screen.queryByText('-999,000')).not.toBeInTheDocument()
    expect(screen.getByText('2317 鴻海')).toBeInTheDocument()
  })

  it('only needs a single click of 開始掃描 for the backtest result to appear, with no further user action', async () => {
    scanResponder = () => boxScanResponse()
    backtestResponder = () => ({ status: 200, body: singleBacktestResponse() })
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    selectStrategy('箱型突破')

    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() => expect(screen.getByText('賣出日')).toBeInTheDocument())
    expect(screen.getByText('總報酬率')).toBeInTheDocument()
  })

  // ---------------- 父列逐筆日期 ----------------

  it("lists every position's date on its own line in the parent row, newest first, and reflects each one's checked state", async () => {
    scanResponder = () => twoDateSingleStockScanResponse()
    backtestResponder = () => ({ status: 200, body: twoDateSingleStockBacktestResponse() })
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    fireEvent.click(within(screen.getByText('箱型突破').closest('.st-strategy-card')!).getByRole('checkbox'))
    fireEvent.click(within(screen.getByText('底底高').closest('.st-strategy-card')!).getByRole('checkbox'))
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() => expect(screen.getByText('賣出日')).toBeInTheDocument())

    // Cell order: 展開, 勾選框, 代號/名稱, 命中策略, 買進日, 買進價, 賣出日, 賣出價, 報酬率, 收益.
    // Scoped to the parent row by its own (multi-child) checkbox label — a bare
    // `getByText('2330 台積電').closest('tr')` becomes ambiguous once expanded, since each
    // child row repeats the same stock name in its own 代號/名稱 cell.
    let cells = within(screen.getByLabelText('納入 2330 全部計算').closest('tr') as HTMLElement).getAllByRole('cell')
    expect(Array.from(cells[4].querySelectorAll('div')).map((d) => d.textContent)).toEqual(['2026-08-27', '2026-08-20'])
    expect(Array.from(cells[6].querySelectorAll('div')).map((d) => d.textContent)).toEqual(['2026-08-28', '2026-08-25'])
    // Both checked by default — neither line is muted. Parent no longer shows 「{N} 筆」.
    expect(cells[4].querySelectorAll('div')[0].className).not.toContain('sl-muted')
    expect(cells[6].querySelectorAll('div')[0].className).not.toContain('sl-muted')
    expect(screen.queryByText(/^\d+ 筆$/)).not.toBeInTheDocument()

    // Uncheck the older position (2026-08-20) — its own line in both date columns must
    // turn muted immediately, while the newer position's lines stay unaffected.
    fireEvent.click(screen.getByRole('button', { name: '展開 2330 的訊號日明細' }))
    fireEvent.click(screen.getByLabelText('納入 2330 2026-08-20 計算'))

    cells = within(screen.getByLabelText('納入 2330 全部計算').closest('tr') as HTMLElement).getAllByRole('cell')
    const buyLines = Array.from(cells[4].querySelectorAll('div'))
    const sellLines = Array.from(cells[6].querySelectorAll('div'))
    expect(buyLines[0].className).not.toContain('sl-muted')
    expect(buyLines[1].className).toContain('sl-muted')
    expect(sellLines[0].className).not.toContain('sl-muted')
    expect(sellLines[1].className).toContain('sl-muted')
  })

  it('shows a muted 「—」 for the parent row\'s unbacktestable position while keeping the same line count in both date columns', async () => {
    scanResponder = () => twoDateSingleStockScanResponse()
    backtestResponder = () => ({
      status: 200,
      body: {
        asOfDate: '2026-09-10',
        lotSize: 1000,
        totalCost: 100000,
        totalProfit: 10000,
        totalReturnPercent: 10,
        backtestedCount: 1,
        items: [
          { stockId: '2330', buyDate: '2026-08-27', buyPrice: 100, sellDate: '2026-08-28', sellPrice: 110, returnPercent: 10, profit: 10000 },
          { stockId: '2330', buyDate: '2026-08-20', buyPrice: 10000, sellDate: null, sellPrice: null, returnPercent: null, profit: null },
        ],
      },
    })
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    fireEvent.click(within(screen.getByText('箱型突破').closest('.st-strategy-card')!).getByRole('checkbox'))
    fireEvent.click(within(screen.getByText('底底高').closest('.st-strategy-card')!).getByRole('checkbox'))
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() => expect(screen.getByText('賣出日')).toBeInTheDocument())

    const cells = within(screen.getByText('2330 台積電').closest('tr') as HTMLElement).getAllByRole('cell')
    const buyLines = Array.from(cells[4].querySelectorAll('div'))
    const sellLines = Array.from(cells[6].querySelectorAll('div'))
    expect(buyLines.map((d) => d.textContent)).toEqual(['2026-08-27', '2026-08-20'])
    expect(sellLines.map((d) => d.textContent)).toEqual(['2026-08-28', '—'])
    expect(sellLines).toHaveLength(buyLines.length)
    expect(sellLines[1].className).toContain('sl-muted')
  })

  // ---------- 以進場日買進（上漲支撐 D+2） ----------

  // 上漲支撐 alone: signalDate D = 2026-08-26, buyDate D+2 = 2026-08-28 (matching the
  // spec's own 1296/1272/1248 confirmCloses example). Single position, single row.
  function risingSupportDPlus2ScanResponse() {
    return {
      startDate: '2026-06-01',
      endDate: '2026-08-30',
      scannedStocks: 1,
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
              buyDate: '2026-08-28',
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
          insufficientData: [],
          pendingConfirm: [],
        },
      ],
    }
  }

  // Backtest response keyed on the D+2 buyDate — deliberately carries no signalDate field
  // at all (matching the real contract: 回應不含 signalDate).
  function risingSupportDPlus2BacktestResponse() {
    return {
      asOfDate: '2026-09-10',
      lotSize: 1000,
      totalCost: 1248000,
      totalProfit: 12000,
      totalReturnPercent: 0.96,
      backtestedCount: 1,
      items: [
        { stockId: '2454', buyDate: '2026-08-28', buyPrice: 1248, sellDate: '2026-09-01', sellPrice: 1260, returnPercent: 0.96, profit: 12000 },
      ],
    }
  }

  it('shows the 上漲支撐 buyDate (D+2) under 買進日 — distinct from the D signalDate shown in 命中策略與訊號日 — and sends the scan\'s own buyDate verbatim on the backtest request', async () => {
    scanResponder = () => risingSupportDPlus2ScanResponse()
    backtestResponder = () => ({ status: 200, body: risingSupportDPlus2BacktestResponse() })
    renderTab()
    await waitFor(() => expect(screen.getByText('上漲支撐')).toBeInTheDocument())
    selectStrategy('上漲支撐')
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() => expect(screen.getByText('賣出日')).toBeInTheDocument())

    const row = screen.getByText('2454 聯發科').closest('tr') as HTMLElement
    const hitsCell = row.querySelector('.st-union-hits') as HTMLElement
    // 命中策略與訊號日 shows the rise day D — not the buyDate.
    expect(within(hitsCell).getByText('2026-08-26')).toBeInTheDocument()
    expect(within(hitsCell).queryByText('2026-08-28')).not.toBeInTheDocument()
    // 買進日 (the 5th cell: expand/checkbox/代號/命中策略/買進日) shows the D+2 buyDate.
    const cells = within(row).getAllByRole('cell')
    expect(cells[4].textContent).toBe('2026-08-28')
    // 買進價 comes from the buyDate's own buyPrice in the backtest response.
    expect(within(row).getByText('1248.00')).toBeInTheDocument()

    const backtestCall = fetchMock.mock.calls.find((c) => String(c[0]).startsWith('/api/strategies/backtest'))!
    const body = JSON.parse((backtestCall[1] as RequestInit).body as string)
    // 前端不自行推算進場日：送出的是掃描回應的 buyDate（D+2），不是 signalDate（D）。
    expect(body.items).toEqual([{ stockId: '2454', buyDate: '2026-08-28' }])
  })

  it('merges a RISING_SUPPORT hit (signal D, buy D+2) with another strategy signalling on D+2 into one position and one row', async () => {
    scanResponder = () => ({
      startDate: '2026-06-01',
      endDate: '2026-08-30',
      scannedStocks: 1,
      results: [
        { ...risingSupportDPlus2ScanResponse().results[0] },
        {
          strategy: 'HIGHER_LOWS',
          preset: 'STRICT',
          matchedCount: 1,
          items: [
            {
              stockId: '2454',
              stockName: '聯發科',
              signalDate: '2026-08-28',
              buyDate: '2026-08-28',
              detail: {
                lows: [
                  { tradeDate: '2026-07-01', ma5: 1000.0, low: 1000.0 },
                  { tradeDate: '2026-08-28', ma5: 1248.0, low: 1248.0 },
                ],
              },
            },
          ],
          insufficientData: [],
          pendingConfirm: [],
        },
      ],
    })
    backtestResponder = () => ({ status: 200, body: risingSupportDPlus2BacktestResponse() })
    renderTab()
    await waitFor(() => expect(screen.getByText('上漲支撐')).toBeInTheDocument())
    selectStrategy('上漲支撐')
    selectStrategy('底底高')
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() => expect(screen.getByText('命中彙總 — 共 1 檔')).toBeInTheDocument())
    await waitFor(() => expect(screen.getByText('賣出日')).toBeInTheDocument())

    // One buyDate -> one position -> no expand caret.
    expect(screen.queryByRole('button', { name: /展開 2454/ })).not.toBeInTheDocument()

    const row = screen.getByText('2454 聯發科').closest('tr') as HTMLElement
    const hitsCell = row.querySelector('.st-union-hits') as HTMLElement
    expect(within(hitsCell).getByText('上漲支撐')).toBeInTheDocument()
    expect(within(hitsCell).getByText('2026-08-26')).toBeInTheDocument()
    expect(within(hitsCell).getByText('底底高')).toBeInTheDocument()
    expect(within(hitsCell).getByText('2026-08-28')).toBeInTheDocument()

    const backtestCall = fetchMock.mock.calls.find((c) => String(c[0]).startsWith('/api/strategies/backtest'))!
    const body = JSON.parse((backtestCall[1] as RequestInit).body as string)
    expect(body.items).toEqual([{ stockId: '2454', buyDate: '2026-08-28' }])
  })

  it('produces two positions and an expandable row when RISING_SUPPORT and HIGHER_LOWS both signal on the same day D, since their buy dates (D+2 vs D) differ', async () => {
    scanResponder = () => ({
      startDate: '2026-06-01',
      endDate: '2026-08-30',
      scannedStocks: 1,
      results: [
        { ...risingSupportDPlus2ScanResponse().results[0] },
        {
          strategy: 'HIGHER_LOWS',
          preset: 'STRICT',
          matchedCount: 1,
          items: [
            {
              stockId: '2454',
              stockName: '聯發科',
              signalDate: '2026-08-26',
              buyDate: '2026-08-26',
              detail: {
                lows: [
                  { tradeDate: '2026-07-01', ma5: 1000.0, low: 1000.0 },
                  { tradeDate: '2026-08-26', ma5: 1200.0, low: 1200.0 },
                ],
              },
            },
          ],
          insufficientData: [],
          pendingConfirm: [],
        },
      ],
    })
    backtestResponder = () => ({
      status: 200,
      body: {
        asOfDate: '2026-09-10',
        lotSize: 1000,
        totalCost: 2448000,
        totalProfit: 22000,
        totalReturnPercent: 0.9,
        backtestedCount: 2,
        items: [
          { stockId: '2454', buyDate: '2026-08-28', buyPrice: 1248, sellDate: '2026-09-01', sellPrice: 1260, returnPercent: 0.96, profit: 12000 },
          { stockId: '2454', buyDate: '2026-08-26', buyPrice: 1200, sellDate: '2026-08-27', sellPrice: 1210, returnPercent: 0.83, profit: 10000 },
        ],
      },
    })
    renderTab()
    await waitFor(() => expect(screen.getByText('上漲支撐')).toBeInTheDocument())
    selectStrategy('上漲支撐')
    selectStrategy('底底高')
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() => expect(screen.getByText('命中彙總 — 共 1 檔')).toBeInTheDocument())
    await waitFor(() => expect(screen.getByText('賣出日')).toBeInTheDocument())

    const expandBtn = screen.getByRole('button', { name: '展開 2454 的訊號日明細' })
    fireEvent.click(expandBtn)

    const unionTable = screen.getByText('命中彙總 — 共 1 檔').closest('.st-union-block')!.querySelector('table')!
    const childRows = within(unionTable).getAllByRole('row').filter((r) => r.className.includes('st-child-row'))
    expect(childRows).toHaveLength(2)
    expect(childRows.some((r) => r.textContent?.includes('2026-08-28'))).toBe(true)
    expect(childRows.some((r) => r.textContent?.includes('2026-08-26'))).toBe(true)

    const backtestCall = fetchMock.mock.calls.find((c) => String(c[0]).startsWith('/api/strategies/backtest'))!
    const body = JSON.parse((backtestCall[1] as RequestInit).body as string)
    expect(body.items).toEqual([
      { stockId: '2454', buyDate: '2026-08-28' },
      { stockId: '2454', buyDate: '2026-08-26' },
    ])
  })

  it('matches the backtest response to rows by (stockId, buyDate) alone — rendering succeeds even though the response never carries signalDate', async () => {
    scanResponder = () => risingSupportDPlus2ScanResponse()
    const backtestBody = risingSupportDPlus2BacktestResponse()
    backtestResponder = () => ({ status: 200, body: backtestBody })
    renderTab()
    await waitFor(() => expect(screen.getByText('上漲支撐')).toBeInTheDocument())
    selectStrategy('上漲支撐')
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() => expect(screen.getByText('賣出日')).toBeInTheDocument())

    expect(backtestBody.items.every((item) => !('signalDate' in item))).toBe(true)
    const row = screen.getByText('2454 聯發科').closest('tr') as HTMLElement
    expect(within(row).getByText('1248.00')).toBeInTheDocument()
    expect(within(row).getByText('0.96%')).toBeInTheDocument()
  })
})
