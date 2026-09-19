import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import StrategyTab from '../pages/StrategyTab'

function revealIncompletePositions() {
  const checkbox = screen.queryByLabelText('隱藏資料不齊（無賣出日）') as HTMLInputElement | null
  if (checkbox?.checked) fireEvent.click(checkbox)
}

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
    {
      code: 'INSTITUTIONAL_NET_RATIO',
      name: '法人買賣超佔比',
      description: '外資（不含外資自營商）或投信近指定天數買賣超合計取絕對值 ÷ 成交股數合計，達門檻即命中，淨買超與淨賣超皆計',
      presets: [],
      params: [
        {
          code: 'investors',
          name: '法人',
          type: 'multiSelect',
          options: [
            { code: 'FOREIGN', name: '外資' },
            { code: 'TRUST', name: '投信' },
          ],
          default: ['FOREIGN', 'TRUST'],
          minSelected: 1,
        },
        { code: 'windowDays', name: '天數', unit: '日', default: 5, min: 1, max: 20, step: 1 },
        { code: 'ratioPercent', name: '佔比門檻', unit: '%', default: 10, min: 0, max: 100, step: 0.1 },
      ],
    },
    {
      code: 'INSTITUTIONAL_CONSECUTIVE_BUY',
      name: '法人連續買超',
      description: '外資（不含外資自營商）或投信連續指定天數每日買超',
      presets: [],
      params: [
        {
          code: 'investors',
          name: '法人',
          type: 'multiSelect',
          options: [
            { code: 'FOREIGN', name: '外資' },
            { code: 'TRUST', name: '投信' },
          ],
          default: ['FOREIGN', 'TRUST'],
          minSelected: 1,
        },
        { code: 'buyDays', name: '連續買超天數', unit: '日', default: 5, min: 1, max: 20, step: 1 },
      ],
    },
    {
      code: 'INSTITUTIONAL_STRENGTH_RANK',
      name: '法人買超強度排名',
      description: '外資（不含外資自營商）或投信近指定天數買超合計 ÷ 成交股數合計為強度，只有淨買超者參與，每個交易日外資與投信各取強度前幾名',
      presets: [],
      params: [
        {
          code: 'investors',
          name: '法人',
          type: 'multiSelect',
          options: [
            { code: 'FOREIGN', name: '外資' },
            { code: 'TRUST', name: '投信' },
          ],
          default: ['FOREIGN', 'TRUST'],
          minSelected: 1,
        },
        { code: 'windowDays', name: '天數', unit: '日', default: 5, min: 1, max: 20, step: 1 },
        { code: 'topN', name: '取前幾名', unit: '名', default: 10, min: 1, max: 50, step: 1 },
      ],
    },
    {
      code: 'MACD_GOLDEN_CROSS',
      name: 'MACD 黃金交叉',
      description: 'DIF（短期 EMA − 長期 EMA）由下往上穿越 DEA（DIF 的 9 日 EMA）當日為訊號日',
      presets: [],
      // Defaults 5／20 — matching the real backend catalogue (specs/backend/strategy-scan.md
      // 「MACD 預設天數改為 5／20」), NOT the system's own day-K MACD (12／26). Kept
      // deliberately different from `macdScanResponse()`'s own echoed 12／26 below, so a test
      // that scans without touching the inputs and asserts the request/params-line shows
      // 5／20 (catalogue default) is distinguishable from a test that asserts 本次採用參數
      // shows whatever the RESPONSE echoes (12／26), proving the latter is response-driven,
      // not catalogue-driven.
      params: [
        { code: 'fastPeriod', name: '短期 EMA', unit: '日', default: 5, min: 2, max: 50, step: 1, lessThan: 'slowPeriod' },
        { code: 'slowPeriod', name: '長期 EMA', unit: '日', default: 20, min: 3, max: 100, step: 1 },
      ],
    },
    {
      code: 'KDJ_GOLDEN_CROSS',
      name: 'KDJ 黃金交叉',
      description: 'KD(9,3,3) 的 J 由下往上同時穿越 K 與 D 當日為訊號日，且前一交易日 J 低於門檻',
      presets: [],
      params: [{ code: 'jThreshold', name: 'J 門檻', unit: '', default: 40, min: -100, max: 100, step: 0.1 }],
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

// 法人買賣超佔比 — one hit, 外資賣超 only (matchedInvestors: ['FOREIGN'], direction: SELL),
// dataThroughDate present. signalDate/buyDate deliberately different (訊號日的下一個交易日)
// so「命中策略與訊號日」／「買進日」欄可以各自驗證。
function netRatioScanResponse() {
  return {
    startDate: '2026-06-01',
    endDate: '2026-08-30',
    scannedStocks: 3,
    results: [
      {
        strategy: 'INSTITUTIONAL_NET_RATIO',
        investors: ['FOREIGN', 'TRUST'],
        windowDays: 5,
        ratioPercent: 10,
        dataThroughDate: '2026-08-28',
        matchedCount: 1,
        items: [
          {
            stockId: '2609',
            stockName: '陽明',
            signalDate: '2026-08-27',
            buyDate: '2026-08-28',
            detail: {
              windowStartDate: '2026-08-21',
              volumeShares: 67500000,
              matchedInvestors: ['FOREIGN'],
              foreign: { netShares: -12500000, ratioPercent: 18.52, direction: 'SELL' },
              trust: null,
            },
          },
        ],
        insufficientData: [],
        pendingConfirm: [],
      },
    ],
  }
}

// 法人連續買超 — one hit (投信 only), one pendingConfirm entry.
function consecutiveBuyScanResponse() {
  return {
    startDate: '2026-06-01',
    endDate: '2026-08-30',
    scannedStocks: 3,
    results: [
      {
        strategy: 'INSTITUTIONAL_CONSECUTIVE_BUY',
        investors: ['TRUST'],
        buyDays: 5,
        dataThroughDate: '2026-08-28',
        matchedCount: 1,
        items: [
          {
            stockId: '2317',
            stockName: '鴻海',
            signalDate: '2026-08-26',
            buyDate: '2026-08-27',
            detail: {
              windowStartDate: '2026-08-20',
              matchedInvestors: ['TRUST'],
              foreign: null,
              trust: { netBuyShares: 3150000 },
            },
          },
        ],
        insufficientData: [],
        pendingConfirm: ['2330'],
      },
    ],
  }
}

// 法人買超強度排名 — one hit, both sides matched. dataThroughDate null (尚無法人資料 case).
function strengthRankScanResponse() {
  return {
    startDate: '2026-06-01',
    endDate: '2026-08-30',
    scannedStocks: 3,
    results: [
      {
        strategy: 'INSTITUTIONAL_STRENGTH_RANK',
        investors: ['FOREIGN', 'TRUST'],
        windowDays: 5,
        topN: 10,
        dataThroughDate: null,
        matchedCount: 1,
        items: [
          {
            stockId: '3231',
            stockName: '緯創',
            signalDate: '2026-08-27',
            buyDate: '2026-08-28',
            detail: {
              windowStartDate: '2026-08-21',
              volumeShares: 67500000,
              matchedInvestors: ['FOREIGN', 'TRUST'],
              foreign: { rank: 2, strengthPercent: 18.52, netBuyShares: 12500000 },
              trust: { rank: 7, strengthPercent: 3.1, netBuyShares: 2092500 },
            },
          },
        ],
        insufficientData: ['1234'],
        pendingConfirm: [],
      },
    ],
  }
}

// MACD 黃金交叉 — one hit, one insufficientData; buyDate === signalDate (unlike the three
// institutional strategies). fastPeriod/slowPeriod/signalPeriod all echoed from the response.
function macdScanResponse() {
  return {
    startDate: '2026-06-01',
    endDate: '2026-08-30',
    scannedStocks: 3,
    results: [
      {
        strategy: 'MACD_GOLDEN_CROSS',
        fastPeriod: 12,
        slowPeriod: 26,
        signalPeriod: 9,
        matchedCount: 1,
        items: [
          {
            stockId: '2330',
            stockName: '台積電',
            signalDate: '2026-08-26',
            buyDate: '2026-08-26',
            detail: { dif: -1.2034, dea: -1.312, osc: 0.1086, prevOsc: -0.0452 },
          },
        ],
        insufficientData: ['6949'],
        pendingConfirm: [],
      },
    ],
  }
}

// Same shape as `macdScanResponse()` but echoes the REAL default (5／20) — used by the
// 「MACD 預設天數改為 5／20」tests that scan WITHOUT touching the inputs, so the request
// and the 本次採用參數 line both reflect the catalogue's own default, not a stale 12／26.
function macdDefaultScanResponse() {
  const response = macdScanResponse()
  response.results[0].fastPeriod = 5
  response.results[0].slowPeriod = 20
  return response
}

// KDJ 黃金交叉 — one hit, `jThreshold` negative (-10) to exercise「前一日 J < -10」formatting.
function kdjScanResponse() {
  return {
    startDate: '2026-06-01',
    endDate: '2026-08-30',
    scannedStocks: 3,
    results: [
      {
        strategy: 'KDJ_GOLDEN_CROSS',
        jThreshold: -10,
        matchedCount: 1,
        items: [
          {
            stockId: '2317',
            stockName: '鴻海',
            signalDate: '2026-08-27',
            buyDate: '2026-08-27',
            detail: { k: 28.441, d: 27.9025, j: 29.518, prevK: 24.1037, prevD: 26.6333, prevJ: -12.3 },
          },
        ],
        insufficientData: [],
        pendingConfirm: [],
      },
    ],
  }
}

function macdBacktestResponse() {
  return {
    asOfDate: '2026-09-10',
    lotSize: 1000,
    totalCost: 242000,
    totalProfit: 3000,
    totalReturnPercent: 1.24,
    backtestedCount: 1,
    feeRatePercent: 0.1425,
    taxRatePercent: 0.3,
    items: [
      {
        stockId: '2330',
        buyDate: '2026-08-26',
        buyPrice: 242,
        sellDate: '2026-09-01',
        sellPrice: 245,
        returnPercent: 1.24,
        profit: 3000,
        buyFee: 345,
        sellFee: 349,
        sellTax: 735,
        cost: 242000,
      },
    ],
  }
}

function netRatioBacktestResponse() {
  return {
    asOfDate: '2026-09-10',
    lotSize: 1000,
    totalCost: 20000,
    totalProfit: 500,
    totalReturnPercent: 2.5,
    backtestedCount: 1,
    feeRatePercent: 0.1425,
    taxRatePercent: 0.3,
    items: [
      {
        stockId: '2609',
        buyDate: '2026-08-28',
        buyPrice: 20,
        sellDate: '2026-09-01',
        sellPrice: 20.5,
        returnPercent: 2.5,
        profit: 500,
        buyFee: 3,
        sellFee: 3,
        sellTax: 6,
        cost: 20000,
      },
    ],
  }
}

// One stock (9999) hit by two institutional strategies on two DIFFERENT buyDates — exercises
// the expand/collapse path with institutional hit-tag formatting on both the collapsed row
// and each expanded child row.
function institutionalExpansionScanResponse() {
  return {
    startDate: '2026-06-01',
    endDate: '2026-08-30',
    scannedStocks: 3,
    results: [
      {
        strategy: 'INSTITUTIONAL_NET_RATIO',
        investors: ['FOREIGN', 'TRUST'],
        windowDays: 5,
        ratioPercent: 10,
        dataThroughDate: '2026-08-28',
        matchedCount: 1,
        items: [
          {
            stockId: '9999',
            stockName: '測試股',
            signalDate: '2026-08-27',
            buyDate: '2026-08-28',
            detail: {
              windowStartDate: '2026-08-21',
              volumeShares: 1000000,
              matchedInvestors: ['FOREIGN', 'TRUST'],
              foreign: { netShares: 100000, ratioPercent: 12, direction: 'BUY' },
              trust: { netShares: -50000, ratioPercent: 11, direction: 'SELL' },
            },
          },
        ],
        insufficientData: [],
        pendingConfirm: [],
      },
      {
        strategy: 'INSTITUTIONAL_CONSECUTIVE_BUY',
        investors: ['TRUST'],
        buyDays: 5,
        dataThroughDate: '2026-08-28',
        matchedCount: 1,
        items: [
          {
            stockId: '9999',
            stockName: '測試股',
            signalDate: '2026-08-19',
            buyDate: '2026-08-20',
            detail: {
              windowStartDate: '2026-08-13',
              matchedInvestors: ['TRUST'],
              foreign: null,
              trust: { netBuyShares: 500000 },
            },
          },
        ],
        insufficientData: [],
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
    feeRatePercent: 0.1425,
    taxRatePercent: 0.3,
    items: [
      {
        stockId: '2330',
        buyDate: '2026-08-27',
        buyPrice: 2420,
        sellDate: '2026-09-01',
        sellPrice: 2450,
        returnPercent: 1.24,
        profit: 30000,
        buyFee: 3448,
        sellFee: 3491,
        sellTax: 7350,
        cost: 2420000,
      },
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
    feeRatePercent: 0.1425,
    taxRatePercent: 0.3,
    items: [
      {
        stockId: '2330',
        buyDate: '2026-08-27',
        buyPrice: 2420,
        sellDate: null,
        sellPrice: null,
        returnPercent: null,
        profit: null,
        buyFee: 3448,
        sellFee: null,
        sellTax: null,
        cost: 2420000,
      },
    ],
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
    feeRatePercent: 0.1425,
    taxRatePercent: 0.3,
    items: [
      {
        stockId: '2317',
        buyDate: '2026-08-28',
        buyPrice: 100,
        sellDate: '2026-09-01',
        sellPrice: 110,
        returnPercent: 10,
        profit: 10000,
        buyFee: 142,
        sellFee: 156,
        sellTax: 330,
        cost: 100000,
      },
      {
        stockId: '2330',
        buyDate: '2026-08-28',
        buyPrice: 2400,
        sellDate: '2026-09-02',
        sellPrice: 2300,
        returnPercent: -4.17,
        profit: -100000,
        buyFee: 3420,
        sellFee: 3277,
        sellTax: 6900,
        cost: 2400000,
      },
      {
        stockId: '2330',
        buyDate: '2026-08-25',
        buyPrice: 2350,
        sellDate: '2026-08-29',
        sellPrice: 2400,
        returnPercent: 2.13,
        profit: 50000,
        buyFee: 3349,
        sellFee: 3420,
        sellTax: 7200,
        cost: 2350000,
      },
      {
        stockId: '2454',
        buyDate: '2026-08-20',
        buyPrice: 900,
        sellDate: null,
        sellPrice: null,
        returnPercent: null,
        profit: null,
        buyFee: 1282,
        sellFee: null,
        sellTax: null,
        cost: 900000,
      },
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
    feeRatePercent: 0.1425,
    taxRatePercent: 0.3,
    items: [
      {
        stockId: '2330',
        buyDate: '2026-08-27',
        buyPrice: 100,
        sellDate: '2026-08-28',
        sellPrice: 110,
        returnPercent: 10,
        profit: 10000,
        buyFee: 142,
        sellFee: 156,
        sellTax: 330,
        cost: 100000,
      },
      {
        stockId: '2330',
        buyDate: '2026-08-20',
        buyPrice: 10000,
        sellDate: '2026-08-25',
        sellPrice: 9000,
        returnPercent: -10,
        profit: -1000000,
        buyFee: 14250,
        sellFee: 12825,
        sellTax: 27000,
        cost: 10000000,
      },
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
    feeRatePercent: 0.1425,
    taxRatePercent: 0.3,
    items: [
      {
        stockId: '3231',
        buyDate: '2026-08-25',
        buyPrice: 101,
        sellDate: '2026-08-29',
        sellPrice: 104,
        returnPercent: 2.97,
        profit: 3000,
        buyFee: 144,
        sellFee: 148,
        sellTax: 312,
        cost: 101000,
      },
      {
        stockId: '3231',
        buyDate: '2026-08-20',
        buyPrice: 90,
        sellDate: '2026-08-26',
        sellPrice: 92,
        returnPercent: 2.22,
        profit: 2000,
        buyFee: 128,
        sellFee: 131,
        sellTax: 276,
        cost: 90000,
      },
    ],
  }
}

// ---------- 買進價／報酬率欄排序 fixtures ----------

// Three single-buy-date rows with deliberately distinct signalDates so the default order
// (latest signalDate desc, tie stockId asc) is unambiguous: BBBB (08-25), AAAA (08-20),
// CCCC (08-15). CCCC is fully unbacktestable — the 「—」-as-minimum case for both sortable
// columns at once (its buyPrice AND returnPercent are both null).
function sortableScanResponse() {
  return {
    startDate: '2026-06-01',
    endDate: '2026-08-30',
    scannedStocks: 3,
    results: [
      {
        strategy: 'BOX_BREAKOUT',
        preset: 'STANDARD',
        matchedCount: 3,
        items: [
          { stockId: 'AAAA', stockName: 'A股', signalDate: '2026-08-20', buyDate: '2026-08-20', detail: {} },
          { stockId: 'BBBB', stockName: 'B股', signalDate: '2026-08-25', buyDate: '2026-08-25', detail: {} },
          { stockId: 'CCCC', stockName: 'C股', signalDate: '2026-08-15', buyDate: '2026-08-15', detail: {} },
        ],
        insufficientData: [],
        pendingConfirm: [],
      },
    ],
  }
}

// AAAA: buyPrice 100, return +5%. BBBB: buyPrice 200, return -3%. CCCC: no sellable
// trading day at all (buyPrice/sellDate/returnPercent all null).
function sortableBacktestResponse() {
  return {
    asOfDate: '2026-09-10',
    lotSize: 1000,
    totalCost: 300000,
    totalProfit: -1000,
    totalReturnPercent: -0.33,
    backtestedCount: 2,
    feeRatePercent: 0.1425,
    taxRatePercent: 0.3,
    items: [
      {
        stockId: 'AAAA',
        buyDate: '2026-08-20',
        buyPrice: 100,
        sellDate: '2026-08-21',
        sellPrice: 105,
        returnPercent: 5,
        profit: 5000,
        buyFee: 142,
        sellFee: 149,
        sellTax: 315,
        cost: 100000,
      },
      {
        stockId: 'BBBB',
        buyDate: '2026-08-25',
        buyPrice: 200,
        sellDate: '2026-08-26',
        sellPrice: 194,
        returnPercent: -3,
        profit: -6000,
        buyFee: 285,
        sellFee: 276,
        sellTax: 582,
        cost: 200000,
      },
      {
        stockId: 'CCCC',
        buyDate: '2026-08-15',
        buyPrice: null,
        sellDate: null,
        sellPrice: null,
        returnPercent: null,
        profit: null,
        buyFee: null,
        sellFee: null,
        sellTax: null,
        cost: null,
      },
    ],
  }
}

// Two rows tied on 報酬率 (7%) but with different signalDates — the default-order tiebreak
// (latest signalDate first) must decide their relative order, both before AND after
// sorting lands them on an equal value (a stable sort over the already-default-ordered
// array preserves this without any extra tiebreak code).
function tieScanResponse() {
  return {
    startDate: '2026-06-01',
    endDate: '2026-08-30',
    scannedStocks: 2,
    results: [
      {
        strategy: 'BOX_BREAKOUT',
        preset: 'STANDARD',
        matchedCount: 2,
        items: [
          { stockId: 'EEEE', stockName: 'E股', signalDate: '2026-08-18', buyDate: '2026-08-18', detail: {} },
          { stockId: 'FFFF', stockName: 'F股', signalDate: '2026-08-19', buyDate: '2026-08-19', detail: {} },
        ],
        insufficientData: [],
        pendingConfirm: [],
      },
    ],
  }
}

function tieBacktestResponse() {
  return {
    asOfDate: '2026-09-10',
    lotSize: 1000,
    totalCost: 200000,
    totalProfit: 14000,
    totalReturnPercent: 7,
    backtestedCount: 2,
    feeRatePercent: 0.1425,
    taxRatePercent: 0.3,
    items: [
      {
        stockId: 'EEEE',
        buyDate: '2026-08-18',
        buyPrice: 100,
        sellDate: '2026-08-19',
        sellPrice: 107,
        returnPercent: 7,
        profit: 7000,
        buyFee: 142,
        sellFee: 152,
        sellTax: 321,
        cost: 100000,
      },
      {
        stockId: 'FFFF',
        buyDate: '2026-08-19',
        buyPrice: 100,
        sellDate: '2026-08-20',
        sellPrice: 107,
        returnPercent: 7,
        profit: 7000,
        buyFee: 142,
        sellFee: 152,
        sellTax: 321,
        cost: 100000,
      },
    ],
  }
}

// Parent-aggregate sort fixture: 2330 has two buy dates (children +10%/-10%, cost-weighted
// aggregate -9.80%, same math as `twoDateSingleStockBacktestResponse` above) plus two
// plain single-date stocks whose returns straddle the AGGREGATE but not either child's own
// return: 9999 at +20% (above everything) and 8888 at 0% (between the aggregate -9.80% and
// either child's own +10%/-10%). Sorting 2330 by, say, its best child's own +10% instead of
// its displayed -9.80% aggregate would put it ABOVE 8888 instead of below — an observable
// difference in row ORDER, not just a numeric mismatch.
function parentSortScanResponse() {
  return {
    startDate: '2026-06-01',
    endDate: '2026-08-30',
    scannedStocks: 3,
    results: [
      {
        strategy: 'BOX_BREAKOUT',
        preset: 'STANDARD',
        matchedCount: 3,
        items: [
          { stockId: '2330', stockName: '台積電', signalDate: '2026-08-27', buyDate: '2026-08-27', detail: {} },
          { stockId: '9999', stockName: '玖玖', signalDate: '2026-08-22', buyDate: '2026-08-22', detail: {} },
          { stockId: '8888', stockName: '捌捌', signalDate: '2026-08-21', buyDate: '2026-08-21', detail: {} },
        ],
        insufficientData: [],
        pendingConfirm: [],
      },
      {
        strategy: 'HIGHER_LOWS',
        preset: 'STRICT',
        matchedCount: 1,
        items: [{ stockId: '2330', stockName: '台積電', signalDate: '2026-08-20', buyDate: '2026-08-20', detail: { lows: [] } }],
        insufficientData: [],
        pendingConfirm: [],
      },
    ],
  }
}

function parentSortBacktestResponse() {
  return {
    asOfDate: '2026-09-10',
    lotSize: 1000,
    totalCost: 10300000,
    totalProfit: -970000,
    totalReturnPercent: -9.42,
    backtestedCount: 4,
    feeRatePercent: 0.1425,
    taxRatePercent: 0.3,
    items: [
      {
        stockId: '2330',
        buyDate: '2026-08-27',
        buyPrice: 100,
        sellDate: '2026-08-28',
        sellPrice: 110,
        returnPercent: 10,
        profit: 10000,
        buyFee: 142,
        sellFee: 156,
        sellTax: 330,
        cost: 100000,
      },
      {
        stockId: '2330',
        buyDate: '2026-08-20',
        buyPrice: 10000,
        sellDate: '2026-08-25',
        sellPrice: 9000,
        returnPercent: -10,
        profit: -1000000,
        buyFee: 14250,
        sellFee: 12825,
        sellTax: 27000,
        cost: 10000000,
      },
      {
        stockId: '9999',
        buyDate: '2026-08-22',
        buyPrice: 100,
        sellDate: '2026-08-23',
        sellPrice: 120,
        returnPercent: 20,
        profit: 20000,
        buyFee: 142,
        sellFee: 171,
        sellTax: 360,
        cost: 100000,
      },
      {
        stockId: '8888',
        buyDate: '2026-08-21',
        buyPrice: 100,
        sellDate: '2026-08-22',
        sellPrice: 100,
        returnPercent: 0,
        profit: 0,
        buyFee: 142,
        sellFee: 142,
        sellTax: 300,
        cost: 100000,
      },
    ],
  }
}

// 「取消買進價高於 N 元」fixtures — five stocks:
// - 2330: two buy dates, buyPrice 480 (2026-08-27) and 520 (2026-08-20) — straddles the
//   default 500 threshold so only ONE of its two children is ever in the 高價組 (the parent
//   must show 半選, never fully checked/unchecked, once that one child is toggled off).
// - 1101: buyPrice exactly 500.00 — the 不含等於 boundary case, never in the 高價組.
// - 3008: buyPrice 600, `sellDate: null` — 無法回測但有買進價的筆, still in the 高價組.
// - AAAA: buyPrice 100 — a plain low-price control row, never touched by any threshold at
//   or above 100.
// - CCCC: buyPrice null (and unbacktestable) — 「buyPrice 為 null 的筆不受此框影響」, never
//   in the 高價組 at any amount.
function priceThresholdScanResponse() {
  return {
    startDate: '2026-06-01',
    endDate: '2026-08-30',
    scannedStocks: 5,
    results: [
      {
        strategy: 'BOX_BREAKOUT',
        preset: 'STANDARD',
        matchedCount: 5,
        items: [
          { stockId: '2330', stockName: '台積電', signalDate: '2026-08-27', buyDate: '2026-08-27', detail: {} },
          { stockId: '1101', stockName: '台泥', signalDate: '2026-08-25', buyDate: '2026-08-25', detail: {} },
          { stockId: '3008', stockName: '大立光', signalDate: '2026-08-24', buyDate: '2026-08-24', detail: {} },
          { stockId: 'AAAA', stockName: 'A股', signalDate: '2026-08-23', buyDate: '2026-08-23', detail: {} },
          { stockId: 'CCCC', stockName: 'C股', signalDate: '2026-08-22', buyDate: '2026-08-22', detail: {} },
        ],
        insufficientData: [],
        pendingConfirm: [],
      },
      {
        strategy: 'HIGHER_LOWS',
        preset: 'STRICT',
        matchedCount: 1,
        items: [{ stockId: '2330', stockName: '台積電', signalDate: '2026-08-20', buyDate: '2026-08-20', detail: { lows: [] } }],
        insufficientData: [],
        pendingConfirm: [],
      },
    ],
  }
}

// All checked: cost = 480,000 + 520,000 + 500,000 + 100,000 = 1,600,000 (3008/CCCC excluded,
// no sellDate); profit = 20,000 + (-20,000) + 10,000 + 10,000 = 20,000; return = 1.25%.
function priceThresholdBacktestResponse() {
  return {
    asOfDate: '2026-09-10',
    lotSize: 1000,
    totalCost: 1600000,
    totalProfit: 20000,
    totalReturnPercent: 1.25,
    backtestedCount: 4,
    feeRatePercent: 0.1425,
    taxRatePercent: 0.3,
    items: [
      {
        stockId: '2330',
        buyDate: '2026-08-27',
        buyPrice: 480,
        sellDate: '2026-08-28',
        sellPrice: 500,
        returnPercent: 4.17,
        profit: 20000,
        buyFee: 684,
        sellFee: 713,
        sellTax: 1500,
        cost: 480000,
      },
      {
        stockId: '2330',
        buyDate: '2026-08-20',
        buyPrice: 520,
        sellDate: '2026-08-25',
        sellPrice: 500,
        returnPercent: -3.85,
        profit: -20000,
        buyFee: 741,
        sellFee: 713,
        sellTax: 1500,
        cost: 520000,
      },
      {
        stockId: '1101',
        buyDate: '2026-08-25',
        buyPrice: 500,
        sellDate: '2026-08-26',
        sellPrice: 510,
        returnPercent: 2,
        profit: 10000,
        buyFee: 712,
        sellFee: 726,
        sellTax: 1530,
        cost: 500000,
      },
      {
        stockId: '3008',
        buyDate: '2026-08-24',
        buyPrice: 600,
        sellDate: null,
        sellPrice: null,
        returnPercent: null,
        profit: null,
        buyFee: 855,
        sellFee: null,
        sellTax: null,
        cost: 600000,
      },
      {
        stockId: 'AAAA',
        buyDate: '2026-08-23',
        buyPrice: 100,
        sellDate: '2026-08-24',
        sellPrice: 110,
        returnPercent: 10,
        profit: 10000,
        buyFee: 142,
        sellFee: 156,
        sellTax: 330,
        cost: 100000,
      },
      {
        stockId: 'CCCC',
        buyDate: '2026-08-22',
        buyPrice: null,
        sellDate: null,
        sellPrice: null,
        returnPercent: null,
        profit: null,
        buyFee: null,
        sellFee: null,
        sellTax: null,
        cost: null,
      },
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

/** Top-level (parent, or single-buy-date) rows only — excludes `.st-child-row`s inside an
 * expanded parent — read via the 代號 / 名稱 cell (index 2, right after 展開鈕 + 勾選框). Used
 * throughout 「買進價與報酬率欄排序」 to assert row ORDER without hard-coding how many
 * columns exist. */
function topLevelRowStockIds(): string[] {
  const table = screen.getByRole('table')
  return within(table)
    .getAllByRole('row')
    .slice(1)
    .filter((r) => !r.className.includes('st-child-row'))
    .map((r) => within(r).getAllByRole('cell')[2].textContent ?? '')
}

type SortableHeaderLabel = '買進價' | '報酬率' | '收益（每筆 1 張）'

/** Clicks a sortable header by its exact label text (buyPrice → 買進價, returnPercent →
 * 報酬率, profit → 收益（每筆 1 張）) — the label lives in its own `<span
 * class="st-sort-label">`, separate from the icon, so `getByText` matches it uniquely and
 * the click still bubbles to the `<th>`'s own handler (no `stopPropagation` on the label
 * span). */
function clickSortHeader(label: SortableHeaderLabel): void {
  fireEvent.click(screen.getByText(label))
}

function sortHeaderIcon(label: SortableHeaderLabel): string | null {
  const th = screen.getByText(label).closest('th')!
  return th.querySelector('.st-sort-icon')?.textContent ?? null
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

  it('defaults the date range to 上週/本週 — not any of the three shortcut results, so none is highlighted', async () => {
    // Today 2026-09-14 (Monday) is the exact date the spec itself uses: this week is ISO
    // week 38 (09/14–09/20); last week is ISO week 37 (09/07–09/13).
    vi.setSystemTime(new Date('2026-09-14T00:00:00'))
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    expect(screen.getByText('2026 第 37 週（09/07–09/13）')).toBeInTheDocument()
    expect(screen.getByText('2026 第 38 週（09/14–09/20）')).toBeInTheDocument()
    expect(screen.getByText('實際區間 2026-09-07 ~ 2026-09-14')).toBeInTheDocument()
    // Deliberately not any shortcut's result (近一個月's start week is ~4 weeks back, never
    // exactly last week) — so none of the three buttons is highlighted on page load.
    expect(screen.getByRole('button', { name: '近一個月' }).className).not.toContain('st-shortcut-active')
    expect(screen.getByRole('button', { name: '近三個月' }).className).not.toContain('st-shortcut-active')
    expect(screen.getByRole('button', { name: '近半年' }).className).not.toContain('st-shortcut-active')

    selectStrategy('箱型突破')
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() => expect(screen.getByText('2330 台積電')).toBeInTheDocument())
    const scanCall = fetchMock.mock.calls.find((c) => String(c[0]).startsWith('/api/strategies/scan'))!
    const body = JSON.parse((scanCall[1] as RequestInit).body as string)
    expect(body.startDate).toBe('2026-09-07')
    expect(body.endDate).toBe('2026-09-14')
  })

  it('defaults the date range correctly across a year boundary: today 2027-01-05 → 起始週 2026 第 53 週 (12/28–01/03)', async () => {
    // 2027-01-05 is a Tuesday. This week's Monday is 2027-01-04, whose ISO week is 2027
    // week 1 (01/04–01/10). Last week's Monday is 2026-12-28, whose ISO week is the prior
    // year's 2026 week 53 (12/28–01/03) — the cross-year case the spec calls out by name.
    vi.setSystemTime(new Date('2027-01-05T00:00:00'))
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    expect(screen.getByText('2026 第 53 週（12/28–01/03）')).toBeInTheDocument()
    expect(screen.getByText('2027 第 1 週（01/04–01/10）')).toBeInTheDocument()
    expect(screen.getByText('實際區間 2026-12-28 ~ 2027-01-05')).toBeInTheDocument()

    selectStrategy('箱型突破')
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() => expect(screen.getByText('2330 台積電')).toBeInTheDocument())
    const scanCall = fetchMock.mock.calls.find((c) => String(c[0]).startsWith('/api/strategies/scan'))!
    const body = JSON.parse((scanCall[1] as RequestInit).body as string)
    expect(body.startDate).toBe('2026-12-28')
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
    // 起始週 defaults to 上週：今日（2026-09-02）所在的本週一 is 2026-08-31, so 上週一 is
    // 2026-08-24 — whose ISO week is 2026 week 35 (08/24–08/30).
    expect(screen.getByText('2026 第 35 週（08/24–08/30）')).toBeInTheDocument()
    expect(screen.getByText('實際區間 2026-08-24 ~ 2026-09-02')).toBeInTheDocument()

    fireEvent.click(within(screen.getByText('箱型突破').closest('.st-strategy-card')!).getByRole('checkbox'))
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() => expect(screen.getByText('2330 台積電')).toBeInTheDocument())
    const scanCall = fetchMock.mock.calls.find((c) => String(c[0]).startsWith('/api/strategies/scan'))!
    const body = JSON.parse((scanCall[1] as RequestInit).body as string)
    expect(body.startDate).toBe('2026-08-24')
    expect(body.endDate).toBe('2026-09-02')
  })

  it('sends the end week\'s own Sunday as endDate when 結束週 is a past week, not 本週', async () => {
    // Today is 2026-09-02 (ISO week 36, 08/31–09/06); the default 起始週 is 上週 (week 35,
    // 08/24–08/30). Stepping 結束週 back once lands it on week 35 too (equal to 起始週, still
    // a valid non-reversed range) — no longer 本週, so endDate must be that week's own
    // Sunday (2026-08-30), never today's date. (Stepping back a second week would push
    // 結束週 earlier than the default 起始週, which the front end blocks — see the
    // 起始週不可晚於結束週 guard — so this test only steps back the one week that stays valid.)
    vi.setSystemTime(new Date('2026-09-02T00:00:00'))
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    const prevEndWeekButton = screen.getByRole('button', { name: '結束週往前一週' })
    fireEvent.click(prevEndWeekButton)
    expect(screen.getByText('實際區間 2026-08-24 ~ 2026-08-30')).toBeInTheDocument()

    fireEvent.click(within(screen.getByText('箱型突破').closest('.st-strategy-card')!).getByRole('checkbox'))
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() => expect(screen.getByText('2330 台積電')).toBeInTheDocument())
    const scanCall = fetchMock.mock.calls.find((c) => String(c[0]).startsWith('/api/strategies/scan'))!
    const body = JSON.parse((scanCall[1] as RequestInit).body as string)
    expect(body.startDate).toBe('2026-08-24')
    expect(body.endDate).toBe('2026-08-30')
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
    revealIncompletePositions()
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
    revealIncompletePositions()

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
    // 買進價／報酬率／收益 are the three sortable headers — freshly post-backtest, before
    // any header click, all three show the idle 「↕」 indicator (specs/frontend/strategy.md
    // 「買進價、報酬率與收益欄排序」表頭呈現).
    expect(headers).toEqual([
      '',
      '',
      '代號 / 名稱',
      '命中策略與訊號日',
      '買進日',
      '買進價↕',
      '賣出日',
      '賣出價',
      '報酬率↕',
      '收益（每筆 1 張）↕',
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
    revealIncompletePositions()
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
    revealIncompletePositions()

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
    revealIncompletePositions()

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
    revealIncompletePositions()

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
    revealIncompletePositions()

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
    revealIncompletePositions()
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
    revealIncompletePositions()
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
    revealIncompletePositions()
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
    revealIncompletePositions()

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
    revealIncompletePositions()

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
    revealIncompletePositions()

    const callsBefore = fetchMock.mock.calls.length
    // 2317 alone: cost 100*1000=100,000, profit 10,000 -> excluding it from the combined
    // totals (totalCost 4,850,000 / totalProfit -40,000) leaves cost 4,750,000, profit
    // -50,000, return -50,000/4,750,000*100 ≈ -1.05%.
    const row2317 = (screen.getByLabelText('納入 2317 計算') as HTMLInputElement).closest('tr') as HTMLElement
    fireEvent.click(row2317)
    expect(fetchMock.mock.calls.length).toBe(callsBefore)

    const totalValues = Array.from(document.querySelectorAll('.st-total-value')).map((el) => el.textContent)
    expect(totalValues).toEqual(['4,750,000', '-1.05%', '-50,000'])
    expect((screen.getByLabelText('取消全選') as HTMLInputElement).checked).toBe(false)

    // Unchecking every remaining position (2330's two children, then 2454 — itself
    // unbacktestable but still a tracked/checked position) via row clicks drives 取消全選
    // to auto-check, matching the same derivation a checkbox click gets.
    fireEvent.click(screen.getByLabelText('納入 2330 全部計算').closest('tr') as HTMLElement)
    fireEvent.click(screen.getByLabelText('納入 2454 計算').closest('tr') as HTMLElement)
    const totalsAfterAllExcluded = Array.from(document.querySelectorAll('.st-total-value')).map((el) => el.textContent)
    expect(totalsAfterAllExcluded).toEqual(['—', '—', '—'])
    expect(screen.getByText('未勾選任何標的')).toBeInTheDocument()
    expect((screen.getByLabelText('取消全選') as HTMLInputElement).checked).toBe(true)
    expect(fetchMock.mock.calls.length).toBe(callsBefore)
  })

  it("with everything checked, the displayed 總成本／總報酬率／總收益 equal the response's totalCost/totalReturnPercent/totalProfit exactly", async () => {
    scanResponder = () => unionScanResponse()
    backtestResponder = () => ({ status: 200, body: unionBacktestResponse() })
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    fireEvent.click(within(screen.getByText('箱型突破').closest('.st-strategy-card')!).getByRole('checkbox'))
    fireEvent.click(within(screen.getByText('底底高').closest('.st-strategy-card')!).getByRole('checkbox'))
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() => expect(screen.getByText('命中彙總 — 共 3 檔')).toBeInTheDocument())

    await waitFor(() => expect(screen.getByText('總報酬率')).toBeInTheDocument())

    // unionBacktestResponse's own totalCost/totalProfit/totalReturnPercent are
    // 4,850,000 / -40,000 / -0.82 — the displayed values must match verbatim
    // (specs/frontend/strategy.md「全部勾選時…總成本、總報酬率、總收益必須分別等於回應
    // 的 totalCost、totalReturnPercent 與 totalProfit」).
    const totalValues = Array.from(document.querySelectorAll('.st-total-value')).map((el) => el.textContent)
    expect(totalValues).toEqual(['4,850,000', '-0.82%', '-40,000'])
    // 2454 has no sellable day — uncounted for that reason, never "未勾選". Counts 筆
    // (positions), not 檔 — only 1 position (2454's) lacks a sellable day.
    expect(screen.getByText('另 1 筆尚無可賣出交易日，未計入')).toBeInTheDocument()
    expect(screen.queryByText(/未勾選，未計入/)).not.toBeInTheDocument()
  })

  it('computes 總成本 from the response items[].cost (fee-inclusive), never a self-computed buyPrice × lotSize', async () => {
    scanResponder = () => boxScanResponse()
    backtestResponder = () => ({
      status: 200,
      body: {
        asOfDate: '2026-09-10',
        lotSize: 1000,
        totalCost: 2423448,
        totalProfit: 15000,
        totalReturnPercent: 0.62,
        backtestedCount: 1,
        feeRatePercent: 0.1425,
        taxRatePercent: 0.3,
        items: [
          {
            stockId: '2330',
            buyDate: '2026-08-27',
            buyPrice: 2420,
            sellDate: '2026-09-01',
            sellPrice: 2450,
            returnPercent: 0.62,
            profit: 15000,
            buyFee: 3448,
            sellFee: 3491,
            sellTax: 7350,
            // Deliberately NOT equal to buyPrice × lotSize (2,420,000) — it includes buyFee
            // (2,420,000 + 3,448), so a frontend that self-computed `buyPrice × lotSize`
            // instead of summing this field would show a different (wrong) total.
            cost: 2423448,
          },
        ],
      },
    })
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    selectStrategy('箱型突破')
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))

    // (Both the row's own cell and the total label read 0.62% here, hence getAllByText.)
    await waitFor(() => expect(screen.getAllByText('0.62%').length).toBeGreaterThan(0))
    const totalValues = Array.from(document.querySelectorAll('.st-total-value')).map((el) => el.textContent)
    // 總成本 must equal the response's own fee-inclusive `cost` (2,423,448), not
    // buyPrice × lotSize (which would read 2,420,000 instead).
    expect(totalValues).toEqual(['2,423,448', '0.62%', '15,000'])
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
      '4,850,000',
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
      // Unchecking 2317 (cost 100,000, backtestable) drops total cost to 4,750,000 too —
      // 總成本 tracks the exact same 已勾選且可回測 coverage as 總報酬率／總收益.
      expect(Array.from(document.querySelectorAll('.st-total-value')).map((el) => el.textContent)).toEqual([
        '4,750,000',
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
        '4,850,000',
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
    expect(totalValues).toEqual(['—', '—', '—'])
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
    revealIncompletePositions()

    fireEvent.click(screen.getByLabelText('納入 2330 計算'))
    await waitFor(() => expect(screen.getByText('未勾選任何標的')).toBeInTheDocument())
    const totalValues = Array.from(document.querySelectorAll('.st-total-value')).map((el) => el.textContent)
    expect(totalValues).toEqual(['—', '—', '—'])
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

    revealIncompletePositions()
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
        feeRatePercent: 0.1425,
        taxRatePercent: 0.3,
        items: [
          {
            stockId: '2330',
            buyDate: '2026-08-27',
            buyPrice: 2420,
            sellDate: '2026-09-01',
            sellPrice: 2420,
            returnPercent: 0,
            profit: 0,
            buyFee: 3448,
            sellFee: 3448,
            sellTax: 7260,
            cost: 2420000,
          },
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
    expect(totalValues.map((el) => el.textContent)).toEqual(['2,420,000', '0.00%', '0'])
    // 總成本（第一個）恆為主要文字色，不套用漲跌色，即使其餘兩個標籤在報酬率／收益為 0
    // 時套用 sl-neutral — 「總成本不是漲跌值，不套漲跌色」。
    expect(totalValues[0].className).not.toContain('sl-neutral')
    expect(totalValues[0].className).not.toContain('sl-up')
    expect(totalValues[0].className).not.toContain('sl-down')
    totalValues.slice(1).forEach((el) => expect(el.className).toContain('sl-neutral'))
  })

  it('clears any backtest result and returns the table to its un-backtested state the moment 開始掃描 is pressed again', async () => {
    scanResponder = () => boxScanResponse()
    backtestResponder = () => ({ status: 200, body: singleBacktestResponse() })
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    selectStrategy('箱型突破')
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() => expect(screen.getByText('賣出日')).toBeInTheDocument())
    revealIncompletePositions()

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
    revealIncompletePositions()

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
    revealIncompletePositions()

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
    revealIncompletePositions()

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
    revealIncompletePositions()

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
    revealIncompletePositions()

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
    revealIncompletePositions()

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
    revealIncompletePositions()

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
    revealIncompletePositions()

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
    revealIncompletePositions()

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
    revealIncompletePositions()
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
    revealIncompletePositions()
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
    revealIncompletePositions()

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

  it('keeps only three totals in the header and places cancel-all in the batch row after a successful 回測', async () => {
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
    revealIncompletePositions()
    const container = screen.getByText('總報酬率').closest('.st-backtest-totals') as HTMLElement
    const labels = within(container).getAllByText(/^取消全選$|^總成本（每筆 1 張）$|^總報酬率$|^總收益（每筆 1 張）$/)
    expect(labels.map((el) => el.textContent)).toEqual(['總成本（每筆 1 張）', '總報酬率', '總收益（每筆 1 張）'])
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
    expect(Array.from(document.querySelectorAll('.st-total-value')).map((el) => el.textContent)).toEqual(['—', '—', '—'])
  })

  it('unchecking 「取消全選」checks every position back, restoring the totals to the response\'s own totalCost/totalReturnPercent/totalProfit', async () => {
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
        '4,850,000',
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
    revealIncompletePositions()

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
    revealIncompletePositions()

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
    revealIncompletePositions()

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
    revealIncompletePositions()

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
    revealIncompletePositions()
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
    revealIncompletePositions()
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
    revealIncompletePositions()

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
    revealIncompletePositions()

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
      feeRatePercent: 0.1425,
      taxRatePercent: 0.3,
      items: [
        {
          stockId: '2454',
          buyDate: '2026-08-28',
          buyPrice: 1248,
          sellDate: '2026-09-01',
          sellPrice: 1260,
          returnPercent: 0.96,
          profit: 12000,
          buyFee: 1778,
          sellFee: 1795,
          sellTax: 3780,
          cost: 1248000,
        },
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
    revealIncompletePositions()

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
    revealIncompletePositions()

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
        feeRatePercent: 0.1425,
        taxRatePercent: 0.3,
        items: [
          {
            stockId: '2454',
            buyDate: '2026-08-28',
            buyPrice: 1248,
            sellDate: '2026-09-01',
            sellPrice: 1260,
            returnPercent: 0.96,
            profit: 12000,
            buyFee: 1778,
            sellFee: 1795,
            sellTax: 3780,
            cost: 1248000,
          },
          {
            stockId: '2454',
            buyDate: '2026-08-26',
            buyPrice: 1200,
            sellDate: '2026-08-27',
            sellPrice: 1210,
            returnPercent: 0.83,
            profit: 10000,
            buyFee: 1710,
            sellFee: 1724,
            sellTax: 3630,
            cost: 1200000,
          },
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
    revealIncompletePositions()

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
    revealIncompletePositions()

    expect(backtestBody.items.every((item) => !('signalDate' in item))).toBe(true)
    const row = screen.getByText('2454 聯發科').closest('tr') as HTMLElement
    expect(within(row).getByText('1248.00')).toBeInTheDocument()
    expect(within(row).getByText('0.96%')).toBeInTheDocument()
  })

  // ---------------- 總成本 ----------------

  it('keeps 「總收益 ÷ 總成本 × 100」(rounded to two decimals) equal to the displayed 總報酬率 at any checked state', async () => {
    scanResponder = () => unionScanResponse()
    backtestResponder = () => ({ status: 200, body: unionBacktestResponse() })
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    fireEvent.click(within(screen.getByText('箱型突破').closest('.st-strategy-card')!).getByRole('checkbox'))
    fireEvent.click(within(screen.getByText('底底高').closest('.st-strategy-card')!).getByRole('checkbox'))
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() => expect(screen.getByText('命中彙總 — 共 3 檔')).toBeInTheDocument())
    await waitFor(() => expect(screen.getByText('總報酬率')).toBeInTheDocument())

    const readTotals = () => {
      const [cost, returnPct] = Array.from(document.querySelectorAll('.st-total-value')).map((el) => el.textContent)
      return { cost: Number(cost!.replace(/,/g, '')), returnPct: Number(returnPct!.replace('%', '')) }
    }
    // All checked: cost 4,850,000, profit -40,000 -> -0.82%.
    const before = readTotals()
    expect(before.cost).toBe(4850000)
    expect(Math.round((-40000 / before.cost) * 100 * 100) / 100).toBe(before.returnPct)

    fireEvent.click(screen.getByLabelText('納入 2317 計算'))
    // 2317 excluded: cost 4,750,000, profit -50,000 -> -1.05%.
    await waitFor(() => expect(readTotals().cost).toBe(4750000))
    const after = readTotals()
    expect(Math.round((-50000 / after.cost) * 100 * 100) / 100).toBe(after.returnPct)
  })

  it('exists only after a successful 回測, same lifecycle as 取消全選／總報酬率／總收益: absent before/during/on failure, present after a successful retry', async () => {
    scanResponder = () => boxScanResponse()
    backtestResponder = () => ({ status: 500, body: {} })
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    selectStrategy('箱型突破')
    expect(screen.queryByText('總成本（每筆 1 張）')).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    expect(screen.queryByText('總成本（每筆 1 張）')).not.toBeInTheDocument()

    await waitFor(() => expect(screen.getByText('回測失敗，請稍後再試')).toBeInTheDocument())
    expect(screen.queryByText('總成本（每筆 1 張）')).not.toBeInTheDocument()

    backtestResponder = () => ({ status: 200, body: singleBacktestResponse() })
    fireEvent.click(screen.getByRole('button', { name: '重試回測' }))
    await waitFor(() => expect(screen.getByText('總成本（每筆 1 張）')).toBeInTheDocument())

    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    expect(screen.queryByText('總成本（每筆 1 張）')).not.toBeInTheDocument()
  })

  // ---------------- 買進價／報酬率欄排序 ----------------

  it('has no sortable headers before 回測 completes, and exactly 買進價／報酬率／收益 sortable (with the idle 「↕」 icon) once it does', async () => {
    scanResponder = () => sortableScanResponse()
    // Deferred (never-yet-resolved) backtest response — same technique the pre-existing
    // 「shows the merged table with no checkbox column while the auto-triggered 回測 is in
    // flight」test uses — so the "mid-回測" checkpoint below is genuinely mid-flight rather
    // than incidentally timed against how fast mocked fetches happen to resolve.
    let resolveBacktest: (v: unknown) => void = () => {}
    fetchMock.mockImplementation((url: string) => {
      const u = String(url)
      if (u.startsWith('/api/strategies/backtest')) {
        return new Promise((resolve) => {
          resolveBacktest = () => resolve(jsonResponse(200, sortableBacktestResponse()))
        })
      }
      if (u.startsWith('/api/strategies/scan')) return Promise.resolve(jsonResponse(200, sortableScanResponse()))
      if (u.startsWith('/api/strategies')) return Promise.resolve(jsonResponse(200, CATALOG))
      if (u.startsWith('/api/stocks/sync/progress')) return Promise.resolve(jsonResponse(200, progressResponse()))
      return Promise.resolve(jsonResponse(200, {}))
    })
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    selectStrategy('箱型突破')
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() => expect(screen.getByText('AAAA A股')).toBeInTheDocument())
    // Mid-回測 (checkbox column and the six backtest columns don't exist yet either).
    expect(document.querySelectorAll('.st-sortable')).toHaveLength(0)

    resolveBacktest(undefined)
    await waitFor(() => expect(screen.getByText('賣出日')).toBeInTheDocument())
    revealIncompletePositions()
    const sortableHeaders = Array.from(document.querySelectorAll('.st-sortable')).map((el) => el.textContent)
    expect(sortableHeaders).toEqual(['買進價↕', '報酬率↕', '收益（每筆 1 張）↕'])
  })

  it('shows the default order (latest signalDate desc, tie stockId asc) immediately after 回測, before any header click', async () => {
    scanResponder = () => sortableScanResponse()
    backtestResponder = () => ({ status: 200, body: sortableBacktestResponse() })
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    selectStrategy('箱型突破')
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() => expect(screen.getByText('賣出日')).toBeInTheDocument())
    revealIncompletePositions()
    expect(topLevelRowStockIds()).toEqual(['BBBB B股', 'AAAA A股', 'CCCC C股'])
  })

  it('cycles 降冪 → 升冪 → 還原預設排序 → 降冪… on repeated clicks of the same header, reordering rows and updating the icon each time, treating 「—」 as the minimum', async () => {
    scanResponder = () => sortableScanResponse()
    backtestResponder = () => ({ status: 200, body: sortableBacktestResponse() })
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    selectStrategy('箱型突破')
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() => expect(screen.getByText('賣出日')).toBeInTheDocument())
    revealIncompletePositions()

    clickSortHeader('報酬率') // 1st: 降冪 — AAAA 5% > BBBB -3% > CCCC 「—」(min)
    expect(sortHeaderIcon('報酬率')).toBe('▼')
    expect(topLevelRowStockIds()).toEqual(['AAAA A股', 'BBBB B股', 'CCCC C股'])

    clickSortHeader('報酬率') // 2nd: 升冪 — CCCC 「—」(min) first
    expect(sortHeaderIcon('報酬率')).toBe('▲')
    expect(topLevelRowStockIds()).toEqual(['CCCC C股', 'BBBB B股', 'AAAA A股'])

    clickSortHeader('報酬率') // 3rd: 還原預設排序
    expect(sortHeaderIcon('報酬率')).toBe('↕')
    expect(topLevelRowStockIds()).toEqual(['BBBB B股', 'AAAA A股', 'CCCC C股'])

    clickSortHeader('報酬率') // 4th: 降冪 again
    expect(sortHeaderIcon('報酬率')).toBe('▼')
    expect(topLevelRowStockIds()).toEqual(['AAAA A股', 'BBBB B股', 'CCCC C股'])
  })

  it('restarts at 降冪 when switching to the OTHER sortable column, clearing the previous column\'s icon (only one column sorted at a time)', async () => {
    scanResponder = () => sortableScanResponse()
    backtestResponder = () => ({ status: 200, body: sortableBacktestResponse() })
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    selectStrategy('箱型突破')
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() => expect(screen.getByText('賣出日')).toBeInTheDocument())
    revealIncompletePositions()

    clickSortHeader('報酬率')
    expect(sortHeaderIcon('報酬率')).toBe('▼')

    clickSortHeader('買進價') // switching column -> restarts at 降冪, not continuing 報酬率's cycle
    expect(sortHeaderIcon('買進價')).toBe('▼')
    expect(sortHeaderIcon('報酬率')).toBe('↕') // cleared — only one header shows a direction at once
    expect(topLevelRowStockIds()).toEqual(['BBBB B股', 'AAAA A股', 'CCCC C股']) // 200 > 100 > 「—」

    clickSortHeader('買進價') // 升冪
    expect(topLevelRowStockIds()).toEqual(['CCCC C股', 'AAAA A股', 'BBBB B股'])
  })

  it('breaks a tie on the sorted value using the default order (latest signalDate first) — a stable sort over the already-default-ordered rows', async () => {
    scanResponder = () => tieScanResponse()
    backtestResponder = () => ({ status: 200, body: tieBacktestResponse() })
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    selectStrategy('箱型突破')
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() => expect(screen.getByText('賣出日')).toBeInTheDocument())
    revealIncompletePositions()
    expect(topLevelRowStockIds()).toEqual(['FFFF F股', 'EEEE E股']) // default: latest signalDate (08-19) first

    clickSortHeader('報酬率') // both tied at 7% -> tie broken by default order, unchanged
    expect(topLevelRowStockIds()).toEqual(['FFFF F股', 'EEEE E股'])
  })

  it('sorts a multi-buy-date parent row by the value it DISPLAYS (its cost-weighted aggregate), not by any single child\'s own return', async () => {
    scanResponder = () => parentSortScanResponse()
    backtestResponder = () => ({ status: 200, body: parentSortBacktestResponse() })
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    fireEvent.click(within(screen.getByText('箱型突破').closest('.st-strategy-card')!).getByRole('checkbox'))
    fireEvent.click(within(screen.getByText('底底高').closest('.st-strategy-card')!).getByRole('checkbox'))
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() => expect(screen.getByText('賣出日')).toBeInTheDocument())
    revealIncompletePositions()

    const row2330 = screen.getByLabelText('納入 2330 全部計算').closest('tr') as HTMLElement
    // Cost-weighted aggregate of children +10%/-10% is -9.80% — neither child's own value.
    expect(within(row2330).getByText('-9.80%')).toBeInTheDocument()

    clickSortHeader('報酬率') // 降冪 by the DISPLAYED value: 9999 20% > 8888 0% > 2330 -9.80%
    // If 2330 were sorted by its best child's own +10% instead, it would land ABOVE 8888.
    expect(topLevelRowStockIds()).toEqual(['9999 玖玖', '8888 捌捌', '2330 台積電'])
  })

  it('treats a multi-buy-date parent whose children are ALL unchecked (displaying 「—」) as the minimum on the next header click', async () => {
    scanResponder = () => parentSortScanResponse()
    backtestResponder = () => ({ status: 200, body: parentSortBacktestResponse() })
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    fireEvent.click(within(screen.getByText('箱型突破').closest('.st-strategy-card')!).getByRole('checkbox'))
    fireEvent.click(within(screen.getByText('底底高').closest('.st-strategy-card')!).getByRole('checkbox'))
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() => expect(screen.getByText('賣出日')).toBeInTheDocument())
    revealIncompletePositions()

    const row2330 = screen.getByLabelText('納入 2330 全部計算').closest('tr') as HTMLElement
    fireEvent.click(row2330) // fully checked -> click unchecks every child
    await waitFor(() => expect(within(row2330).getAllByText('—').length).toBeGreaterThan(0))

    clickSortHeader('報酬率') // 降冪: 2330 now displays 「—」, sorts last
    expect(topLevelRowStockIds()).toEqual(['9999 玖玖', '8888 捌捌', '2330 台積電'])

    clickSortHeader('報酬率') // 升冪: 「—」 sorts first
    expect(topLevelRowStockIds()).toEqual(['2330 台積電', '8888 捌捌', '9999 玖玖'])
  })

  it('freezes row order at the moment of the header click — toggling a checkbox afterward changes what a parent DISPLAYS but never moves any row until the next header click', async () => {
    scanResponder = () => parentSortScanResponse()
    backtestResponder = () => ({ status: 200, body: parentSortBacktestResponse() })
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    fireEvent.click(within(screen.getByText('箱型突破').closest('.st-strategy-card')!).getByRole('checkbox'))
    fireEvent.click(within(screen.getByText('底底高').closest('.st-strategy-card')!).getByRole('checkbox'))
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() => expect(screen.getByText('賣出日')).toBeInTheDocument())
    revealIncompletePositions()
    fireEvent.click(screen.getByRole('button', { name: '展開 2330 的訊號日明細' }))

    clickSortHeader('報酬率') // 降冪: 9999 20% > 8888 0% > 2330 -9.80%
    expect(topLevelRowStockIds()).toEqual(['9999 玖玖', '8888 捌捌', '2330 台積電'])

    // Uncheck 2330's losing (-10%) child, leaving only its +10% child checked — its
    // displayed aggregate becomes +10%, now HIGHER than 8888's 0%. The row must not move.
    fireEvent.click(screen.getByLabelText('納入 2330 2026-08-20 計算'))
    const row2330 = screen.getByLabelText('納入 2330 全部計算').closest('tr') as HTMLElement
    await waitFor(() => expect(within(row2330).getByText('10.00%')).toBeInTheDocument())
    expect(topLevelRowStockIds()).toEqual(['9999 玖玖', '8888 捌捌', '2330 台積電'])

    // Only the NEXT header click re-sorts, using the now-current displayed values
    // (9999=20%, 8888=0%, 2330=10%): 升冪 order is 8888, 2330, 9999.
    clickSortHeader('報酬率')
    expect(topLevelRowStockIds()).toEqual(['8888 捌捌', '2330 台積電', '9999 玖玖'])
  })

  it("sorts an expanded parent's own child rows by the same column/direction, restoring buyDate-desc order on 還原預設排序", async () => {
    scanResponder = () => twoDateSingleStockScanResponse()
    backtestResponder = () => ({ status: 200, body: twoDateSingleStockBacktestResponse() })
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    fireEvent.click(within(screen.getByText('箱型突破').closest('.st-strategy-card')!).getByRole('checkbox'))
    fireEvent.click(within(screen.getByText('底底高').closest('.st-strategy-card')!).getByRole('checkbox'))
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() => expect(screen.getByText('賣出日')).toBeInTheDocument())
    revealIncompletePositions()
    fireEvent.click(screen.getByRole('button', { name: '展開 2330 的訊號日明細' }))

    const table = screen.getByRole('table')
    const childBuyDates = () =>
      within(table)
        .getAllByRole('row')
        .filter((r) => r.className.includes('st-child-row'))
        .map((r) => within(r).getAllByRole('cell')[4].textContent)

    expect(childBuyDates()).toEqual(['2026-08-27', '2026-08-20']) // default: buyDate desc

    clickSortHeader('報酬率') // 降冪: +10% (08-27) then -10% (08-20) — matches default here
    expect(childBuyDates()).toEqual(['2026-08-27', '2026-08-20'])

    clickSortHeader('報酬率') // 升冪: -10% (08-20) first — now DIFFERS from default order
    expect(childBuyDates()).toEqual(['2026-08-20', '2026-08-27'])

    clickSortHeader('報酬率') // 還原預設排序
    expect(childBuyDates()).toEqual(['2026-08-27', '2026-08-20'])
  })

  it("keeps a collapsed parent's own buyDate/sellDate lines in buyDate-desc order under any active sort direction", async () => {
    scanResponder = () => twoDateSingleStockScanResponse()
    backtestResponder = () => ({ status: 200, body: twoDateSingleStockBacktestResponse() })
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    fireEvent.click(within(screen.getByText('箱型突破').closest('.st-strategy-card')!).getByRole('checkbox'))
    fireEvent.click(within(screen.getByText('底底高').closest('.st-strategy-card')!).getByRole('checkbox'))
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() => expect(screen.getByText('賣出日')).toBeInTheDocument())
    revealIncompletePositions()
    // Deliberately NOT expanded — this is the collapsed-row listing.

    const row2330 = screen.getByLabelText('納入 2330 全部計算').closest('tr') as HTMLElement
    const buyDateCell = within(row2330).getAllByRole('cell')[4]
    const dates = () => within(buyDateCell).getAllByText(/^\d{4}-\d{2}-\d{2}$/).map((el) => el.textContent)
    expect(dates()).toEqual(['2026-08-27', '2026-08-20'])

    clickSortHeader('報酬率') // 降冪
    expect(dates()).toEqual(['2026-08-27', '2026-08-20'])

    // 升冪 would reverse an expanded parent's OWN child rows (see the previous test) — the
    // collapsed listing must stay in buyDate-desc order regardless.
    clickSortHeader('報酬率')
    expect(dates()).toEqual(['2026-08-27', '2026-08-20'])
  })

  it('sorts entirely client-side: zero network requests, and leaves checkbox state, 共 N 檔, totals and 未計入 notes unchanged', async () => {
    scanResponder = () => sortableScanResponse()
    backtestResponder = () => ({ status: 200, body: sortableBacktestResponse() })
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    selectStrategy('箱型突破')
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() => expect(screen.getByText('賣出日')).toBeInTheDocument())
    revealIncompletePositions()

    const table = screen.getByRole('table')
    const callsBefore = fetchMock.mock.calls.length
    const checkedBefore = (within(table).getAllByRole('checkbox') as HTMLInputElement[]).map((cb) => cb.checked)
    const totalsBefore = Array.from(document.querySelectorAll('.st-total-value')).map((el) => el.textContent)
    expect(screen.getByText('命中彙總 — 共 3 檔')).toBeInTheDocument()
    expect(screen.getByText('另 1 筆尚無可賣出交易日，未計入')).toBeInTheDocument()

    clickSortHeader('報酬率')

    expect(fetchMock.mock.calls.length).toBe(callsBefore)
    expect((within(table).getAllByRole('checkbox') as HTMLInputElement[]).map((cb) => cb.checked)).toEqual(checkedBefore)
    expect(Array.from(document.querySelectorAll('.st-total-value')).map((el) => el.textContent)).toEqual(totalsBefore)
    expect(screen.getByText('命中彙總 — 共 3 檔')).toBeInTheDocument()
    expect(screen.getByText('另 1 筆尚無可賣出交易日，未計入')).toBeInTheDocument()
  })

  it('resets sort to default (icons back to 「↕」) the instant 開始掃描 re-scans', async () => {
    scanResponder = () => sortableScanResponse()
    backtestResponder = () => ({ status: 200, body: sortableBacktestResponse() })
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    selectStrategy('箱型突破')
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() => expect(screen.getByText('賣出日')).toBeInTheDocument())
    revealIncompletePositions()

    clickSortHeader('報酬率')
    expect(sortHeaderIcon('報酬率')).toBe('▼')

    scanResponder = () => boxScanResponse()
    backtestResponder = () => ({ status: 200, body: singleBacktestResponse() })
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() => expect(screen.getByText('賣出日')).toBeInTheDocument())
    revealIncompletePositions()
    expect(sortHeaderIcon('報酬率')).toBe('↕')
    expect(sortHeaderIcon('買進價')).toBe('↕')
  })

  it('shows default sort order (idle 「↕」 icons) after a failed auto-回測 is retried successfully — no sortable header existed to have been sorted before the retry', async () => {
    scanResponder = () => sortableScanResponse()
    backtestResponder = () => ({ status: 500, body: {} })
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    selectStrategy('箱型突破')
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() => expect(screen.getByText('回測失敗，請稍後再試')).toBeInTheDocument())
    expect(screen.queryByText('買進價')).not.toBeInTheDocument()

    backtestResponder = () => ({ status: 200, body: sortableBacktestResponse() })
    fireEvent.click(screen.getByRole('button', { name: '重試回測' }))
    await waitFor(() => expect(screen.getByText('賣出日')).toBeInTheDocument())
    revealIncompletePositions()
    expect(sortHeaderIcon('報酬率')).toBe('↕')
    expect(sortHeaderIcon('買進價')).toBe('↕')
    expect(topLevelRowStockIds()).toEqual(['BBBB B股', 'AAAA A股', 'CCCC C股'])
  })

  // ---------------- 收益欄排序 ----------------

  it('cycles 收益 through 降冪 → 升冪 → 還原預設排序 → 降冪… on repeated clicks, independently of 買進價／報酬率', async () => {
    scanResponder = () => sortableScanResponse()
    backtestResponder = () => ({ status: 200, body: sortableBacktestResponse() })
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    selectStrategy('箱型突破')
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() => expect(screen.getByText('賣出日')).toBeInTheDocument())
    revealIncompletePositions()
    expect(sortHeaderIcon('收益（每筆 1 張）')).toBe('↕')

    clickSortHeader('收益（每筆 1 張）') // 1st: 降冪 — AAAA 5,000 > BBBB -6,000 > CCCC 「—」(min)
    expect(sortHeaderIcon('收益（每筆 1 張）')).toBe('▼')
    expect(topLevelRowStockIds()).toEqual(['AAAA A股', 'BBBB B股', 'CCCC C股'])

    clickSortHeader('收益（每筆 1 張）') // 2nd: 升冪 — CCCC 「—」(min) first
    expect(sortHeaderIcon('收益（每筆 1 張）')).toBe('▲')
    expect(topLevelRowStockIds()).toEqual(['CCCC C股', 'BBBB B股', 'AAAA A股'])

    clickSortHeader('收益（每筆 1 張）') // 3rd: 還原預設排序
    expect(sortHeaderIcon('收益（每筆 1 張）')).toBe('↕')
    expect(topLevelRowStockIds()).toEqual(['BBBB B股', 'AAAA A股', 'CCCC C股'])

    clickSortHeader('收益（每筆 1 張）') // 4th: 降冪 again
    expect(sortHeaderIcon('收益（每筆 1 張）')).toBe('▼')
    expect(topLevelRowStockIds()).toEqual(['AAAA A股', 'BBBB B股', 'CCCC C股'])
  })

  it('shows only one sort indicator at a time — sorting by 收益 clears 買進價／報酬率, and vice versa', async () => {
    scanResponder = () => sortableScanResponse()
    backtestResponder = () => ({ status: 200, body: sortableBacktestResponse() })
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    selectStrategy('箱型突破')
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() => expect(screen.getByText('賣出日')).toBeInTheDocument())
    revealIncompletePositions()

    clickSortHeader('收益（每筆 1 張）')
    expect(sortHeaderIcon('收益（每筆 1 張）')).toBe('▼')
    expect(sortHeaderIcon('買進價')).toBe('↕')
    expect(sortHeaderIcon('報酬率')).toBe('↕')

    clickSortHeader('報酬率') // switching column -> 收益's indicator clears
    expect(sortHeaderIcon('報酬率')).toBe('▼')
    expect(sortHeaderIcon('收益（每筆 1 張）')).toBe('↕')

    clickSortHeader('買進價') // switching again -> 報酬率's indicator clears too
    expect(sortHeaderIcon('買進價')).toBe('▼')
    expect(sortHeaderIcon('報酬率')).toBe('↕')
    expect(sortHeaderIcon('收益（每筆 1 張）')).toBe('↕')
  })

  it('treats 收益「—」(unbacktestable, or a parent with every child unchecked) as the minimum, and breaks ties on the default order', async () => {
    // Null case: CCCC has no sellable day (profit null) -> last on 降冪, first on 升冪.
    scanResponder = () => sortableScanResponse()
    backtestResponder = () => ({ status: 200, body: sortableBacktestResponse() })
    const first = renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    selectStrategy('箱型突破')
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() => expect(screen.getByText('賣出日')).toBeInTheDocument())
    revealIncompletePositions()
    clickSortHeader('收益（每筆 1 張）') // 降冪
    expect(topLevelRowStockIds()).toEqual(['AAAA A股', 'BBBB B股', 'CCCC C股'])
    clickSortHeader('收益（每筆 1 張）') // 升冪
    expect(topLevelRowStockIds()).toEqual(['CCCC C股', 'BBBB B股', 'AAAA A股'])
    first.unmount()

    // Tie case: EEEE and FFFF both have profit 7,000 -> tie broken by default order
    // (latest signalDate first — FFFF 08-19 before EEEE 08-18).
    scanResponder = () => tieScanResponse()
    backtestResponder = () => ({ status: 200, body: tieBacktestResponse() })
    const second = renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    selectStrategy('箱型突破')
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() => expect(screen.getByText('賣出日')).toBeInTheDocument())
    revealIncompletePositions()
    expect(topLevelRowStockIds()).toEqual(['FFFF F股', 'EEEE E股'])
    clickSortHeader('收益（每筆 1 張）')
    expect(topLevelRowStockIds()).toEqual(['FFFF F股', 'EEEE E股'])
    second.unmount()

    // Parent-all-unchecked case: 2330's two children both unchecked -> displays 「—」,
    // sorts last on 降冪.
    scanResponder = () => parentSortScanResponse()
    backtestResponder = () => ({ status: 200, body: parentSortBacktestResponse() })
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    fireEvent.click(within(screen.getByText('箱型突破').closest('.st-strategy-card')!).getByRole('checkbox'))
    fireEvent.click(within(screen.getByText('底底高').closest('.st-strategy-card')!).getByRole('checkbox'))
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() => expect(screen.getByText('賣出日')).toBeInTheDocument())
    revealIncompletePositions()
    const row2330 = screen.getByLabelText('納入 2330 全部計算').closest('tr') as HTMLElement
    fireEvent.click(row2330) // fully checked -> click unchecks every child
    await waitFor(() => expect(within(row2330).getAllByText('—').length).toBeGreaterThan(0))
    clickSortHeader('收益（每筆 1 張）') // 降冪: 2330 now displays 「—」, sorts last
    expect(topLevelRowStockIds()).toEqual(['9999 玖玖', '8888 捌捌', '2330 台積電'])
  })

  it("sorts a multi-buy-date parent row by its DISPLAYED 收益 (sum of checked children's profit), and sorts its expanded child rows the same direction — collapsed buyDate/sellDate lines stay newest-first", async () => {
    scanResponder = () => parentSortScanResponse()
    backtestResponder = () => ({ status: 200, body: parentSortBacktestResponse() })
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    fireEvent.click(within(screen.getByText('箱型突破').closest('.st-strategy-card')!).getByRole('checkbox'))
    fireEvent.click(within(screen.getByText('底底高').closest('.st-strategy-card')!).getByRole('checkbox'))
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() => expect(screen.getByText('賣出日')).toBeInTheDocument())
    revealIncompletePositions()

    const row2330 = screen.getByLabelText('納入 2330 全部計算').closest('tr') as HTMLElement
    // 2330's displayed 收益 is the sum of its two checked children: 10,000 + (-1,000,000).
    expect(within(row2330).getByText('-990,000')).toBeInTheDocument()

    clickSortHeader('收益（每筆 1 張）') // 降冪: 9999 20,000 > 8888 0 > 2330 -990,000
    expect(topLevelRowStockIds()).toEqual(['9999 玖玖', '8888 捌捌', '2330 台積電'])

    // 父列摺疊時逐行列出的買進日／賣出日順序不變 — collapsed listing stays buyDate-desc
    // under any active sort, per the same rule 買進價／報酬率排序 already exercises.
    const buyDateCell = within(row2330).getAllByRole('cell')[4]
    const dates = () => within(buyDateCell).getAllByText(/^\d{4}-\d{2}-\d{2}$/).map((el) => el.textContent)
    expect(dates()).toEqual(['2026-08-27', '2026-08-20'])

    // Now the single-buy-date-per-stock fixture, expanded, to check the CHILD rows' own
    // order under 收益 sort (its two children: +10,000 (08-27) and -1,000,000 (08-20)).
    scanResponder = () => twoDateSingleStockScanResponse()
    backtestResponder = () => ({ status: 200, body: twoDateSingleStockBacktestResponse() })
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() => expect(screen.getByText('賣出日')).toBeInTheDocument())
    revealIncompletePositions()
    fireEvent.click(screen.getByRole('button', { name: '展開 2330 的訊號日明細' }))

    const table = screen.getByRole('table')
    const childBuyDates = () =>
      within(table)
        .getAllByRole('row')
        .filter((r) => r.className.includes('st-child-row'))
        .map((r) => within(r).getAllByRole('cell')[4].textContent)

    clickSortHeader('收益（每筆 1 張）') // 降冪: +10,000 (08-27) then -1,000,000 (08-20) — matches default here
    expect(childBuyDates()).toEqual(['2026-08-27', '2026-08-20'])

    clickSortHeader('收益（每筆 1 張）') // 升冪: -1,000,000 (08-20) first — differs from default order
    expect(childBuyDates()).toEqual(['2026-08-20', '2026-08-27'])

    clickSortHeader('收益（每筆 1 張）') // 還原預設排序
    expect(childBuyDates()).toEqual(['2026-08-27', '2026-08-20'])
  })

  it('sorting by 收益 does not move rows on a checkbox toggle, resets to default on 重新掃描／重試回測 success, and never issues a network request', async () => {
    scanResponder = () => sortableScanResponse()
    backtestResponder = () => ({ status: 200, body: sortableBacktestResponse() })
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    selectStrategy('箱型突破')
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() => expect(screen.getByText('賣出日')).toBeInTheDocument())
    revealIncompletePositions()

    clickSortHeader('收益（每筆 1 張）') // 降冪: AAAA > BBBB > CCCC
    expect(topLevelRowStockIds()).toEqual(['AAAA A股', 'BBBB B股', 'CCCC C股'])

    const callsBefore = fetchMock.mock.calls.length
    fireEvent.click(screen.getByLabelText('納入 BBBB 計算')) // toggling a checkbox never reorders
    expect(topLevelRowStockIds()).toEqual(['AAAA A股', 'BBBB B股', 'CCCC C股'])
    expect(fetchMock.mock.calls.length).toBe(callsBefore) // no network request either

    // 重新掃描 resets the sort to default.
    scanResponder = () => boxScanResponse()
    backtestResponder = () => ({ status: 200, body: singleBacktestResponse() })
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() => expect(screen.getByText('賣出日')).toBeInTheDocument())
    revealIncompletePositions()
    expect(sortHeaderIcon('收益（每筆 1 張）')).toBe('↕')

    // 「重試回測」success also lands on default order.
    scanResponder = () => sortableScanResponse()
    backtestResponder = () => ({ status: 500, body: {} })
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() => expect(screen.getByText('回測失敗，請稍後再試')).toBeInTheDocument())
    backtestResponder = () => ({ status: 200, body: sortableBacktestResponse() })
    fireEvent.click(screen.getByRole('button', { name: '重試回測' }))
    await waitFor(() => expect(screen.getByText('賣出日')).toBeInTheDocument())
    revealIncompletePositions()
    expect(sortHeaderIcon('收益（每筆 1 張）')).toBe('↕')
    expect(topLevelRowStockIds()).toEqual(['BBBB B股', 'AAAA A股', 'CCCC C股'])
  })

  // ---------------- 收益扣手續費與證交稅 ----------------

  it('renders 收益／報酬率 exactly as returned by the backend (already net of fee/tax) — never a self-computed (賣出價 − 買進價) × 1000', async () => {
    scanResponder = () => boxScanResponse() // hits 2330, buyDate 2026-08-27
    backtestResponder = () => ({
      status: 200,
      body: {
        asOfDate: '2026-09-10',
        lotSize: 1000,
        totalCost: 2423448,
        totalProfit: 15000,
        totalReturnPercent: 0.62,
        backtestedCount: 1,
        feeRatePercent: 0.1425,
        taxRatePercent: 0.3,
        items: [
          {
            stockId: '2330',
            buyDate: '2026-08-27',
            buyPrice: 2420,
            sellDate: '2026-09-01',
            sellPrice: 2450,
            // Deliberately NOT the gross (賣出價 − 買進價) ÷ 買進價 × 100 = 1.24% /
            // (賣出價 − 買進價) × 1000 = 30,000 — these are the net-of-cost figures.
            returnPercent: 0.62,
            profit: 15000,
            buyFee: 3448,
            sellFee: 3491,
            sellTax: 7350,
            cost: 2423448,
          },
        ],
      },
    })
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    selectStrategy('箱型突破')
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() => expect(screen.getByText('賣出日')).toBeInTheDocument())

    // The gross figures a naive (賣出價 − 買進價) calc would produce must never appear.
    expect(screen.queryByText('1.24%')).not.toBeInTheDocument()
    expect(screen.queryByText('30,000')).not.toBeInTheDocument()
    // The response's own net-of-cost figures, rendered verbatim.
    expect(screen.getAllByText('0.62%').length).toBeGreaterThan(0)
    expect(screen.getAllByText('15,000').length).toBeGreaterThan(0)
  })

  it("parent-row 報酬率 = Σ checked children's profit ÷ Σ checked children's cost × 100 (cost is the response's fee-inclusive field, not buyPrice × lotSize); with everything checked, 總成本／總報酬率／總收益 equal the response's totalCost/totalReturnPercent/totalProfit", async () => {
    scanResponder = () => twoDateSingleStockScanResponse()
    backtestResponder = () => ({
      status: 200,
      body: {
        asOfDate: '2026-09-10',
        lotSize: 1000,
        totalCost: 10111000,
        totalProfit: -991000,
        totalReturnPercent: -9.8,
        backtestedCount: 2,
        feeRatePercent: 0.1425,
        taxRatePercent: 0.3,
        items: [
          {
            stockId: '2330',
            buyDate: '2026-08-27',
            buyPrice: 100,
            sellDate: '2026-08-28',
            sellPrice: 110,
            returnPercent: 8.91,
            profit: 9000,
            buyFee: 142,
            sellFee: 157,
            sellTax: 330,
            cost: 101000, // buyPrice × lotSize (100,000) + buyFee — NOT equal to the former alone
          },
          {
            stockId: '2330',
            buyDate: '2026-08-20',
            buyPrice: 10000,
            sellDate: '2026-08-25',
            sellPrice: 9000,
            returnPercent: -9.99,
            profit: -1000000,
            buyFee: 14250,
            sellFee: 12825,
            sellTax: 27000,
            cost: 10010000,
          },
        ],
      },
    })
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    fireEvent.click(within(screen.getByText('箱型突破').closest('.st-strategy-card')!).getByRole('checkbox'))
    fireEvent.click(within(screen.getByText('底底高').closest('.st-strategy-card')!).getByRole('checkbox'))
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() => expect(screen.getByText('賣出日')).toBeInTheDocument())

    const row2330 = screen.getByLabelText('納入 2330 全部計算').closest('tr') as HTMLElement
    // (9,000 + -1,000,000) ÷ (101,000 + 10,010,000) × 100 = -9.80% — using `cost`, not
    // buyPrice × lotSize (which would give a different denominator and a different number).
    expect(within(row2330).getByText('-9.80%')).toBeInTheDocument()

    const totalValues = Array.from(document.querySelectorAll('.st-total-value')).map((el) => el.textContent)
    expect(totalValues).toEqual(['10,111,000', '-9.80%', '-991,000'])
  })

  it('shows 「收益已扣手續費 {feeRatePercent}%（買賣各一次）與證交稅 {taxRatePercent}%」 sourced from the response, changes when feeRatePercent changes, and only exists after a successful 回測', async () => {
    scanResponder = () => boxScanResponse()
    backtestResponder = () => ({ status: 500, body: {} })
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    selectStrategy('箱型突破')
    expect(screen.queryByText(/收益已扣手續費/)).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    expect(screen.queryByText(/收益已扣手續費/)).not.toBeInTheDocument() // 回測進行中

    await waitFor(() => expect(screen.getByText('回測失敗，請稍後再試')).toBeInTheDocument())
    expect(screen.queryByText(/收益已扣手續費/)).not.toBeInTheDocument() // 回測失敗

    backtestResponder = () => ({ status: 200, body: singleBacktestResponse() })
    fireEvent.click(screen.getByRole('button', { name: '重試回測' }))
    await waitFor(() =>
      expect(screen.getByText('收益已扣手續費 0.1425%（買賣各一次）與證交稅 0.3%')).toBeInTheDocument(),
    )

    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    expect(screen.queryByText(/收益已扣手續費/)).not.toBeInTheDocument() // 重新掃描時消失
  })

  it("reflects the response's own feeRatePercent verbatim — changing the mock response's feeRatePercent to 0.1 changes what is displayed", async () => {
    scanResponder = () => boxScanResponse()
    backtestResponder = () => ({ status: 200, body: { ...singleBacktestResponse(), feeRatePercent: 0.1 } })
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    selectStrategy('箱型突破')
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() =>
      expect(screen.getByText('收益已扣手續費 0.1%（買賣各一次）與證交稅 0.3%')).toBeInTheDocument(),
    )
  })

  it('places 「收益已扣手續費…」directly above the 「未計入」lines, using the same 次要文字色 class, and never inside the floating totals block', async () => {
    scanResponder = () => unionScanResponse()
    backtestResponder = () => ({ status: 200, body: unionBacktestResponse() })
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    fireEvent.click(within(screen.getByText('箱型突破').closest('.st-strategy-card')!).getByRole('checkbox'))
    fireEvent.click(within(screen.getByText('底底高').closest('.st-strategy-card')!).getByRole('checkbox'))
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() => expect(screen.getByText('賣出日')).toBeInTheDocument())

    const feeNote = screen.getByText('收益已扣手續費 0.1425%（買賣各一次）與證交稅 0.3%')
    // Same class (and therefore same 次要文字色 #93a4b8) as the 「未計入」lines.
    expect(feeNote.className).toBe('st-uncounted-note')

    const notesBlock = feeNote.closest('.st-uncounted-notes') as HTMLElement
    expect(notesBlock).not.toBeNull()
    // unionBacktestResponse has an uncountable position (2454, no sellDate) — its 「未計入」
    // line must exist, and the fee note must be the FIRST child, i.e. above it.
    const noteTexts = Array.from(notesBlock.children).map((el) => el.textContent)
    expect(noteTexts[0]).toBe('收益已扣手續費 0.1425%（買賣各一次）與證交稅 0.3%')
    expect(noteTexts.some((t) => t?.includes('未計入'))).toBe(true)

    // Never part of the floating totals — only the three totals themselves float.
    expect(feeNote.closest('.st-floating-totals')).toBeNull()
  })

  // ---------------- 「取消買進價高於 N 元」勾選框 ----------------

  async function runPriceThresholdScan(): Promise<void> {
    scanResponder = () => priceThresholdScanResponse()
    backtestResponder = () => ({ status: 200, body: priceThresholdBacktestResponse() })
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    fireEvent.click(within(screen.getByText('箱型突破').closest('.st-strategy-card')!).getByRole('checkbox'))
    fireEvent.click(within(screen.getByText('底底高').closest('.st-strategy-card')!).getByRole('checkbox'))
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() => expect(screen.getByText('命中彙總 — 共 5 檔')).toBeInTheDocument())
    await waitFor(() => expect(screen.getByText('賣出日')).toBeInTheDocument())
    revealIncompletePositions()
  }

  function priceThresholdCheckbox(): HTMLInputElement {
    return screen.getByLabelText('取消買進價高於金額元') as HTMLInputElement
  }

  function priceThresholdAmountInput(): HTMLInputElement {
    return screen.getByLabelText('取消買進價高於的金額') as HTMLInputElement
  }

  /** `document.querySelector` returns `null` (not a thrown error) when nothing matches yet,
   * so a bare `waitFor(() => document.querySelector(...))` resolves immediately with `null`
   * instead of actually waiting — this throws until the element exists, which `waitFor`
   * does treat as "not ready yet". */
  async function waitForFloatingTotals(): Promise<HTMLElement> {
    return waitFor(() => {
      const el = document.querySelector('.st-floating-totals')
      if (!el) throw new Error('.st-floating-totals not in the document yet')
      return el as HTMLElement
    })
  }

  it('places 取消買進價高於 between 取消全選 and 總成本, defaults the amount to 500, and exists only after a successful 回測', async () => {
    scanResponder = () => boxScanResponse()
    backtestResponder = () => ({ status: 200, body: singleBacktestResponse() })
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    selectStrategy('箱型突破')

    expect(screen.queryByLabelText('取消買進價高於金額元')).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    expect(screen.queryByLabelText('取消買進價高於金額元')).not.toBeInTheDocument() // 掃描成功、回測進行中

    await waitFor(() => expect(screen.getByText('賣出日')).toBeInTheDocument())
    revealIncompletePositions()
    expect(priceThresholdAmountInput().value).toBe('500')

    const container = screen.getByText('總報酬率').closest('.st-backtest-totals') as HTMLElement
    const text = container.textContent ?? ''
    const idx = {
      cancelAll: text.indexOf('取消全選'),
      priceThreshold: text.indexOf('取消買進價高於'),
      cost: text.indexOf('總成本（每筆 1 張）'),
      returnPct: text.indexOf('總報酬率'),
      profit: text.indexOf('總收益（每筆 1 張）'),
    }
    expect(idx.cancelAll).toBe(-1)
    expect(idx.priceThreshold).toBe(-1)
    expect(Array.from(document.querySelectorAll('.st-batch-controls input[type=checkbox]')).map((el) => el.getAttribute('aria-label'))).toEqual(['取消全選', '取消買進價高於金額元', '隱藏資料不齊（無賣出日）'])
    expect(idx.cost).toBeLessThan(idx.returnPct)
    expect(idx.returnPct).toBeLessThan(idx.profit)
  })

  it('hides only prices strictly above 500 and renders the remaining 480 position as a single row', async () => {
    await runPriceThresholdScan()
    fireEvent.click(priceThresholdCheckbox())
    expect(screen.queryByLabelText('納入 2330 2026-08-20 計算')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('納入 3008 計算')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '展開 2330 的訊號日明細' })).not.toBeInTheDocument()
    expect(screen.getByLabelText('納入 2330 計算')).toBeChecked()
    expect(screen.getByLabelText('納入 1101 計算')).toBeChecked()
    expect(screen.getByLabelText('納入 AAAA 計算')).toBeChecked()
    expect(screen.getByLabelText('納入 CCCC 計算')).toBeChecked()
    expect(screen.getByText('另 2 筆已隱藏')).toBeInTheDocument()
    expect(priceThresholdAmountInput()).toBeDisabled()
    fireEvent.click(priceThresholdCheckbox())
    expect(priceThresholdAmountInput()).toBeEnabled()
  })

  it('unchecking it checks the 高價組 back, without touching a row the user had already unchecked by hand', async () => {
    await runPriceThresholdScan()
    fireEvent.click(screen.getByRole('button', { name: '展開 2330 的訊號日明細' }))
    // 先手動取消一筆低價列（不在高價組）。
    fireEvent.click(screen.getByLabelText('納入 AAAA 計算'))
    expect((screen.getByLabelText('納入 AAAA 計算') as HTMLInputElement).checked).toBe(false)

    fireEvent.click(priceThresholdCheckbox()) // 勾選 -> 高價組全部取消
    fireEvent.click(priceThresholdCheckbox()) // 再取消勾選 -> 高價組全部勾回

    expect((screen.getByLabelText('納入 2330 2026-08-20 計算') as HTMLInputElement).checked).toBe(true)
    expect((screen.getByLabelText('納入 3008 計算') as HTMLInputElement).checked).toBe(true)
    // AAAA 從未在高價組內，兩次切換都不曾動它——維持使用者先前手動取消的狀態。
    expect((screen.getByLabelText('納入 AAAA 計算') as HTMLInputElement).checked).toBe(false)
  })

  it('is disabled and unchecked when nothing is above the amount', async () => {
    await runPriceThresholdScan()
    fireEvent.change(priceThresholdAmountInput(), { target: { value: '10000' } })

    const checkbox = priceThresholdCheckbox()
    expect(checkbox.disabled).toBe(true)
    expect(checkbox.checked).toBe(false)
  })

  it.each(['', '-1', '1.234'])('is disabled and shows the #F09A94 hint for an invalid amount (%s)', async (invalid) => {
    await runPriceThresholdScan()
    fireEvent.change(priceThresholdAmountInput(), { target: { value: invalid } })

    expect(priceThresholdCheckbox().disabled).toBe(true)
    expect(screen.getByText('金額需為 0 以上、最多兩位小數')).toBeInTheDocument()
  })

  it('remembers its own hidden state: manual high-price deselection and cancel-all never check the price switch', async () => {
    await runPriceThresholdScan()
    fireEvent.click(screen.getByRole('button', { name: '展開 2330 的訊號日明細' }))
    fireEvent.click(screen.getByLabelText('納入 2330 2026-08-20 計算'))
    fireEvent.click(screen.getByLabelText('納入 3008 計算'))
    expect(priceThresholdCheckbox()).not.toBeChecked()
    fireEvent.click(screen.getByLabelText('取消全選'))
    expect(priceThresholdCheckbox()).not.toBeChecked()
  })

  it('never toggles any position when the amount itself is edited — only the checkbox derivation changes', async () => {
    await runPriceThresholdScan()
    const callsBefore = fetchMock.mock.calls.length
    const checkedBefore = (screen.getAllByRole('checkbox') as HTMLInputElement[])
      .filter((cb) => cb !== priceThresholdCheckbox())
      .map((cb) => cb.checked)

    fireEvent.change(priceThresholdAmountInput(), { target: { value: '450' } })

    const checkedAfter = (screen.getAllByRole('checkbox') as HTMLInputElement[])
      .filter((cb) => cb !== priceThresholdCheckbox())
      .map((cb) => cb.checked)
    expect(checkedAfter).toEqual(checkedBefore)
    expect(fetchMock.mock.calls.length).toBe(callsBefore)
    // The checkbox's own derived state does change (450 now covers 480/500/520/600).
    expect(priceThresholdCheckbox().checked).toBe(false)
  })

  it('hides a collapsed high-price child without affecting the remaining visible position', async () => {
    await runPriceThresholdScan()
    fireEvent.click(priceThresholdCheckbox())
    expect(screen.queryByRole('button', { name: '展開 2330 的訊號日明細' })).not.toBeInTheDocument()
    expect(screen.getByLabelText('納入 2330 計算')).toBeChecked()
    fireEvent.click(priceThresholdCheckbox())
    fireEvent.click(screen.getByRole('button', { name: '展開 2330 的訊號日明細' }))
    expect(screen.getByLabelText('納入 2330 2026-08-20 計算')).toBeChecked()
  })

  it('drops a multi-buy-date stock from the table entirely once every one of its positions is hidden, while 共 N 檔 and 另 K 筆已隱藏 both still count it', async () => {
    await runPriceThresholdScan()
    // 400 puts BOTH of 2330's buy dates (480, 520) in the 高價組, along with 1101 (500) and
    // 3008 (600) — AAAA (100) and CCCC (null) stay untouched. 4 positions hidden total.
    fireEvent.change(priceThresholdAmountInput(), { target: { value: '400' } })
    fireEvent.click(priceThresholdCheckbox())
    expect(screen.queryByText('2330 台積電')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '展開 2330 的訊號日明細' })).not.toBeInTheDocument()
    expect(screen.queryByLabelText('納入 1101 計算')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('納入 3008 計算')).not.toBeInTheDocument()
    expect(screen.getByLabelText('納入 AAAA 計算')).toBeInTheDocument()
    // 標題「共 N 檔」不變——即使 2330 整檔（兩筆）都被隱藏。
    expect(screen.getByText('命中彙總 — 共 5 檔')).toBeInTheDocument()
    expect(screen.getByText('另 4 筆已隱藏')).toBeInTheDocument()
    // 取消勾選後 2330 整檔（含兩個買進日）重新出現。
    fireEvent.click(priceThresholdCheckbox())
    expect(screen.getByText('2330 台積電')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '展開 2330 的訊號日明細' })).toBeInTheDocument()
  })

  it('recomputes the three totals, the parent aggregate, and 取消全選 the instant it is toggled — with no network request', async () => {
    await runPriceThresholdScan()
    fireEvent.click(screen.getByRole('button', { name: '展開 2330 的訊號日明細' }))
    const callsBefore = fetchMock.mock.calls.length

    fireEvent.click(priceThresholdCheckbox())

    expect(fetchMock.mock.calls.length).toBe(callsBefore)
    // Checked+backtestable after the toggle: 2330@0827 (480, profit 20,000), 1101 (500,
    // profit 10,000), AAAA (100, profit 10,000). Excluded: 2330@0820 (520, now unchecked),
    // 3008 (600, now unchecked, also unbacktestable anyway), CCCC (buyPrice null, no
    // sellDate). cost = 1,080,000; profit = 40,000; return = 40,000/1,080,000*100 ≈ 3.70%.
    expect(Array.from(document.querySelectorAll('.st-totals-anchor .st-total-value')).map((el) => el.textContent)).toEqual([
      '1,080,000',
      '3.70%',
      '40,000',
    ])
    const parentCheckbox = screen.getByLabelText('納入 2330 計算') as HTMLInputElement
    expect(parentCheckbox.checked).toBe(true)
    expect(parentCheckbox.indeterminate).toBe(false)
    expect((screen.getByLabelText('取消全選') as HTMLInputElement).checked).toBe(false) // 還有其餘筆勾選著
  })

  it('keeps the user-entered amount across a re-scan (only a page reload resets it to 500), and follows the 取消全選 lifecycle (gone on failed 回測, back on 重試回測 success, cleared on 重新掃描)', async () => {
    await runPriceThresholdScan()
    fireEvent.change(priceThresholdAmountInput(), { target: { value: '750' } })
    expect(priceThresholdAmountInput().value).toBe('750')

    scanResponder = () => priceThresholdScanResponse()
    backtestResponder = () => ({ status: 500, body: {} })
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() => expect(screen.getByText('回測失敗，請稍後再試')).toBeInTheDocument())
    expect(screen.queryByLabelText('取消買進價高於金額元')).not.toBeInTheDocument()

    backtestResponder = () => ({ status: 200, body: priceThresholdBacktestResponse() })
    fireEvent.click(screen.getByRole('button', { name: '重試回測' }))
    await waitFor(() => expect(screen.getByLabelText('取消買進價高於金額元')).toBeInTheDocument())
    // 金額在同一次進頁內保留 — 重試回測不重設它。
    expect(priceThresholdAmountInput().value).toBe('750')

    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    expect(screen.queryByLabelText('取消買進價高於金額元')).not.toBeInTheDocument()
  })

  it('has no effect and disabled+unchecked state when the amount is left at its default with nothing above 500 (empty 高價組 across the whole hit list)', async () => {
    scanResponder = () => boxScanResponse() // 2330 only, buyPrice 2420 in singleBacktestResponse — but this test raises the bar instead
    backtestResponder = () => ({ status: 200, body: singleBacktestResponse() })
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    selectStrategy('箱型突破')
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() => expect(screen.getByText('賣出日')).toBeInTheDocument())
    revealIncompletePositions()
    fireEvent.change(priceThresholdAmountInput(), { target: { value: '5000' } })
    expect(priceThresholdCheckbox().disabled).toBe(true)
    expect(priceThresholdCheckbox().checked).toBe(false)
  })

  // Increment 20: exercise the default hidden state directly (no reveal helper).
  async function runHiddenScan() {
    scanResponder = () => priceThresholdScanResponse()
    backtestResponder = () => ({ status: 200, body: priceThresholdBacktestResponse() })
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    selectStrategy('箱型突破')
    selectStrategy('底底高')
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() => expect(screen.getByLabelText('隱藏資料不齊（無賣出日）')).toBeInTheDocument())
  }
  const incompleteCheckbox = () => screen.getByLabelText('隱藏資料不齊（無賣出日）')
  const totalValues = () => Array.from(document.querySelectorAll('.st-totals-anchor .st-total-value')).map((el) => el.textContent)

  it('defaults to hiding null sell dates including null buy prices, preserves their selection and totals on toggles without requests', async () => {
    await runHiddenScan()
    expect(incompleteCheckbox()).toBeChecked()
    expect(screen.queryByLabelText('納入 3008 計算')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('納入 CCCC 計算')).not.toBeInTheDocument()
    expect(screen.getByText('另 2 筆已隱藏')).toBeInTheDocument()
    const totals = totalValues()
    const requests = fetchMock.mock.calls.length
    fireEvent.click(incompleteCheckbox())
    expect(screen.getByLabelText('納入 CCCC 計算')).toBeChecked()
    fireEvent.click(screen.getByLabelText('納入 CCCC 計算'))
    fireEvent.click(incompleteCheckbox())
    fireEvent.click(incompleteCheckbox())
    expect(screen.getByLabelText('納入 CCCC 計算')).not.toBeChecked()
    expect(totalValues()).toEqual(totals)
    expect(screen.getByText('另 2 筆尚無可賣出交易日，未計入')).toBeInTheDocument()
    expect(fetchMock.mock.calls.length).toBe(requests)
  })

  it('takes the union of hidden groups and only reveals overlapping positions after both switches turn off', async () => {
    await runHiddenScan()
    fireEvent.click(priceThresholdCheckbox())
    expect(screen.getByText('另 3 筆已隱藏')).toBeInTheDocument()
    fireEvent.click(incompleteCheckbox())
    expect(screen.getByText('另 2 筆已隱藏')).toBeInTheDocument()
    expect(screen.queryByLabelText('納入 3008 計算')).not.toBeInTheDocument()
    expect(screen.getByLabelText('納入 CCCC 計算')).toBeChecked()
    fireEvent.click(priceThresholdCheckbox())
    expect(screen.getByLabelText('納入 3008 計算')).toBeChecked()
    expect(screen.queryByText(/筆已隱藏/)).not.toBeInTheDocument()
    expect(screen.getByText('命中彙總 — 共 5 檔')).toBeInTheDocument()
  })

  it('cancel-all in either direction clears both hidden switches and affects every position', async () => {
    await runHiddenScan()
    const requests = fetchMock.mock.calls.length
    fireEvent.click(priceThresholdCheckbox())
    fireEvent.click(screen.getByLabelText('取消全選'))
    expect(incompleteCheckbox()).not.toBeChecked()
    expect(priceThresholdCheckbox()).not.toBeChecked()
    expect(screen.getByLabelText('納入 3008 計算')).not.toBeChecked()
    expect(screen.getByText('另 2 筆尚無可賣出交易日，未計入')).toBeInTheDocument()
    fireEvent.click(priceThresholdCheckbox())
    fireEvent.click(incompleteCheckbox())
    fireEvent.click(screen.getByLabelText('取消全選'))
    expect(incompleteCheckbox()).not.toBeChecked()
    expect(priceThresholdCheckbox()).not.toBeChecked()
    expect(screen.getByLabelText('納入 CCCC 計算')).toBeChecked()
    expect(screen.getByLabelText('納入 2330 全部計算')).toBeChecked()
    expect(totalValues()).toEqual(['1,600,000', '1.25%', '20,000'])
    expect(fetchMock.mock.calls.length).toBe(requests)
  })

  it('preserves a sorted snapshot across hide/show, and sorts a filtered parent by its visible single-position price', async () => {
    await runHiddenScan()
    revealIncompletePositions()
    clickSortHeader('買進價')
    const original = topLevelRowStockIds()
    fireEvent.click(priceThresholdCheckbox())
    expect(sortHeaderIcon('買進價')).toBe('▼')
    fireEvent.click(priceThresholdCheckbox())
    expect(topLevelRowStockIds()).toEqual(original)
    fireEvent.click(priceThresholdCheckbox())
    clickSortHeader('買進價') // asc: 100, 480, 500, null (null is first)
    expect(topLevelRowStockIds()).toEqual(['CCCC C股', 'AAAA A股', '2330 台積電', '1101 台泥'])
  })

  it('keeps a three-position parent with only two visible dates, and parent clicks leave its hidden incomplete child checked', async () => {
    const scan = priceThresholdScanResponse()
    // `results[0]`'s and `results[1]`'s `items` arrays carry differently-shaped `detail`
    // literals (`{}` vs `{ lows: [] }`), so TS widens `results` itself to a union-element
    // array — indexing narrows `.items` to a union of the two array types, whose combined
    // `push` signature then demands a `detail` satisfying BOTH shapes at once. This fixture
    // never reads `detail`'s contents, so a targeted widen-then-cast here is the correct
    // fix, not a strict-typing workaround for a real bug.
    ;(scan.results[0].items as { stockId: string; stockName: string; signalDate: string; buyDate: string; detail: Record<string, unknown> }[]).push({
      stockId: '2330',
      stockName: '台積電',
      signalDate: '2026-08-29',
      buyDate: '2026-08-29',
      detail: {},
    })
    const backtest = priceThresholdBacktestResponse()
    backtest.items.push({
      stockId: '2330',
      buyDate: '2026-08-29',
      buyPrice: null,
      sellDate: null,
      sellPrice: null,
      returnPercent: null,
      profit: null,
      buyFee: null,
      sellFee: null,
      sellTax: null,
      cost: null,
    })
    scanResponder = () => scan
    backtestResponder = () => ({ status: 200, body: backtest })
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    selectStrategy('箱型突破')
    selectStrategy('底底高')
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() => expect(incompleteCheckbox()).toBeChecked())
    const parent = screen.getByLabelText('納入 2330 全部計算').closest('tr')!
    expect(within(parent).getAllByRole('cell')[4].textContent).toBe('2026-08-272026-08-20')
    fireEvent.click(screen.getByRole('button', { name: '展開 2330 的訊號日明細' }))
    expect(screen.queryByLabelText('納入 2330 2026-08-29 計算')).not.toBeInTheDocument()
    fireEvent.click(screen.getByLabelText('納入 2330 全部計算'))
    expect(screen.getByLabelText('納入 2330 全部計算')).not.toBeChecked()
    fireEvent.click(within(parent).getAllByRole('cell')[2])
    fireEvent.click(screen.getByLabelText('納入 2330 2026-08-20 計算'))
    expect((screen.getByLabelText('納入 2330 全部計算') as HTMLInputElement).indeterminate).toBe(true)
    fireEvent.click(incompleteCheckbox())
    expect(screen.getByLabelText('納入 2330 2026-08-29 計算')).toBeChecked()
  })

  it('restores the default incomplete filter on retry and on the next successful scan', async () => {
    await runHiddenScan()
    fireEvent.click(incompleteCheckbox())
    backtestResponder = () => ({ status: 500, body: {} })
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    expect(screen.queryByLabelText('隱藏資料不齊（無賣出日）')).not.toBeInTheDocument()
    await waitFor(() => expect(screen.getByRole('button', { name: '重試回測' })).toBeInTheDocument())
    backtestResponder = () => ({ status: 200, body: priceThresholdBacktestResponse() })
    fireEvent.click(screen.getByRole('button', { name: '重試回測' }))
    await waitFor(() => expect(incompleteCheckbox()).toBeChecked())
    expect(screen.queryByLabelText('納入 CCCC 計算')).not.toBeInTheDocument()
    fireEvent.click(incompleteCheckbox())
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() => expect(incompleteCheckbox()).toBeChecked())
  })

  it('disables the incomplete filter unchecked when every position has a sell date', async () => {
    scanResponder = () => boxScanResponse()
    backtestResponder = () => ({ status: 200, body: singleBacktestResponse() })
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    selectStrategy('箱型突破')
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() => expect(incompleteCheckbox()).toBeDisabled())
    expect(incompleteCheckbox()).not.toBeChecked()
  })

  // ---------------- 總計浮動跟隨 ----------------

  /** Mocks `getBoundingClientRect` for exactly the three elements the 總計浮動跟隨 scroll
   * effect reads (`.st-totals-anchor`, `.st-table-end-sentinel`, `.st-union-block`) — jsdom
   * has no real layout engine, so every other element keeps the harmless all-zero default. */
  function mockFloatingRects(anchorTop: number, tableEndTop: number, panelRight = 900): void {
    vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(function (this: HTMLElement) {
      const base = { x: 0, y: 0, width: 0, height: 0, left: 0, bottom: 0, right: 0, top: 0, toJSON: () => ({}) }
      if (this.classList.contains('st-totals-anchor')) return { ...base, top: anchorTop, bottom: anchorTop + 30 } as DOMRect
      if (this.classList.contains('st-table-end-sentinel')) return { ...base, top: tableEndTop, bottom: tableEndTop } as DOMRect
      if (this.classList.contains('st-union-block')) return { ...base, right: panelRight, left: panelRight - 700 } as DOMRect
      return base as DOMRect
    })
  }

  it('does not show the floating totals before any scroll (both measurement points still at their jsdom-default zero rect)', async () => {
    scanResponder = () => boxScanResponse()
    backtestResponder = () => ({ status: 200, body: singleBacktestResponse() })
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    selectStrategy('箱型突破')
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() => expect(screen.getByText('賣出日')).toBeInTheDocument())
    revealIncompletePositions()
    expect(document.querySelector('.st-floating-totals')).not.toBeInTheDocument()
  })

  it('shows the floating totals once the in-flow totals scroll above the viewport top, and hides them again once they scroll back into view', async () => {
    scanResponder = () => boxScanResponse()
    backtestResponder = () => ({ status: 200, body: singleBacktestResponse() })
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    selectStrategy('箱型突破')
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() => expect(screen.getByText('賣出日')).toBeInTheDocument())
    revealIncompletePositions()

    mockFloatingRects(-40, 300)
    window.dispatchEvent(new Event('scroll'))
    await waitFor(() => expect(document.querySelector('.st-floating-totals')).toBeInTheDocument())

    mockFloatingRects(10, 300) // scrolled back — the in-flow totals are visible again
    window.dispatchEvent(new Event('scroll'))
    await waitFor(() => expect(document.querySelector('.st-floating-totals')).not.toBeInTheDocument())
  })

  it('hides the floating totals once the whole hit table has scrolled past the viewport top, even though the anchor is also above it', async () => {
    scanResponder = () => boxScanResponse()
    backtestResponder = () => ({ status: 200, body: singleBacktestResponse() })
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    selectStrategy('箱型突破')
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() => expect(screen.getByText('賣出日')).toBeInTheDocument())
    revealIncompletePositions()

    mockFloatingRects(-500, -20) // anchor long gone AND the table's own end has scrolled past
    window.dispatchEvent(new Event('scroll'))
    await waitFor(() => expect(document.querySelector('.st-floating-totals')).not.toBeInTheDocument())
  })

  it('mirrors the exact same totals values as the in-flow copy, updating together the instant a checkbox is toggled — never a second computation', async () => {
    scanResponder = () => unionScanResponse()
    backtestResponder = () => ({ status: 200, body: unionBacktestResponse() })
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    fireEvent.click(within(screen.getByText('箱型突破').closest('.st-strategy-card')!).getByRole('checkbox'))
    fireEvent.click(within(screen.getByText('底底高').closest('.st-strategy-card')!).getByRole('checkbox'))
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() => expect(screen.getByText('命中彙總 — 共 3 檔')).toBeInTheDocument())
    await waitFor(() => expect(screen.getByText('賣出日')).toBeInTheDocument())
    revealIncompletePositions()

    mockFloatingRects(-40, 300)
    window.dispatchEvent(new Event('scroll'))
    const floating = await waitForFloatingTotals()
    const inFlowValues = () => Array.from(document.querySelectorAll('.st-totals-anchor .st-total-value')).map((el) => el.textContent)
    const floatingValues = () => Array.from(floating.querySelectorAll('.st-total-value')).map((el) => el.textContent)
    expect(floatingValues()).toEqual(inFlowValues())

    fireEvent.click(screen.getByLabelText('納入 2317 計算'))
    expect(floatingValues()).toEqual(inFlowValues())
  })

  it('right-aligns the floating totals to the result panel\'s own right edge', async () => {
    scanResponder = () => boxScanResponse()
    backtestResponder = () => ({ status: 200, body: singleBacktestResponse() })
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    selectStrategy('箱型突破')
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() => expect(screen.getByText('賣出日')).toBeInTheDocument())
    revealIncompletePositions()

    mockFloatingRects(-40, 300, 900)
    window.dispatchEvent(new Event('scroll'))
    const floating = await waitForFloatingTotals()
    // `window.innerWidth` reads whatever the suite's current width is (1920 by default,
    // see setupTests.ts) — this assertion is width-agnostic on purpose; panel right edge
    // mocked at 900.
    expect(floating.style.right).toBe(`${window.innerWidth - 900}px`)
  })

  it('never shows the floating totals before 回測, while 回測 is running, or after 回測 fails — even if a scroll event fires', async () => {
    scanResponder = () => boxScanResponse()
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    selectStrategy('箱型突破')

    mockFloatingRects(-40, 300)
    window.dispatchEvent(new Event('scroll'))
    expect(document.querySelector('.st-floating-totals')).not.toBeInTheDocument() // 回測前

    const baseImpl = fetchMock.getMockImplementation() as (url: string, init?: RequestInit) => Promise<unknown>
    let resolveBacktest: (v: unknown) => void = () => {}
    fetchMock.mockImplementation((url: string, init?: RequestInit) => {
      const u = String(url)
      if (u.startsWith('/api/strategies/backtest')) {
        return new Promise((resolve) => {
          resolveBacktest = () => resolve(jsonResponse(200, singleBacktestResponse()))
        })
      }
      return baseImpl(url, init)
    })
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() => expect(screen.getByText('回測中…')).toBeInTheDocument())
    window.dispatchEvent(new Event('scroll'))
    expect(document.querySelector('.st-floating-totals')).not.toBeInTheDocument() // 回測中

    resolveBacktest(undefined)
    await waitFor(() => expect(screen.getByText('賣出日')).toBeInTheDocument())
    revealIncompletePositions()
    fetchMock.mockImplementation(baseImpl) // restore normal routing (honors backtestResponder again)

    scanResponder = () => boxScanResponse()
    backtestResponder = () => ({ status: 500, body: {} })
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() => expect(screen.getByText('回測失敗，請稍後再試')).toBeInTheDocument())
    window.dispatchEvent(new Event('scroll'))
    expect(document.querySelector('.st-floating-totals')).not.toBeInTheDocument() // 回測失敗
  })

  it('never issues a network request when scrolling, even across multiple visibility transitions', async () => {
    scanResponder = () => boxScanResponse()
    backtestResponder = () => ({ status: 200, body: singleBacktestResponse() })
    renderTab()
    await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
    selectStrategy('箱型突破')
    fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
    await waitFor(() => expect(screen.getByText('賣出日')).toBeInTheDocument())
    revealIncompletePositions()

    const callsBefore = fetchMock.mock.calls.length
    mockFloatingRects(-40, 300)
    window.dispatchEvent(new Event('scroll'))
    mockFloatingRects(10, 300)
    window.dispatchEvent(new Event('scroll'))
    mockFloatingRects(-500, -20)
    window.dispatchEvent(new Event('scroll'))
    window.dispatchEvent(new Event('resize'))
    expect(fetchMock.mock.calls.length).toBe(callsBefore)
  })

  describe('法人籌碼卡片', () => {
    it('renders three institutional cards with names/params/description from GET /api/strategies, and no sensitivity dropdown', async () => {
      renderTab()
      await waitFor(() => expect(screen.getByText('法人買賣超佔比')).toBeInTheDocument())
      expect(screen.getByText('法人連續買超')).toBeInTheDocument()
      expect(screen.getByText('法人買超強度排名')).toBeInTheDocument()

      const netRatioCard = cardFor('法人買賣超佔比')
      expect(
        within(netRatioCard).getByText(
          '外資（不含外資自營商）或投信近指定天數買賣超合計取絕對值 ÷ 成交股數合計，達門檻即命中，淨買超與淨賣超皆計',
        ),
      ).toBeInTheDocument()
      expect(within(netRatioCard).queryByRole('combobox')).not.toBeInTheDocument()
      const consecutiveCard = cardFor('法人連續買超')
      expect(within(consecutiveCard).queryByRole('combobox')).not.toBeInTheDocument()
      const rankCard = cardFor('法人買超強度排名')
      expect(within(rankCard).queryByRole('combobox')).not.toBeInTheDocument()

      // Values/ranges are only populated once a card is selected (matches every other
      // params-driven card's own behavior) — select each and check its inputs picked up the
      // catalogue's own default/min/max, never a front-end-hard-coded number.
      selectStrategy('法人買賣超佔比')
      expect((within(netRatioCard).getByLabelText('天數') as HTMLInputElement).value).toBe('5')
      expect((within(netRatioCard).getByLabelText('佔比門檻') as HTMLInputElement).value).toBe('10')
      selectStrategy('法人連續買超')
      expect((within(consecutiveCard).getByLabelText('連續買超天數') as HTMLInputElement).value).toBe('5')
      selectStrategy('法人買超強度排名')
      expect((within(rankCard).getByLabelText('天數') as HTMLInputElement).value).toBe('5')
      expect((within(rankCard).getByLabelText('取前幾名') as HTMLInputElement).value).toBe('10')
    })

    it('renders all ten cards in the shared responsive grid, with every institutional param rendered as its own real input (none dropped to fit)', async () => {
      renderTab()
      await waitFor(() => expect(screen.getByText('法人買超強度排名')).toBeInTheDocument())
      // .st-strategy-cards is an unmodified `grid-template-columns: repeat(auto-fill,
      // minmax(260px, 1fr))` container (see StockListPage.css) that already reflows at any
      // width without a fixed column count — adding three more cards exercises the same,
      // already-correct wrapping mechanism, not new layout code.
      const cards = document.querySelectorAll('.st-strategy-cards > .st-strategy-card')
      expect(cards.length).toBe(10)
      expect(within(cardFor('法人買賣超佔比')).getByLabelText('天數')).toBeInTheDocument()
      expect(within(cardFor('法人買賣超佔比')).getByLabelText('佔比門檻')).toBeInTheDocument()
      expect(within(cardFor('法人連續買超')).getByLabelText('連續買超天數')).toBeInTheDocument()
      expect(within(cardFor('法人買超強度排名')).getByLabelText('天數')).toBeInTheDocument()
      expect(within(cardFor('法人買超強度排名')).getByLabelText('取前幾名')).toBeInTheDocument()
    })

    it('renders a checkbox row (法人：外資／投信, both checked by default) for a multiSelect param, and reshaping any numeric param to type: "multiSelect" in the catalogue makes IT render checkboxes too — no branch keyed on investors or a strategy code', async () => {
      // Reshape 累積上漲's plain numeric `days` param into a multiSelect — the renderer must
      // key off `type`, not off a hard-coded `investors` name or `CUMULATIVE_RISE` code.
      const reshapedCatalog = {
        strategies: CATALOG.strategies.map((s) =>
          s.code === 'CUMULATIVE_RISE'
            ? {
                ...s,
                params: (s.params as unknown[]).map((p) =>
                  (p as { code: string }).code === 'days'
                    ? {
                        code: 'days',
                        name: '天數',
                        type: 'multiSelect',
                        options: [
                          { code: 'A', name: '選項甲' },
                          { code: 'B', name: '選項乙' },
                        ],
                        default: ['A'],
                        minSelected: 1,
                      }
                    : p,
                ),
              }
            : s,
        ),
      }
      fetchMock.mockImplementation((url: string) => {
        const u = String(url)
        if (u.startsWith('/api/strategies/scan')) return Promise.resolve(jsonResponse(200, scanResponder()))
        if (u.startsWith('/api/strategies')) return Promise.resolve(jsonResponse(200, reshapedCatalog))
        if (u.startsWith('/api/stocks/sync/progress')) return Promise.resolve(jsonResponse(200, progressResponse()))
        return Promise.resolve(jsonResponse(200, {}))
      })

      renderTab()
      await waitFor(() => expect(screen.getByText('法人買賣超佔比')).toBeInTheDocument())
      const netRatioCard = cardFor('法人買賣超佔比')
      expect((within(netRatioCard).getByLabelText('外資') as HTMLInputElement).checked).toBe(true)
      expect((within(netRatioCard).getByLabelText('投信') as HTMLInputElement).checked).toBe(true)

      const card = cardFor('累積上漲')
      expect(within(card).getByLabelText('選項甲')).toHaveAttribute('type', 'checkbox')
      expect(within(card).getByLabelText('選項乙')).toHaveAttribute('type', 'checkbox')
      expect(within(card).queryByLabelText('天數')).not.toBeInTheDocument() // no longer a number input
      expect((within(card).getByLabelText('漲幅門檻') as HTMLInputElement).type).toBe('number') // untouched param
    })

    it('shows 請至少勾選一個法人 and blocks the scan once both 法人 checkboxes are unchecked; re-checking either makes it disappear', async () => {
      renderTab()
      await waitFor(() => expect(screen.getByText('法人買賣超佔比')).toBeInTheDocument())
      selectStrategy('法人買賣超佔比')
      const card = cardFor('法人買賣超佔比')
      fireEvent.click(within(card).getByLabelText('外資'))
      fireEvent.click(within(card).getByLabelText('投信'))

      expect(within(card).getByText('請至少勾選一個法人')).toBeInTheDocument()
      expect(screen.getByRole('button', { name: '開始掃描' })).toBeDisabled()
      const scanCalls = fetchMock.mock.calls.filter((c) => String(c[0]).startsWith('/api/strategies/scan')).length
      expect(scanCalls).toBe(0)

      fireEvent.click(within(card).getByLabelText('外資'))
      expect(within(card).queryByText('請至少勾選一個法人')).not.toBeInTheDocument()
      expect(screen.getByRole('button', { name: '開始掃描' })).not.toBeDisabled()
    })

    it('sends only investors/windowDays/ratioPercent for 法人買賣超佔比 — never preset/risePercent/days — investors ordered FOREIGN before TRUST', async () => {
      scanResponder = () => netRatioScanResponse()
      renderTab()
      await waitFor(() => expect(screen.getByText('法人買賣超佔比')).toBeInTheDocument())
      selectStrategy('法人買賣超佔比')
      fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))

      const scanCall = await vi.waitFor(() =>
        fetchMock.mock.calls.find((c) => String(c[0]).startsWith('/api/strategies/scan')),
      )
      const body = JSON.parse((scanCall![1] as RequestInit).body as string)
      expect(body.strategies).toEqual([{ code: 'INSTITUTIONAL_NET_RATIO', investors: ['FOREIGN', 'TRUST'], windowDays: 5, ratioPercent: 10 }])
    })

    it('sends only investors/buyDays for 法人連續買超 — never preset/risePercent/days — and investors is ["TRUST"] when only 投信 stays checked', async () => {
      scanResponder = () => consecutiveBuyScanResponse()
      renderTab()
      await waitFor(() => expect(screen.getByText('法人連續買超')).toBeInTheDocument())
      selectStrategy('法人連續買超')
      const card = cardFor('法人連續買超')
      fireEvent.click(within(card).getByLabelText('外資')) // uncheck 外資, leaving only 投信
      fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))

      const scanCall = await vi.waitFor(() =>
        fetchMock.mock.calls.find((c) => String(c[0]).startsWith('/api/strategies/scan')),
      )
      const body = JSON.parse((scanCall![1] as RequestInit).body as string)
      expect(body.strategies).toEqual([{ code: 'INSTITUTIONAL_CONSECUTIVE_BUY', investors: ['TRUST'], buyDays: 5 }])
    })

    it('sends only investors/windowDays/topN for 法人買超強度排名 — never preset/risePercent/days', async () => {
      scanResponder = () => strengthRankScanResponse()
      renderTab()
      await waitFor(() => expect(screen.getByText('法人買超強度排名')).toBeInTheDocument())
      selectStrategy('法人買超強度排名')
      fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))

      const scanCall = await vi.waitFor(() =>
        fetchMock.mock.calls.find((c) => String(c[0]).startsWith('/api/strategies/scan')),
      )
      const body = JSON.parse((scanCall![1] as RequestInit).body as string)
      expect(body.strategies).toEqual([{ code: 'INSTITUTIONAL_STRENGTH_RANK', investors: ['FOREIGN', 'TRUST'], windowDays: 5, topN: 10 }])
    })

    it.each([
      ['法人買賣超佔比', '天數', '0', '天數需介於 1 ~ 20 的整數'],
      ['法人買賣超佔比', '天數', '21', '天數需介於 1 ~ 20 的整數'],
      ['法人買賣超佔比', '天數', '5.5', '天數需介於 1 ~ 20 的整數'],
      ['法人買賣超佔比', '佔比門檻', '-1', '佔比門檻需介於 0 ~ 100'],
      ['法人買賣超佔比', '佔比門檻', '100.1', '佔比門檻需介於 0 ~ 100'],
      ['法人買賣超佔比', '佔比門檻', '10.55', '佔比門檻需介於 0 ~ 100'],
      ['法人連續買超', '連續買超天數', '0', '連續買超天數需介於 1 ~ 20 的整數'],
      ['法人連續買超', '連續買超天數', '21', '連續買超天數需介於 1 ~ 20 的整數'],
      ['法人買超強度排名', '取前幾名', '0', '取前幾名需介於 1 ~ 50 的整數'],
      ['法人買超強度排名', '取前幾名', '51', '取前幾名需介於 1 ~ 50 的整數'],
    ])(
      'blocks the scan and shows the range error for %s＝%s with value %s (message: %s)',
      async (cardName, label, bad, expected) => {
        renderTab()
        await waitFor(() => expect(screen.getByText(cardName)).toBeInTheDocument())
        selectStrategy(cardName)
        const card = cardFor(cardName)
        const input = within(card).getByLabelText(label) as HTMLInputElement
        fireEvent.change(input, { target: { value: bad } })

        expect(within(card).getByText(expected)).toBeInTheDocument()
        expect(screen.getByRole('button', { name: '開始掃描' })).toBeDisabled()
        const scanCalls = fetchMock.mock.calls.filter((c) => String(c[0]).startsWith('/api/strategies/scan')).length
        expect(scanCalls).toBe(0)
      },
    )

    it.each([
      ['INVALID_WINDOW_DAYS', 'INSTITUTIONAL_NET_RATIO', '法人買賣超佔比'],
      ['INVALID_RATIO_PERCENT', 'INSTITUTIONAL_NET_RATIO', '法人買賣超佔比'],
      ['INVALID_BUY_DAYS', 'INSTITUTIONAL_CONSECUTIVE_BUY', '法人連續買超'],
      ['INVALID_WINDOW_DAYS', 'INSTITUTIONAL_STRENGTH_RANK', '法人買超強度排名'],
      ['INVALID_TOP_N', 'INSTITUTIONAL_STRENGTH_RANK', '法人買超強度排名'],
    ])('shows the backend %s error under the %s card (%s), not as a page-wide error', async (code, strategyCode, cardName) => {
      fetchMock.mockImplementation((url: string) => {
        const u = String(url)
        if (u.startsWith('/api/strategies/scan')) return Promise.resolve(jsonResponse(400, { code, strategy: strategyCode }))
        if (u.startsWith('/api/strategies')) return Promise.resolve(jsonResponse(200, CATALOG))
        if (u.startsWith('/api/stocks/sync/progress')) return Promise.resolve(jsonResponse(200, progressResponse()))
        return Promise.resolve(jsonResponse(200, {}))
      })
      renderTab()
      await waitFor(() => expect(screen.getByText(cardName)).toBeInTheDocument())
      selectStrategy(cardName)
      const card = cardFor(cardName)
      fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))

      await waitFor(() => expect(card.querySelectorAll('.st-inline-error').length).toBeGreaterThan(0))
      expect(screen.queryByText('掃描失敗，請稍後再試')).not.toBeInTheDocument()
    })

    it('shows 請至少勾選一個法人 under the named card for the backend INVALID_INVESTORS fallback', async () => {
      fetchMock.mockImplementation((url: string) => {
        const u = String(url)
        if (u.startsWith('/api/strategies/scan')) {
          return Promise.resolve(jsonResponse(400, { code: 'INVALID_INVESTORS', strategy: 'INSTITUTIONAL_STRENGTH_RANK' }))
        }
        if (u.startsWith('/api/strategies')) return Promise.resolve(jsonResponse(200, CATALOG))
        if (u.startsWith('/api/stocks/sync/progress')) return Promise.resolve(jsonResponse(200, progressResponse()))
        return Promise.resolve(jsonResponse(200, {}))
      })
      renderTab()
      await waitFor(() => expect(screen.getByText('法人買超強度排名')).toBeInTheDocument())
      selectStrategy('法人買超強度排名')
      fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))

      const card = cardFor('法人買超強度排名')
      await waitFor(() => expect(within(card).getByText('請至少勾選一個法人')).toBeInTheDocument())
      expect(screen.queryByText('掃描失敗，請稍後再試')).not.toBeInTheDocument()
    })

    it('formats 命中策略與訊號日 for institutional hits — matched investors (and direction for 法人買賣超佔比) — the same format in the collapsed row and in every expanded child row', async () => {
      scanResponder = () => institutionalExpansionScanResponse()
      renderTab()
      await waitFor(() => expect(screen.getByText('法人買賣超佔比')).toBeInTheDocument())
      selectStrategy('法人買賣超佔比')
      selectStrategy('法人連續買超')
      fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
      await waitFor(() => expect(screen.getByText('9999 測試股')).toBeInTheDocument())

      // 「命中策略與訊號日」renders the strategy name and the「（達標方）」segment as two
      // separate text nodes inside the same `.st-union-tag-name` span, so a plain
      // `getByText(...)` (which does not match across sibling text nodes) can't be used
      // here — read the whole tag's own textContent instead.
      const tagTexts = () => Array.from(document.querySelectorAll('.st-union-tag')).map((el) => el.textContent)

      // Collapsed row: both hits listed, each in its own institutional format.
      expect(tagTexts()).toContain('法人買賣超佔比（外資買超／投信賣超） 2026-08-27')
      expect(tagTexts()).toContain('法人連續買超（投信） 2026-08-19')

      // Expand — each child row (one distinct buyDate each) keeps the exact same format.
      fireEvent.click(screen.getByRole('button', { name: '展開 9999 的訊號日明細' }))
      const childRows = screen.getAllByRole('row').filter((r) => r.className.includes('st-child-row'))
      expect(childRows).toHaveLength(2)
      const childTagTexts = childRows.flatMap((r) => Array.from(r.querySelectorAll('.st-union-tag')).map((el) => el.textContent))
      expect(childTagTexts).toContain('法人買賣超佔比（外資買超／投信賣超） 2026-08-27')
      expect(childTagTexts).toContain('法人連續買超（投信） 2026-08-19')
    })

    it("shows 買進日 from the response's buyDate (different from 訊號日) and sends that buyDate — never signalDate+1 — to 回測", async () => {
      scanResponder = () => netRatioScanResponse()
      backtestResponder = () => ({ status: 200, body: netRatioBacktestResponse() })
      renderTab()
      await waitFor(() => expect(screen.getByText('法人買賣超佔比')).toBeInTheDocument())
      selectStrategy('法人買賣超佔比')
      fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
      await waitFor(() => expect(screen.getByText('賣出日')).toBeInTheDocument())

      const row = screen.getByLabelText('納入 2609 計算').closest('tr') as HTMLElement
      const cells = within(row).getAllByRole('cell')
      expect(cells[3].textContent).toContain('2026-08-27') // 命中策略與訊號日：signalDate
      expect(cells[4].textContent).toBe('2026-08-28') // 買進日：buyDate ≠ signalDate

      const backtestCall = fetchMock.mock.calls.find((c) => String(c[0]).startsWith('/api/strategies/backtest'))
      const body = JSON.parse((backtestCall![1] as RequestInit).body as string)
      expect(body.items).toEqual([{ stockId: '2609', buyDate: '2026-08-28' }])
    })

    it.each([
      [() => netRatioScanResponse(), '法人買賣超佔比', '法人買賣超佔比（外資／投信・5 日合計 ≥ 10%・法人資料至 2026-08-28）'],
      [() => consecutiveBuyScanResponse(), '法人連續買超', '法人連續買超（投信・連 5 日・法人資料至 2026-08-28）'],
      [() => strengthRankScanResponse(), '法人買超強度排名', '法人買超強度排名（外資／投信・5 日強度・各前 10 名・尚無法人資料）'],
    ])('shows 本次採用參數 for %s sourced from the scan response — including 法人資料至/尚無法人資料 — not the current inputs', async (responder, cardName, expected) => {
      scanResponder = responder
      renderTab()
      await waitFor(() => expect(screen.getByText(cardName)).toBeInTheDocument())
      selectStrategy(cardName)
      // Edit the input after selecting but before scanning — the params line must still
      // reflect what the RESPONSE says was actually used, never this unsaved edit.
      const card = cardFor(cardName)
      const dayLikeInput = within(card).queryByLabelText('天數') ?? within(card).queryByLabelText('連續買超天數')
      if (dayLikeInput) fireEvent.change(dayLikeInput, { target: { value: '9' } })
      fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))

      await waitFor(() => expect(screen.getByText(expected)).toBeInTheDocument())
    })

    it('shows 「{策略名稱}：另有 N 檔已達標，但次一交易日尚未到」for institutional pendingConfirm, excluded from the merged table and from 回測', async () => {
      scanResponder = () => consecutiveBuyScanResponse()
      renderTab()
      await waitFor(() => expect(screen.getByText('法人連續買超')).toBeInTheDocument())
      selectStrategy('法人連續買超')
      fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))

      await waitFor(() => expect(screen.getByText('法人連續買超：另有 1 檔已達標，但次一交易日尚未到')).toBeInTheDocument())
      expect(topLevelRowStockIds()).toEqual(['2317 鴻海'])
      const backtestCall = await vi.waitFor(() =>
        fetchMock.mock.calls.find((c) => String(c[0]).startsWith('/api/strategies/backtest')),
      )
      const body = JSON.parse((backtestCall![1] as RequestInit).body as string)
      expect(body.items.some((i: { stockId: string }) => i.stockId === '2330')).toBe(false)
    })

    it('shows 「{策略名稱}：另有 N 檔因行情或法人資料不足而未納入判定」for institutional insufficientData; the five price patterns keep their existing wording', async () => {
      scanResponder = () => strengthRankScanResponse()
      renderTab()
      await waitFor(() => expect(screen.getByText('法人買超強度排名')).toBeInTheDocument())
      selectStrategy('法人買超強度排名')
      fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))

      await waitFor(() =>
        expect(screen.getByText('法人買超強度排名：另有 1 檔因行情或法人資料不足而未納入判定')).toBeInTheDocument(),
      )
      // Never the price-pattern wording for an institutional strategy's own note.
      expect(screen.queryByText('法人買超強度排名：另有 1 檔因區間前的歷史資料不足而未納入判定')).not.toBeInTheDocument()
    })

    it('never uses 建議／推薦／可進場 wording anywhere in the three institutional cards or their scan-result text', async () => {
      scanResponder = () => netRatioScanResponse()
      renderTab()
      await waitFor(() => expect(screen.getByText('法人買賣超佔比')).toBeInTheDocument())
      selectStrategy('法人買賣超佔比')
      fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
      await waitFor(() => expect(screen.getByText('賣出日')).toBeInTheDocument())

      const text = document.body.textContent ?? ''
      expect(text).not.toContain('建議')
      expect(text).not.toContain('推薦')
      expect(text).not.toContain('可進場')
    })
  })

  describe('技術指標卡片', () => {
    it('renders MACD/KDJ cards after the institutional cards, with name/description/params from GET /api/strategies and no sensitivity dropdown', async () => {
      renderTab()
      await waitFor(() => expect(screen.getByText('MACD 黃金交叉')).toBeInTheDocument())
      expect(screen.getByText('KDJ 黃金交叉')).toBeInTheDocument()

      const cardNames = Array.from(document.querySelectorAll('.st-strategy-cards > .st-strategy-card')).map(
        (c) => c.querySelector('.st-strategy-name')?.textContent,
      )
      expect(cardNames.slice(-2)).toEqual(['MACD 黃金交叉', 'KDJ 黃金交叉'])

      const macdCard = cardFor('MACD 黃金交叉')
      expect(
        within(macdCard).getByText('DIF（短期 EMA − 長期 EMA）由下往上穿越 DEA（DIF 的 9 日 EMA）當日為訊號日'),
      ).toBeInTheDocument()
      expect(within(macdCard).queryByRole('combobox')).not.toBeInTheDocument()
      const kdjCard = cardFor('KDJ 黃金交叉')
      expect(within(kdjCard).queryByRole('combobox')).not.toBeInTheDocument()

      selectStrategy('MACD 黃金交叉')
      expect((within(macdCard).getByLabelText('短期 EMA') as HTMLInputElement).value).toBe('5')
      expect((within(macdCard).getByLabelText('長期 EMA') as HTMLInputElement).value).toBe('20')
      selectStrategy('KDJ 黃金交叉')
      expect((within(kdjCard).getByLabelText('J 門檻') as HTMLInputElement).value).toBe('40')
    })

    it('shows the 回看的交易日數 hint under both MACD EMA inputs (unit 日)', async () => {
      renderTab()
      await waitFor(() => expect(screen.getByText('MACD 黃金交叉')).toBeInTheDocument())
      selectStrategy('MACD 黃金交叉')
      const card = cardFor('MACD 黃金交叉')
      expect(within(card).getAllByText('回看的交易日數，不含週末與休市日')).toHaveLength(2)
    })

    it('draws no suffix box for KDJ「J 門檻」(empty unit) and accepts a negative value', async () => {
      renderTab()
      await waitFor(() => expect(screen.getByText('KDJ 黃金交叉')).toBeInTheDocument())
      selectStrategy('KDJ 黃金交叉')
      const card = cardFor('KDJ 黃金交叉')
      const row = within(card).getByLabelText('J 門檻').closest('.st-param-input-row') as HTMLElement
      expect(row.querySelector('.st-param-suffix')).toBeNull()

      fireEvent.change(within(card).getByLabelText('J 門檻'), { target: { value: '-12.5' } })
      expect(within(card).queryByText(/J 門檻需介於/)).not.toBeInTheDocument()
      expect(screen.getByRole('button', { name: '開始掃描' })).not.toBeDisabled()
    })

    it.each([
      ['-100.1', true],
      ['100.1', true],
      ['40.25', true],
      ['-100', false],
      ['100', false],
    ])('J 門檻＝%s → blocked: %s', async (value, shouldBlock) => {
      renderTab()
      await waitFor(() => expect(screen.getByText('KDJ 黃金交叉')).toBeInTheDocument())
      selectStrategy('KDJ 黃金交叉')
      const card = cardFor('KDJ 黃金交叉')
      fireEvent.change(within(card).getByLabelText('J 門檻'), { target: { value } })
      if (shouldBlock) {
        expect(within(card).getByText('J 門檻需介於 -100 ~ 100')).toBeInTheDocument()
        expect(screen.getByRole('button', { name: '開始掃描' })).toBeDisabled()
      } else {
        expect(within(card).queryByText('J 門檻需介於 -100 ~ 100')).not.toBeInTheDocument()
        expect(screen.getByRole('button', { name: '開始掃描' })).not.toBeDisabled()
      }
    })

    it.each([
      ['短期 EMA', '1', '短期 EMA需介於 2 ~ 50 的整數'],
      ['短期 EMA', '51', '短期 EMA需介於 2 ~ 50 的整數'],
      ['短期 EMA', '12.5', '短期 EMA需介於 2 ~ 50 的整數'],
      ['長期 EMA', '2', '長期 EMA需介於 3 ~ 100 的整數'],
      ['長期 EMA', '101', '長期 EMA需介於 3 ~ 100 的整數'],
    ])('blocks the scan and shows the range error for %s＝%s (message: %s)', async (label, bad, expected) => {
      renderTab()
      await waitFor(() => expect(screen.getByText('MACD 黃金交叉')).toBeInTheDocument())
      selectStrategy('MACD 黃金交叉')
      const card = cardFor('MACD 黃金交叉')
      fireEvent.change(within(card).getByLabelText(label), { target: { value: bad } })
      expect(within(card).getByText(expected)).toBeInTheDocument()
      expect(screen.getByRole('button', { name: '開始掃描' })).toBeDisabled()
    })

    it.each([
      ['26', '26'],
      ['30', '20'],
    ])('blocks the scan and shows 短期 EMA需小於長期 EMA when 短期 EMA ≥ 長期 EMA (%s／%s); fixing either side clears it', async (fast, slow) => {
      renderTab()
      await waitFor(() => expect(screen.getByText('MACD 黃金交叉')).toBeInTheDocument())
      selectStrategy('MACD 黃金交叉')
      const card = cardFor('MACD 黃金交叉')
      fireEvent.change(within(card).getByLabelText('短期 EMA'), { target: { value: fast } })
      fireEvent.change(within(card).getByLabelText('長期 EMA'), { target: { value: slow } })

      expect(within(card).getByText('短期 EMA需小於長期 EMA')).toBeInTheDocument()
      expect(screen.getByRole('button', { name: '開始掃描' })).toBeDisabled()

      fireEvent.change(within(card).getByLabelText('長期 EMA'), { target: { value: '100' } })
      expect(within(card).queryByText('短期 EMA需小於長期 EMA')).not.toBeInTheDocument()
      expect(screen.getByRole('button', { name: '開始掃描' })).not.toBeDisabled()
    })

    it('shows only the range error, not 短期 EMA需小於長期 EMA, when one side is itself out of range', async () => {
      renderTab()
      await waitFor(() => expect(screen.getByText('MACD 黃金交叉')).toBeInTheDocument())
      selectStrategy('MACD 黃金交叉')
      const card = cardFor('MACD 黃金交叉')
      fireEvent.change(within(card).getByLabelText('短期 EMA'), { target: { value: '51' } })
      fireEvent.change(within(card).getByLabelText('長期 EMA'), { target: { value: '10' } }) // would also violate lessThan

      expect(within(card).getByText('短期 EMA需介於 2 ~ 50 的整數')).toBeInTheDocument()
      expect(within(card).queryByText('短期 EMA需小於長期 EMA')).not.toBeInTheDocument()
    })

    it('stops blocking 短期 EMA ≥ 長期 EMA once the catalogue response omits lessThan (proves the check is data-driven, not hard-coded to fastPeriod/slowPeriod)', async () => {
      const noLessThanCatalog = {
        strategies: CATALOG.strategies.map((s) => {
          if (s.code !== 'MACD_GOLDEN_CROSS') return s
          return {
            ...s,
            params: (s.params as unknown[]).map((p) => {
              const copy = { ...(p as Record<string, unknown>) }
              delete copy.lessThan
              return copy
            }),
          }
        }),
      }
      fetchMock.mockImplementation((url: string) => {
        const u = String(url)
        if (u.startsWith('/api/strategies/scan')) return Promise.resolve(jsonResponse(200, scanResponder()))
        if (u.startsWith('/api/strategies')) return Promise.resolve(jsonResponse(200, noLessThanCatalog))
        if (u.startsWith('/api/stocks/sync/progress')) return Promise.resolve(jsonResponse(200, progressResponse()))
        return Promise.resolve(jsonResponse(200, {}))
      })
      renderTab()
      await waitFor(() => expect(screen.getByText('MACD 黃金交叉')).toBeInTheDocument())
      selectStrategy('MACD 黃金交叉')
      const card = cardFor('MACD 黃金交叉')
      fireEvent.change(within(card).getByLabelText('短期 EMA'), { target: { value: '26' } })
      fireEvent.change(within(card).getByLabelText('長期 EMA'), { target: { value: '26' } })

      expect(within(card).queryByText('短期 EMA需小於長期 EMA')).not.toBeInTheDocument()
      expect(screen.getByRole('button', { name: '開始掃描' })).not.toBeDisabled()
    })

    it('sends { code, fastPeriod, slowPeriod } for MACD and { code, jThreshold } for KDJ — never preset/risePercent/days', async () => {
      scanResponder = () => macdScanResponse()
      renderTab()
      await waitFor(() => expect(screen.getByText('MACD 黃金交叉')).toBeInTheDocument())
      selectStrategy('MACD 黃金交叉')
      selectStrategy('KDJ 黃金交叉')
      fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))

      const scanCall = await vi.waitFor(() =>
        fetchMock.mock.calls.find((c) => String(c[0]).startsWith('/api/strategies/scan')),
      )
      const body = JSON.parse((scanCall![1] as RequestInit).body as string)
      expect(body.strategies).toEqual([
        { code: 'MACD_GOLDEN_CROSS', fastPeriod: 5, slowPeriod: 20 },
        { code: 'KDJ_GOLDEN_CROSS', jThreshold: 40 },
      ])
    })

    it("disables both cards' inputs when unchecked, and omits an unchecked strategy from the scan request", async () => {
      scanResponder = () => macdScanResponse()
      renderTab()
      await waitFor(() => expect(screen.getByText('MACD 黃金交叉')).toBeInTheDocument())
      const macdCard = cardFor('MACD 黃金交叉')
      expect((within(macdCard).getByLabelText('短期 EMA') as HTMLInputElement).disabled).toBe(true)
      expect((within(macdCard).getByLabelText('長期 EMA') as HTMLInputElement).disabled).toBe(true)
      const kdjCard = cardFor('KDJ 黃金交叉')
      expect((within(kdjCard).getByLabelText('J 門檻') as HTMLInputElement).disabled).toBe(true)

      selectStrategy('MACD 黃金交叉') // only MACD checked
      fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))

      const scanCall = await vi.waitFor(() =>
        fetchMock.mock.calls.find((c) => String(c[0]).startsWith('/api/strategies/scan')),
      )
      const body = JSON.parse((scanCall![1] as RequestInit).body as string)
      expect(body.strategies).toEqual([{ code: 'MACD_GOLDEN_CROSS', fastPeriod: 5, slowPeriod: 20 }])
    })

    it.each([
      ['INVALID_FAST_PERIOD', 'MACD_GOLDEN_CROSS', 'MACD 黃金交叉', '短期 EMA需介於 2 ~ 50 的整數'],
      ['INVALID_SLOW_PERIOD', 'MACD_GOLDEN_CROSS', 'MACD 黃金交叉', '長期 EMA需介於 3 ~ 100 的整數'],
      ['INVALID_MACD_PERIODS', 'MACD_GOLDEN_CROSS', 'MACD 黃金交叉', '短期 EMA需小於長期 EMA'],
      ['INVALID_J_THRESHOLD', 'KDJ_GOLDEN_CROSS', 'KDJ 黃金交叉', 'J 門檻需介於 -100 ~ 100'],
    ])('shows the backend %s error under the %s card (%s), not as a page-wide error', async (code, strategyCode, cardName, expected) => {
      fetchMock.mockImplementation((url: string) => {
        const u = String(url)
        if (u.startsWith('/api/strategies/scan')) return Promise.resolve(jsonResponse(400, { code, strategy: strategyCode }))
        if (u.startsWith('/api/strategies')) return Promise.resolve(jsonResponse(200, CATALOG))
        if (u.startsWith('/api/stocks/sync/progress')) return Promise.resolve(jsonResponse(200, progressResponse()))
        return Promise.resolve(jsonResponse(200, {}))
      })
      renderTab()
      await waitFor(() => expect(screen.getByText(cardName)).toBeInTheDocument())
      selectStrategy(cardName)
      const card = cardFor(cardName)
      fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))

      await waitFor(() => expect(within(card).getByText(expected)).toBeInTheDocument())
      expect(screen.queryByText('掃描失敗，請稍後再試')).not.toBeInTheDocument()
    })

    it.each([
      [() => macdScanResponse(), 'MACD 黃金交叉', 'MACD 黃金交叉（12／26／9）'],
      [() => kdjScanResponse(), 'KDJ 黃金交叉', 'KDJ 黃金交叉（前一日 J < -10）'],
    ])('shows 本次採用參數 for %s sourced from the scan response — not the current inputs', async (responder, cardName, expected) => {
      scanResponder = responder
      renderTab()
      await waitFor(() => expect(screen.getByText(cardName)).toBeInTheDocument())
      selectStrategy(cardName)
      // Edit an input after selecting but before scanning — the params line must still
      // reflect what the RESPONSE says was actually used, never this unsaved edit. (`6`
      // stays below the catalogue's own 長期 EMA default of 20, so this edit alone never
      // trips 「短期 EMA需小於長期 EMA」 and blocks the scan.)
      const card = cardFor(cardName)
      const emaInput = within(card).queryByLabelText('短期 EMA')
      if (emaInput) fireEvent.change(emaInput, { target: { value: '6' } })
      fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))

      await waitFor(() => expect(screen.getByText(expected)).toBeInTheDocument())
    })

    it('shows 「MACD 黃金交叉 {signalDate}」in 命中策略與訊號日, 買進日 equal to signalDate, and sends that buyDate to 回測', async () => {
      scanResponder = () => macdScanResponse()
      backtestResponder = () => ({ status: 200, body: macdBacktestResponse() })
      renderTab()
      await waitFor(() => expect(screen.getByText('MACD 黃金交叉')).toBeInTheDocument())
      selectStrategy('MACD 黃金交叉')
      fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
      await waitFor(() => expect(screen.getByText('賣出日')).toBeInTheDocument())

      expect(document.querySelector('.st-union-tag')?.textContent).toBe('MACD 黃金交叉 2026-08-26')
      const row = screen.getByLabelText('納入 2330 計算').closest('tr') as HTMLElement
      const cells = within(row).getAllByRole('cell')
      expect(cells[3].textContent).toContain('2026-08-26') // 命中策略與訊號日：signalDate
      expect(cells[4].textContent).toBe('2026-08-26') // 買進日：等於 signalDate

      const backtestCall = fetchMock.mock.calls.find((c) => String(c[0]).startsWith('/api/strategies/backtest'))
      const body = JSON.parse((backtestCall![1] as RequestInit).body as string)
      expect(body.items).toEqual([{ stockId: '2330', buyDate: '2026-08-26' }])
    })

    it('shows 「MACD 黃金交叉：另有 N 檔因區間前的歷史資料不足而未納入判定」for insufficientData; no 待確認 line for either technical-indicator strategy', async () => {
      scanResponder = () => macdScanResponse()
      renderTab()
      await waitFor(() => expect(screen.getByText('MACD 黃金交叉')).toBeInTheDocument())
      selectStrategy('MACD 黃金交叉')
      fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))

      await waitFor(() =>
        expect(screen.getByText('MACD 黃金交叉：另有 1 檔因區間前的歷史資料不足而未納入判定')).toBeInTheDocument(),
      )
      expect(screen.queryByText(/已達標，但次一交易日尚未到/)).not.toBeInTheDocument()
    })

    it('never uses 建議／推薦／可進場 wording anywhere in the two technical-indicator cards or their scan-result text', async () => {
      scanResponder = () => macdScanResponse()
      renderTab()
      await waitFor(() => expect(screen.getByText('MACD 黃金交叉')).toBeInTheDocument())
      selectStrategy('MACD 黃金交叉')
      fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
      await waitFor(() => expect(screen.getByText('賣出日')).toBeInTheDocument())

      const text = document.body.textContent ?? ''
      expect(text).not.toContain('建議')
      expect(text).not.toContain('推薦')
      expect(text).not.toContain('可進場')
    })
  })

  describe('MACD 預設天數改為 5／20', () => {
    it('shows 短期 EMA=5／長期 EMA=20 sourced from GET /api/strategies「default」— never hard-coded, changing the mock default changes what the card shows', async () => {
      const first = renderTab()
      await waitFor(() => expect(screen.getByText('MACD 黃金交叉')).toBeInTheDocument())
      selectStrategy('MACD 黃金交叉')
      const card = cardFor('MACD 黃金交叉')
      expect((within(card).getByLabelText('短期 EMA') as HTMLInputElement).value).toBe('5')
      expect((within(card).getByLabelText('長期 EMA') as HTMLInputElement).value).toBe('20')
      first.unmount()

      // Change the mock catalogue's own default to an arbitrary different pair (7／15) —
      // if the frontend hard-coded 5／20 anywhere, the card would still show 5／20 here.
      const otherDefaultCatalog = {
        strategies: CATALOG.strategies.map((s) => {
          if (s.code !== 'MACD_GOLDEN_CROSS') return s
          return {
            ...s,
            params: (s.params as unknown[]).map((p) => {
              const param = p as Record<string, unknown>
              if (param.code === 'fastPeriod') return { ...param, default: 7 }
              if (param.code === 'slowPeriod') return { ...param, default: 15 }
              return param
            }),
          }
        }),
      }
      fetchMock.mockImplementation((url: string) => {
        const u = String(url)
        if (u.startsWith('/api/strategies/scan')) return Promise.resolve(jsonResponse(200, scanResponder()))
        if (u.startsWith('/api/strategies')) return Promise.resolve(jsonResponse(200, otherDefaultCatalog))
        if (u.startsWith('/api/stocks/sync/progress')) return Promise.resolve(jsonResponse(200, progressResponse()))
        return Promise.resolve(jsonResponse(200, {}))
      })
      renderTab()
      await waitFor(() => expect(screen.getByText('MACD 黃金交叉')).toBeInTheDocument())
      selectStrategy('MACD 黃金交叉')
      const card2 = cardFor('MACD 黃金交叉')
      expect((within(card2).getByLabelText('短期 EMA') as HTMLInputElement).value).toBe('7')
      expect((within(card2).getByLabelText('長期 EMA') as HTMLInputElement).value).toBe('15')
    })

    it('scans with the default inputs untouched → sends { code: "MACD_GOLDEN_CROSS", fastPeriod: 5, slowPeriod: 20 }, and 本次採用參數 shows 「MACD 黃金交叉（5／20／9）」', async () => {
      scanResponder = () => macdDefaultScanResponse()
      renderTab()
      await waitFor(() => expect(screen.getByText('MACD 黃金交叉')).toBeInTheDocument())
      selectStrategy('MACD 黃金交叉')
      fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))

      const scanCall = await vi.waitFor(() =>
        fetchMock.mock.calls.find((c) => String(c[0]).startsWith('/api/strategies/scan')),
      )
      const body = JSON.parse((scanCall![1] as RequestInit).body as string)
      expect(body.strategies).toEqual([{ code: 'MACD_GOLDEN_CROSS', fastPeriod: 5, slowPeriod: 20 }])

      await waitFor(() => expect(screen.getByText('MACD 黃金交叉（5／20／9）')).toBeInTheDocument())
    })

    it('default 5／20 does not trigger 短期 EMA需小於長期 EMA; setting 長期 EMA to 5 triggers it and blocks the scan, restoring it to 6+ clears it', async () => {
      renderTab()
      await waitFor(() => expect(screen.getByText('MACD 黃金交叉')).toBeInTheDocument())
      selectStrategy('MACD 黃金交叉')
      const card = cardFor('MACD 黃金交叉')
      expect(within(card).queryByText('短期 EMA需小於長期 EMA')).not.toBeInTheDocument()
      expect(screen.getByRole('button', { name: '開始掃描' })).not.toBeDisabled()

      fireEvent.change(within(card).getByLabelText('長期 EMA'), { target: { value: '5' } })
      expect(within(card).getByText('短期 EMA需小於長期 EMA')).toBeInTheDocument()
      expect(screen.getByRole('button', { name: '開始掃描' })).toBeDisabled()

      fireEvent.change(within(card).getByLabelText('長期 EMA'), { target: { value: '6' } })
      expect(within(card).queryByText('短期 EMA需小於長期 EMA')).not.toBeInTheDocument()
      expect(screen.getByRole('button', { name: '開始掃描' })).not.toBeDisabled()
    })
  })

  describe('視窗寬 ≤ 1280px 日期與價格疊欄', () => {
    function setInnerWidth(width: number): void {
      Object.defineProperty(window, 'innerWidth', { writable: true, configurable: true, value: width })
    }

    afterEach(() => {
      // setupTests.ts defaults `window.innerWidth` to 1920 (wide) for every other test in
      // this suite — restore it so a narrow width set in one test here never leaks into the
      // next (this file's own top-level `afterEach` never touches `innerWidth`).
      setInnerWidth(1920)
    })

    it('merges 買進日／賣出日 and 買進價／賣出價 into two stacked, buy-over-sell columns at 1280px, and restores the four independent columns at 1281px — same values/format', async () => {
      setInnerWidth(1280)
      scanResponder = () => sortableScanResponse()
      backtestResponder = () => ({ status: 200, body: sortableBacktestResponse() })
      renderTab()
      await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
      selectStrategy('箱型突破')
      fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
      await waitFor(() => expect(screen.getByText('賣出日')).toBeInTheDocument())
      revealIncompletePositions()

      const table = screen.getByRole('table')
      // 展開/勾選/代號/命中/日期(合併)/價格(合併)/報酬率/收益 = 8 headers when stacked,
      // vs. 10 when the four columns are independent.
      expect(within(table).getAllByRole('columnheader')).toHaveLength(8)

      // AAAA: buyDate 2026-08-20 buyPrice 100 / sellDate 2026-08-21 sellPrice 105.
      const rowAAAA = screen.getByText('AAAA A股').closest('tr') as HTMLElement
      const cells = within(rowAAAA).getAllByRole('cell')
      // Flex layouts must live INSIDE the <td>: a `display:flex` class on a <td> takes it out
      // of table layout, and adjacent non-table-cell <td>s get merged into one anonymous cell —
      // the hits tags, the dates and the prices then all stack in a single column.
      for (const td of within(table).getAllByRole('cell')) {
        expect(td).not.toHaveClass('st-stacked-cell')
        expect(td).not.toHaveClass('st-union-hits')
      }
      expect(cells[4].firstElementChild).toHaveClass('st-stacked-cell')
      expect(cells[5].firstElementChild).toHaveClass('st-stacked-cell')
      // 上行買進、下行賣出 — 買對買、賣對賣, not date-over-price.
      expect(cells[4].firstElementChild!.children[0].textContent).toBe('2026-08-20')
      expect(cells[4].firstElementChild!.children[1].textContent).toBe('2026-08-21')
      expect(cells[5].firstElementChild!.children[0].textContent).toBe('100.00')
      expect(cells[5].firstElementChild!.children[1].textContent).toBe('105.00')
      // 報酬率／收益 stay their own columns, values/format unchanged.
      expect(cells[6].textContent).toBe('5.00%')
      expect(cells[7].textContent).toBe('5,000')

      // 1281px restores the four independent, unmerged columns — same values.
      setInnerWidth(1281)
      window.dispatchEvent(new Event('resize'))
      await waitFor(() => expect(within(table).getAllByRole('columnheader')).toHaveLength(10))
      const cellsWide = within(rowAAAA).getAllByRole('cell')
      expect(cellsWide[4].textContent).toBe('2026-08-20')
      expect(cellsWide[5].textContent).toBe('100.00')
      expect(cellsWide[6].textContent).toBe('2026-08-21')
      expect(cellsWide[7].textContent).toBe('105.00')
      expect(cellsWide[8].textContent).toBe('5.00%')
      expect(cellsWide[9].textContent).toBe('5,000')
    })

    it('shows a muted「—」on the lower line for an unbacktestable position, and grays both stacked lines on an unchecked row — same as the wide-window columns', async () => {
      setInnerWidth(1024)
      scanResponder = () => sortableScanResponse()
      backtestResponder = () => ({ status: 200, body: sortableBacktestResponse() })
      renderTab()
      await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
      selectStrategy('箱型突破')
      fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
      await waitFor(() => expect(screen.getByText('賣出日')).toBeInTheDocument())
      revealIncompletePositions()

      // CCCC has no sellable trading day at all — buyPrice/sellDate/sellPrice all null.
      const rowCCCC = screen.getByText('CCCC C股').closest('tr') as HTMLElement
      const cellsCCCC = within(rowCCCC).getAllByRole('cell')
      expect(cellsCCCC[4].firstElementChild!.children[0].textContent).toBe('2026-08-15') // buyDate always present
      expect(within(cellsCCCC[4]).getByText('—')).toHaveClass('sl-muted')
      expect(within(cellsCCCC[5]).getAllByText('—').every((el) => el.className === 'sl-muted')).toBe(true)

      // Unchecking a row grays every line in both stacked cells (whole-row override).
      const rowAAAA = screen.getByLabelText('納入 AAAA 計算').closest('tr') as HTMLElement
      fireEvent.click(rowAAAA)
      expect(rowAAAA.className).toContain('st-row-unchecked')
      const cellsAAAA = within(rowAAAA).getAllByRole('cell')
      expect(cellsAAAA[4].firstElementChild!.children[0].textContent).toBe('2026-08-20')
      expect(cellsAAAA[4].firstElementChild!.children[1].textContent).toBe('2026-08-21')
    })

    it('stacks a multi-lot parent row as buy/sell pairs per lot (newest buy first) with correct checked/unchecked coloring, and shows the cost-weighted average buy price over a muted「—」', async () => {
      setInnerWidth(1024)
      scanResponder = () => parentSortScanResponse()
      backtestResponder = () => ({ status: 200, body: parentSortBacktestResponse() })
      renderTab()
      await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
      fireEvent.click(within(screen.getByText('箱型突破').closest('.st-strategy-card')!).getByRole('checkbox'))
      fireEvent.click(within(screen.getByText('底底高').closest('.st-strategy-card')!).getByRole('checkbox'))
      fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
      await waitFor(() => expect(screen.getByText('賣出日')).toBeInTheDocument())
      revealIncompletePositions()

      const parentCheckbox = screen.getByLabelText('納入 2330 全部計算') as HTMLInputElement
      const parentRow = parentCheckbox.closest('tr') as HTMLElement
      let cells = within(parentRow).getAllByRole('cell')
      let pairs = cells[4].querySelectorAll('.st-stacked-pair')
      // Newest buy first (08-27 before 08-20); buy-over-sell within each pair.
      expect(pairs).toHaveLength(2)
      expect(pairs[0].children[0].textContent).toBe('2026-08-27')
      expect(pairs[0].children[1].textContent).toBe('2026-08-28')
      expect(pairs[1].children[0].textContent).toBe('2026-08-20')
      expect(pairs[1].children[1].textContent).toBe('2026-08-25')
      // Both lots checked by default -> neither line muted.
      expect(pairs[0].children[0].className).toBe('')
      expect(pairs[1].children[0].className).toBe('')

      // Price column: cost-weighted average buy price on top ((100×1000+10000×1000)÷2000
      // = 5050.00), muted「—」below (no single aggregate sell price for multiple lots).
      expect(cells[5].firstElementChild!.children[0].textContent).toBe('5050.00')
      expect(within(cells[5].firstElementChild!.children[1] as HTMLElement).getByText('—')).toHaveClass('sl-muted')

      // Uncheck the older (08-20) lot and confirm only its pair grays out.
      fireEvent.click(screen.getByRole('button', { name: '展開 2330 的訊號日明細' }))
      fireEvent.click(screen.getByLabelText('納入 2330 2026-08-20 計算'))
      fireEvent.click(screen.getByRole('button', { name: '收合 2330 的訊號日明細' }))

      cells = within(parentRow).getAllByRole('cell')
      pairs = cells[4].querySelectorAll('.st-stacked-pair')
      expect(pairs[0].children[0].className).toBe('') // 08-27 still checked
      expect(pairs[1].children[0].className).toBe('sl-muted') // 08-20 now unchecked
      expect(pairs[1].children[1].className).toBe('sl-muted')
    })

    it('sorts by the 買進價 line of the stacked price header exactly like the wide-window 買進價 header; the other three stacked lines are not sortable', async () => {
      setInnerWidth(1024)
      scanResponder = () => sortableScanResponse()
      backtestResponder = () => ({ status: 200, body: sortableBacktestResponse() })
      renderTab()
      await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
      selectStrategy('箱型突破')
      fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
      await waitFor(() => expect(screen.getByText('賣出日')).toBeInTheDocument())
      revealIncompletePositions()

      expect(sortHeaderIcon('買進價')).toBe('↕')
      clickSortHeader('買進價') // 1st: 降冪 — BBBB 200 > AAAA 100 > CCCC 「—」(min)
      expect(sortHeaderIcon('買進價')).toBe('▼')
      expect(topLevelRowStockIds()).toEqual(['BBBB B股', 'AAAA A股', 'CCCC C股'])

      clickSortHeader('買進價') // 2nd: 升冪
      expect(sortHeaderIcon('買進價')).toBe('▲')
      expect(topLevelRowStockIds()).toEqual(['CCCC C股', 'AAAA A股', 'BBBB B股'])

      clickSortHeader('買進價') // 3rd: 還原預設排序
      expect(sortHeaderIcon('買進價')).toBe('↕')
      expect(topLevelRowStockIds()).toEqual(['BBBB B股', 'AAAA A股', 'CCCC C股'])

      // 「賣出價」「買進日」「賣出日」都不可排序 — clicking them changes nothing.
      const orderBefore = topLevelRowStockIds()
      fireEvent.click(screen.getByText('賣出價'))
      fireEvent.click(screen.getByText('買進日'))
      fireEvent.click(screen.getByText('賣出日'))
      expect(sortHeaderIcon('買進價')).toBe('↕')
      expect(topLevelRowStockIds()).toEqual(orderBefore)
    })

    it('switches live across the 1280px breakpoint with no reload and no network request, preserving checks, expand, hidden rows, sort and floating totals', async () => {
      setInnerWidth(1920)
      scanResponder = () => parentSortScanResponse()
      backtestResponder = () => ({ status: 200, body: parentSortBacktestResponse() })
      renderTab()
      await waitFor(() => expect(screen.getByText('箱型突破')).toBeInTheDocument())
      fireEvent.click(within(screen.getByText('箱型突破').closest('.st-strategy-card')!).getByRole('checkbox'))
      fireEvent.click(within(screen.getByText('底底高').closest('.st-strategy-card')!).getByRole('checkbox'))
      fireEvent.click(screen.getByRole('button', { name: '開始掃描' }))
      await waitFor(() => expect(screen.getByText('賣出日')).toBeInTheDocument())
      revealIncompletePositions()

      // Build up state at the wide width: uncheck a row, expand the parent, sort by 買進價.
      fireEvent.click(screen.getByLabelText('納入 9999 計算'))
      fireEvent.click(screen.getByRole('button', { name: '展開 2330 的訊號日明細' }))
      clickSortHeader('買進價')
      expect(sortHeaderIcon('買進價')).toBe('▼')

      mockFloatingRects(-40, 300)
      window.dispatchEvent(new Event('scroll'))
      const floatingBefore = await waitForFloatingTotals()
      const floatingValuesBefore = Array.from(floatingBefore.querySelectorAll('.st-total-value')).map((el) => el.textContent)

      const table = screen.getByRole('table')
      expect(within(table).getAllByRole('columnheader')).toHaveLength(10) // wide: 4 独立欄位
      const orderBefore = topLevelRowStockIds()
      const callsBefore = fetchMock.mock.calls.length

      // Cross the breakpoint downward — live, no reload, no network request.
      setInnerWidth(1280)
      window.dispatchEvent(new Event('resize'))
      await waitFor(() => expect(within(table).getAllByRole('columnheader')).toHaveLength(8))
      expect(fetchMock.mock.calls.length).toBe(callsBefore)

      // State preserved: unchecked row, expanded parent, sort, floating totals.
      expect((screen.getByLabelText('納入 9999 計算') as HTMLInputElement).checked).toBe(false)
      expect(screen.getByRole('button', { name: '收合 2330 的訊號日明細' })).toBeInTheDocument()
      expect(sortHeaderIcon('買進價')).toBe('▼')
      expect(topLevelRowStockIds()).toEqual(orderBefore)
      const floatingAfter = await waitForFloatingTotals()
      const floatingValuesAfter = Array.from(floatingAfter.querySelectorAll('.st-total-value')).map((el) => el.textContent)
      expect(floatingValuesAfter).toEqual(floatingValuesBefore)

      // And back up across the breakpoint — still no network request, state still intact.
      setInnerWidth(1281)
      window.dispatchEvent(new Event('resize'))
      await waitFor(() => expect(within(table).getAllByRole('columnheader')).toHaveLength(10))
      expect(fetchMock.mock.calls.length).toBe(callsBefore)
      expect((screen.getByLabelText('納入 9999 計算') as HTMLInputElement).checked).toBe(false)
      expect(screen.getByRole('button', { name: '收合 2330 的訊號日明細' })).toBeInTheDocument()
      expect(sortHeaderIcon('買進價')).toBe('▼')
      expect(topLevelRowStockIds()).toEqual(orderBefore)
    })
  })
})
